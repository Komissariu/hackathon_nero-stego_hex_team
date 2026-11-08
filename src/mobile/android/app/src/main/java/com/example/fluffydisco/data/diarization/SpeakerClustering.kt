package com.example.fluffydisco.data.diarization

import kotlin.math.min

/**
 * Maximum number of speakers is NEVER specified: the cluster count comes from
 * the speaker embeddings. The adaptive cut is a conservative, uncalibrated
 * hypothesis to evaluate on annotated dialogues, not a pretrained classifier.
 */
data class SpeakerClusteringConfig(
    val maxAverageCosineDistance: Float,
    val minReliableDurationSamples: Long,
    val minEmbeddingConfidence: Float,
    val maxMergeGapSamples: Long = 0L,
    val enableAdaptiveCut: Boolean = true,
    val adaptiveMaxDistance: Float = 0.80f,
    val adaptiveMinGap: Float = 0.22f,
    val adaptiveMinRatio: Float = 1.30f
) {
    init {
        require(
            maxAverageCosineDistance.isFinite() &&
                    maxAverageCosineDistance in 0f..2f
        )
        require(minReliableDurationSamples > 0L)
        require(
            minEmbeddingConfidence.isFinite() &&
                    minEmbeddingConfidence in 0f..1f
        )
        require(maxMergeGapSamples >= 0L)
        require(
            adaptiveMaxDistance.isFinite() &&
                    adaptiveMaxDistance in maxAverageCosineDistance..2f
        )
        require(adaptiveMinGap.isFinite() && adaptiveMinGap > 0f)
        require(adaptiveMinRatio.isFinite() && adaptiveMinRatio > 1f)
    }
}

data class SpeakerClusteringDiagnostics(
    val reliableWindows: Int,
    val detectedSpeakers: Int,
    val selectedMaxDistance: Float,
    val adaptiveCutUsed: Boolean,
    val evidenceGap: Float?
)

data class SpeakerClusteringResult(
    val turns: List<SpeakerTurn>,
    val diagnostics: SpeakerClusteringDiagnostics
)

/**
 * Global average-linkage clustering. An unsupervised gap in the linkage history
 * can relax the initial threshold for a returning voice, but never specifies a
 * number of speakers. Short/uncertain windows still cannot create a new ID.
 */
class SpeakerClustering(private val config: SpeakerClusteringConfig) {
    fun cluster(observations: List<SpeakerObservation>): List<SpeakerTurn> =
        clusterWithDiagnostics(observations).turns

    fun clusterWithDiagnostics(observations: List<SpeakerObservation>): SpeakerClusteringResult {
        if (observations.isEmpty()) {
            return SpeakerClusteringResult(
                emptyList(), SpeakerClusteringDiagnostics(
                    0, 0, config.maxAverageCosineDistance, false, null
                )
            )
        }
        val ordered = observations.sortedWith(
            compareBy<SpeakerObservation> { it.interval.startSample }
                .thenBy { it.interval.endSample }
        )
        val reliable = ordered.indices.filter { index ->
            val observation = ordered[index]
            observation.embedding != null &&
                    observation.interval.durationSamples >=
                    config.minReliableDurationSamples &&
                    observation.confidence >= config.minEmbeddingConfidence
        }
        if (reliable.isEmpty()) {
            return SpeakerClusteringResult(
                ordered.map { SpeakerTurn(it.interval, UNKNOWN_SPEAKER_ID, it.confidence) },
                SpeakerClusteringDiagnostics(0, 0, config.maxAverageCosineDistance, false, null)
            )
        }

        val mergeHistory = averageLinkageHistory(reliable.map { ordered[it] })
        val cut = selectCut(mergeHistory)
        val union = UnionFind(reliable.size)
        mergeHistory.forEach { merge ->
            if (merge.distance <= cut.distance) union.join(merge.leftLeaf, merge.rightLeaf)
        }

        val membersByCluster = linkedMapOf<Int, MutableList<Int>>()
        reliable.indices.forEach { position ->
            membersByCluster.getOrPut(union.root(position)) { ArrayList() } += position
        }
        val clustersInTimeOrder = membersByCluster.entries.sortedBy { (_, members) ->
            members.minOf { ordered[reliable[it]].interval.startSample }
        }
        val speakerByCluster = clustersInTimeOrder.mapIndexed { index, entry ->
            entry.key to "speaker_${index + 1}"
        }.toMap()
        val labels = IntArray(ordered.size) { -1 }
        reliable.forEachIndexed { position, observationIndex ->
            labels[observationIndex] = union.root(position)
        }

        // A short/low-duration observation can join a verified cluster if its
        // embedding is good enough. It must not invent an additional speaker.
        val reliableSet = reliable.toHashSet()
        ordered.indices.filter { it !in reliableSet }.forEach { index ->
            val observation = ordered[index]
            val embedding = observation.embedding
            if (embedding == null ||
                observation.confidence < config.minEmbeddingConfidence
            ) return@forEach

            var bestCluster = -1
            var bestDistance = Float.POSITIVE_INFINITY
            membersByCluster.forEach { (cluster, members) ->
                var sum = 0.0
                members.forEach { position ->
                    sum += embedding.cosineDistance(
                        requireNotNull(ordered[reliable[position]].embedding)
                    )
                }
                val distance = (sum / members.size).toFloat()
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestCluster = cluster
                }
            }
            if (bestCluster >= 0 && bestDistance <= cut.distance) {
                labels[index] = bestCluster
            }
        }

