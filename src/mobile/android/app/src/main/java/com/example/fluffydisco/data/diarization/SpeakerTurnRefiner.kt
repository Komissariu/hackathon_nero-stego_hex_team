package com.example.fluffydisco.data.diarization

class SpeakerTurnRefiner(
    private val maxBoundaryAdjustmentSamples: Long,
    private val minTurnSamples: Long
) {
    init {
        require(maxBoundaryAdjustmentSamples >= 0L)
        require(minTurnSamples > 0L)
    }

    fun refine(
        turns: List<SpeakerTurn>,
        changePoints: List<SpeakerChangePoint>
    ): List<SpeakerTurn> {
        if (turns.size < 2 || changePoints.isEmpty()) return turns
        val ordered = turns.sortedBy { it.interval.startSample }
        val points = changePoints.sortedBy { it.sample }
        val refined = ordered.toMutableList()

        for (index in 0 until ordered.lastIndex) {
            val left = refined[index]
            val right = refined[index + 1]
            if (left.speakerId == right.speakerId) continue

            val boundary = (left.interval.endSample + right.interval.startSample) / 2
            val point = points.minByOrNull { kotlin.math.abs(it.sample - boundary) }
                ?: continue
            if (kotlin.math.abs(point.sample - boundary) >
                maxBoundaryAdjustmentSamples
            ) {
                continue
            }
            if (point.sample - left.interval.startSample < minTurnSamples ||
                right.interval.endSample - point.sample < minTurnSamples
            ) {
                continue
            }
            refined[index] = left.copy(
                interval = SpeechInterval(left.interval.startSample, point.sample)
            )
            refined[index + 1] = right.copy(
                interval = SpeechInterval(point.sample, right.interval.endSample)
            )
        }
        return refined
    }
}
