package com.example.fluffydisco.data.diarization

data class SpeakerChangeConfig(
    val minMeanCosineDistance: Float,
    val contextWindows: Int,
    val minTurnSamples: Long
) {
    init {
        require(
            minMeanCosineDistance.isFinite() &&
                    minMeanCosineDistance in 0f..2f
        )
        require(contextWindows >= 1)
        require(minTurnSamples > 0L)
    }
}

data class SpeakerChangePoint(
    val sample: Long,
    val meanCosineDistance: Float
)

class SpeakerChangeDetector(
    private val config: SpeakerChangeConfig
) {
    fun detect(observations: List<SpeakerObservation>): List<SpeakerChangePoint> {
        val windows = observations
            .filter { it.embedding != null && it.confidence > 0f }
            .sortedBy { it.interval.centerSample }
        if (windows.size < config.contextWindows * 2) return emptyList()

        val changes = ArrayList<SpeakerChangePoint>()
        val lastBoundary = LongArray(windows.size) { Long.MIN_VALUE }
        for (boundaryIndex in config.contextWindows until
                windows.size - config.contextWindows + 1
        ) {
            val left = windows.subList(
                boundaryIndex - config.contextWindows,
                boundaryIndex
            )
            val right = windows.subList(
                boundaryIndex,
                boundaryIndex + config.contextWindows
            )
            var distanceSum = 0.0
            var pairCount = 0
            left.forEach { leftWindow ->
                right.forEach { rightWindow ->
                    distanceSum += requireNotNull(leftWindow.embedding)
                        .cosineDistance(requireNotNull(rightWindow.embedding))
                    pairCount++
                }
            }
            val meanDistance = (distanceSum / pairCount).toFloat()
            if (meanDistance < config.minMeanCosineDistance) continue

            val before = windows[boundaryIndex - 1].interval.centerSample
            val after = windows[boundaryIndex].interval.centerSample
            val sample = before + (after - before) / 2
            if (changes.lastOrNull()?.let {
                    sample - it.sample < config.minTurnSamples
                } == true
            ) {
                continue
            }
            changes += SpeakerChangePoint(sample, meanDistance.coerceIn(0f, 2f))
        }
        return changes
    }
}