        val unrefined = ordered.indices.map { index ->
            val observation = ordered[index]
            SpeakerTurn(
                observation.interval,
                speakerByCluster[labels[index]] ?: UNKNOWN_SPEAKER_ID,
                observation.confidence
            )
        }
        return SpeakerClusteringResult(
            turns = refineOverlappingWindows(unrefined),
            diagnostics = SpeakerClusteringDiagnostics(
                reliableWindows = reliable.size,
                detectedSpeakers = membersByCluster.size,
                selectedMaxDistance = cut.distance,
                adaptiveCutUsed = cut.adaptive,
                evidenceGap = cut.evidenceGap
            )
        )
    }

    /**
     * Store all merges instead of stopping at one uncalibrated threshold.
     * NN-chain average linkage uses the existing bounded dense matrix and
     * avoids the cubic rescanning cost of a naive all-pairs cluster search.
     */
    private fun averageLinkageHistory(observations: List<SpeakerObservation>): List<ClusterMerge> {
        val count = observations.size
        require(count.toLong() * count <= MAX_PAIRWISE_CELLS) {
            "Too many reliable speaker windows for bounded global clustering: $count"
        }
        if (count < 2) return emptyList()
        val distances = FloatArray(count * count)
        val sizes = IntArray(count) { 1 }
        val eligible = BooleanArray(count) { true }
        val representatives = IntArray(count) { it }

        for (left in 0 until count) {
            val embedding = requireNotNull(observations[left].embedding)
            for (right in left + 1 until count) {
                val distance = embedding.cosineDistance(
                    requireNotNull(observations[right].embedding)
                )
                distances[left * count + right] = distance
                distances[right * count + left] = distance
            }
        }

        val merges = ArrayList<ClusterMerge>(count - 1)
        val chain = ArrayList<Int>()
        var eligibleCount = count
        while (eligibleCount > 1) {
            if (chain.isEmpty()) chain += eligible.indices.first { eligible[it] }
            val current = chain.last()
            var nearest = -1
            var nearestDistance = Float.POSITIVE_INFINITY
            for (candidate in 0 until count) {
                if (eligible[candidate] && candidate != current) {
                    val distance = distances[current * count + candidate]
                    if (distance < nearestDistance) {
                        nearest = candidate
                        nearestDistance = distance
                    }
                }
            }
            check(nearest >= 0) { "No active speaker cluster found" }

            if (chain.size > 1 && nearest == chain[chain.lastIndex - 1]) {
                val keep = min(current, nearest)
                val remove = maxOf(current, nearest)
                merges += ClusterMerge(
                    representatives[keep], representatives[remove], nearestDistance
                )
                for (candidate in 0 until count) {
                    if (eligible[candidate] && candidate != keep && candidate != remove) {
                        val distance = (
                                distances[keep * count + candidate] * sizes[keep] +
                                        distances[remove * count + candidate] * sizes[remove]
                                ) / (sizes[keep] + sizes[remove])
                        distances[keep * count + candidate] = distance
                        distances[candidate * count + keep] = distance
                    }
                }
                sizes[keep] += sizes[remove]
                eligible[remove] = false
                eligibleCount--
                chain.removeAt(chain.lastIndex)
                chain.removeAt(chain.lastIndex)
                chain += keep
            } else {
                chain += nearest
            }
        }
        return merges
    }

    private fun selectCut(history: List<ClusterMerge>): Cut {
        val baseline = config.maxAverageCosineDistance
        if (!config.enableAdaptiveCut || history.size < 2) {
            return Cut(baseline, false, null)
        }
        val sorted = history.map { it.distance }.sorted()
        var bestGap = 0f
        var bestLower = 0f
        var bestUpper = 0f
        for (index in 0 until sorted.lastIndex) {
            val lower = sorted[index]
            val upper = sorted[index + 1]
            val gap = upper - lower
            // The baseline already merges everything below it. Require
            // substantially stronger evidence to relax that baseline.
            if (lower < baseline || lower > config.adaptiveMaxDistance ||
                gap < config.adaptiveMinGap ||
                upper / maxOf(lower, 0.01f) < config.adaptiveMinRatio ||
                gap <= bestGap
            ) continue
            bestGap = gap
            bestLower = lower
            bestUpper = upper
        }
        if (bestGap <= 0f) return Cut(baseline, false, null)
        val threshold = min(config.adaptiveMaxDistance, (bestLower + bestUpper) / 2)
        if (threshold < bestLower) return Cut(baseline, false, null)
        return Cut(maxOf(baseline, threshold), true, bestGap)
    }

    private fun refineOverlappingWindows(turns: List<SpeakerTurn>): List<SpeakerTurn> {
        val refined = turns.mapIndexed { index, turn ->
            val previous = turns.getOrNull(index - 1)
            val next = turns.getOrNull(index + 1)
            val start = if (previous != null &&
                previous.speakerId != turn.speakerId &&
                previous.interval.endSample > turn.interval.startSample
            ) {
                (previous.interval.centerSample + turn.interval.centerSample) / 2
            } else turn.interval.startSample
            val end = if (next != null &&
                next.speakerId != turn.speakerId &&
                next.interval.startSample < turn.interval.endSample
            ) {
                (turn.interval.centerSample + next.interval.centerSample) / 2
            } else turn.interval.endSample
            if (end > start) turn.copy(interval = SpeechInterval(start, end)) else turn
        }
        val merged = ArrayList<SpeakerTurn>()
        refined.forEach { turn ->
            val previous = merged.lastOrNull()
            if (previous != null && previous.speakerId == turn.speakerId &&
                turn.interval.startSample - previous.interval.endSample <=
                config.maxMergeGapSamples
            ) {
                merged[merged.lastIndex] = previous.copy(
                    interval = SpeechInterval(
                        previous.interval.startSample,
                        maxOf(previous.interval.endSample, turn.interval.endSample)
                    ),
                    confidence = min(previous.confidence, turn.confidence)
                )
            } else merged += turn
        }
        return merged
    }

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)

        fun root(value: Int): Int {
            var current = value
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun join(a: Int, b: Int) {
            var ra = root(a)
            var rb = root(b)
            if (ra == rb) return
            if (rank[ra] < rank[rb]) {
                val tmp = ra; ra = rb; rb = tmp
            }
            parent[rb] = ra
            if (rank[ra] == rank[rb]) rank[ra]++
        }
    }

    private data class ClusterMerge(val leftLeaf: Int, val rightLeaf: Int, val distance: Float)
    private data class Cut(val distance: Float, val adaptive: Boolean, val evidenceGap: Float?)

    private companion object {
        const val MAX_PAIRWISE_CELLS = 16_777_216L
    }
}
