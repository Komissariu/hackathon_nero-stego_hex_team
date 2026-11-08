package com.example.fluffydisco.data.diarization

/**
 * Builds non-overlapping, bounded ASR ranges from globally clustered speaker turns.
 * All time coordinates are absolute PCM sample indices.
 * Speech with no reliable speaker (or overlapping contradictory labels) is retained
 * as speaker_unknown, never silently assigned to a neighbour.
 */
class SpeakerTurnPlanner(private val maxRangeSamples: Long = 28L * 16_000) {
    init {
        require(maxRangeSamples > 0L)
    }

    fun plan(
        speechIntervals: List<SpeechInterval>,
        speakerTurns: List<SpeakerTurn>,
        totalSamples: Long
    ): List<SpeakerTurn> {
        require(totalSamples >= 0L)
        val boundedSpeech = speechIntervals.mapNotNull { interval ->
            val start = interval.startSample.coerceAtMost(totalSamples)
            val end = interval.endSample.coerceAtMost(totalSamples)
            if (end > start) SpeechInterval(start, end) else null
        }.sortedBy { it.startSample }
        val speechUnion = ArrayList<SpeechInterval>()
        boundedSpeech.forEach { interval ->
            val previous = speechUnion.lastOrNull()
            if (previous != null && interval.startSample <= previous.endSample) {
                speechUnion[speechUnion.lastIndex] = SpeechInterval(
                    previous.startSample, maxOf(previous.endSample, interval.endSample)
                )
            } else {
                speechUnion += interval
            }
        }

        val pieces = ArrayList<SpeakerTurn>()
        for (speech in speechUnion) {
            val boundaries = ArrayList<Long>()
            boundaries += speech.startSample
            boundaries += speech.endSample
            speakerTurns.forEach { turn ->
                val start = maxOf(speech.startSample, turn.interval.startSample)
                val end = minOf(speech.endSample, turn.interval.endSample)
                if (end > start) {
                    boundaries += start
                    boundaries += end
                }
            }
            val ordered = boundaries.distinct().sorted()
            for (i in 0 until ordered.size - 1) {
                val start = ordered[i]
                val end = ordered[i + 1]
                if (start >= end) continue
                val active = speakerTurns.filter { turn ->
                    turn.interval.startSample < end && turn.interval.endSample > start
                }
                val labels = active.map { it.speakerId }.distinct()
                val singleSpeaker = labels.singleOrNull()
                val speaker = singleSpeaker ?: UNKNOWN_SPEAKER_ID
                val confidence = if (singleSpeaker != null) {
                    active.minOf { it.confidence }
                } else 0f
                val part = SpeakerTurn(SpeechInterval(start, end), speaker, confidence)
                val previous = pieces.lastOrNull()
                if (previous != null && previous.speakerId == part.speakerId &&
                    previous.interval.endSample == part.interval.startSample
                ) {
                    pieces[pieces.lastIndex] = previous.copy(
                        interval = SpeechInterval(previous.interval.startSample, end),
                        confidence = minOf(previous.confidence, part.confidence)
                    )
                } else {
                    pieces += part
                }
            }
        }
        val ranges = ArrayList<SpeakerTurn>()
        pieces.forEach { piece ->
            var start = piece.interval.startSample
            while (start < piece.interval.endSample) {
                val end = minOf(piece.interval.endSample, start + maxRangeSamples)
                ranges += piece.copy(interval = SpeechInterval(start, end))
                start = end
            }
        }
        return ranges
    }
}
