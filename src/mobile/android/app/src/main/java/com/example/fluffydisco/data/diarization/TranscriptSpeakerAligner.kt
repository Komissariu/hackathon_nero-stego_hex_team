package com.example.fluffydisco.data.diarization

import com.example.fluffydisco.domain.model.TranscriptSegment

data class TranscriptAlignmentDiagnostics(
    val asrSegmentCount: Int,
    val speakerTurnCount: Int,
    val segmentsOverlappingMultipleSpeakers: Int,
    val segmentsOverlappingUnknownTurns: Int,
    val unknownSegmentCount: Int,
    val assignedSegmentsBySpeaker: Map<String, Int>
)

data class TranscriptAlignmentResult(
    val segments: List<TranscriptSegment>,
    val diagnostics: TranscriptAlignmentDiagnostics
)

class TranscriptSpeakerAligner(
    private val minimumDominantOverlap: Double
) {
    init {
        require(
            minimumDominantOverlap.isFinite() &&
                    minimumDominantOverlap in 0.5..1.0
        )
    }

    /**
     * Keeps each ASR segment intact. A speaker is assigned only when the union of
     * that speaker's turns unambiguously covers most of the segment; text is never
     * divided by duration.
     */
    fun align(
        segments: List<TranscriptSegment>,
        turns: List<SpeakerTurn>,
        sampleRate: Int
    ): List<TranscriptSegment> =
        alignWithDiagnostics(segments, turns, sampleRate).segments

    fun alignWithDiagnostics(
        segments: List<TranscriptSegment>,
        turns: List<SpeakerTurn>,
        sampleRate: Int
    ): TranscriptAlignmentResult {
        require(sampleRate > 0)
        val sortedTurns = turns.sortedBy { it.interval.startSample }
        var multiSpeakerSegmentCount = 0
        var unknownTurnOverlapCount = 0
        val alignedSegments = segments.map { segment ->
            require(segment.startMs >= 0L && segment.endMs > segment.startMs) {
                "Transcript segment has invalid timestamps"
            }
            val startSample = segment.startMs * sampleRate / 1000L
            val endSample = segment.endMs * sampleRate / 1000L
            val duration = endSample - startSample
            var bestSpeaker = UNKNOWN_SPEAKER_ID
            var bestSamples = 0L
            var overlapsUnknownSpeaker = false
            val overlapRangesBySpeaker = HashMap<String, MutableList<SampleRange>>()

            sortedTurns.forEach { turn ->
                val overlapStart = maxOf(startSample, turn.interval.startSample)
                val overlapEnd = minOf(endSample, turn.interval.endSample)
                if (overlapEnd > overlapStart) {
                    if (turn.speakerId == UNKNOWN_SPEAKER_ID) {
                        overlapsUnknownSpeaker = true
                    } else {
                        overlapRangesBySpeaker
                            .getOrPut(turn.speakerId) { ArrayList() }
                            .add(SampleRange(overlapStart, overlapEnd))
                    }
                }
            }

            val overlapBySpeaker = overlapRangesBySpeaker.mapValues { (_, ranges) ->
                unionLength(ranges)
            }
            overlapBySpeaker.maxByOrNull { it.value }?.let { (speaker, samples) ->
                bestSpeaker = speaker
                bestSamples = samples
            }
            val hasTie = overlapBySpeaker.count { it.value == bestSamples && bestSamples > 0L } > 1
            val overlappingSpeakers = overlapBySpeaker.keys
            if (overlappingSpeakers.size > 1) multiSpeakerSegmentCount++
            if (overlapsUnknownSpeaker) unknownTurnOverlapCount++
            val alignedSpeaker = if (overlappingSpeakers.size <= 1 &&
                !overlapsUnknownSpeaker &&
                !hasTie &&
                bestSpeaker != UNKNOWN_SPEAKER_ID &&
                bestSamples.toDouble() / duration >= minimumDominantOverlap
            ) {
                bestSpeaker
            } else {
                UNKNOWN_SPEAKER_ID
            }
            segment.copy(speakerId = alignedSpeaker)
        }
        val assignedCounts = alignedSegments
            .asSequence()
            .map { it.speakerId }
            .filter { it != UNKNOWN_SPEAKER_ID }
            .groupingBy { it }
            .eachCount()
        val diagnostics = TranscriptAlignmentDiagnostics(
            asrSegmentCount = segments.size,
            speakerTurnCount = turns.size,
            segmentsOverlappingMultipleSpeakers = multiSpeakerSegmentCount,
            segmentsOverlappingUnknownTurns = unknownTurnOverlapCount,
            unknownSegmentCount = alignedSegments.count {
                it.speakerId == UNKNOWN_SPEAKER_ID
            },
            assignedSegmentsBySpeaker = assignedCounts
        )
        return TranscriptAlignmentResult(alignedSegments, diagnostics)
    }

    private fun unionLength(ranges: List<SampleRange>): Long {
        val ordered = ranges.sortedBy { it.startSample }
        var mergedStart = ordered.first().startSample
        var mergedEnd = ordered.first().endSample
        var total = 0L
        ordered.drop(1).forEach { range ->
            if (range.startSample <= mergedEnd) {
                mergedEnd = maxOf(mergedEnd, range.endSample)
            } else {
                total += mergedEnd - mergedStart
                mergedStart = range.startSample
                mergedEnd = range.endSample
            }
        }
        return total + mergedEnd - mergedStart
    }

    private data class SampleRange(
        val startSample: Long,
        val endSample: Long
    )
}
