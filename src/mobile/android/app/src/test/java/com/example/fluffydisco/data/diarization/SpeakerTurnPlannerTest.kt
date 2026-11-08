package com.example.fluffydisco.data.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerTurnPlannerTest {
    @Test
    fun separatesAtoBtoAWithinOneThirtySecondAsrWindow() {
        val sampleRate = 16_000L
        val turns = listOf(
            turn(0 * sampleRate, 8 * sampleRate, "speaker_1"),
            turn(8 * sampleRate, 15 * sampleRate, "speaker_2"),
            turn(15 * sampleRate, 30 * sampleRate, "speaker_1")
        )
        val planned = SpeakerTurnPlanner().plan(
            listOf(SpeechInterval(0, 30 * sampleRate)), turns, 30 * sampleRate
        )
        assertEquals(
            listOf("speaker_1", "speaker_2", "speaker_1"),
            planned.map { it.speakerId })
        assertEquals(8 * sampleRate, planned[1].interval.startSample)
        assertEquals(30 * sampleRate, planned.last().interval.endSample)
    }

    @Test
    fun keepsIdentityAcrossTheThirtySecondBoundary() {
        val rate = 16_000L
        val turns = listOf(turn(27 * rate, 34 * rate, "speaker_2"))
        val ranges = SpeakerTurnPlanner().plan(
            listOf(SpeechInterval(27 * rate, 34 * rate)), turns, 60 * rate
        )
        assertEquals(1, ranges.size)
        assertEquals("speaker_2", ranges[0].speakerId)
        assertEquals(27 * rate, ranges[0].interval.startSample)
        assertEquals(34 * rate, ranges[0].interval.endSample)
    }

    @Test
    fun preservesUncoveredSpeechAndDoesNotGuessOverlappingVoices() {
        val ranges = SpeakerTurnPlanner().plan(
            listOf(SpeechInterval(0, 100)),
            listOf(
                turn(20, 60, "speaker_1"),
                turn(50, 80, "speaker_2")
            ),
            100
        )
        assertEquals(
            listOf(
                UNKNOWN_SPEAKER_ID, "speaker_1", UNKNOWN_SPEAKER_ID,
                "speaker_2", UNKNOWN_SPEAKER_ID
            ), ranges.map { it.speakerId })
        assertEquals(
            listOf(0L, 20L, 50L, 60L, 80L),
            ranges.map { it.interval.startSample })
        assertEquals(100L, ranges.last().interval.endSample)
    }

    @Test
    fun splitsLongSingleSpeakerTurnWithoutChangingIdentity() {
        val rate = 16_000L
        val length = 63 * rate
        val ranges = SpeakerTurnPlanner(28 * rate).plan(
            listOf(SpeechInterval(0, length)),
            listOf(turn(0, length, "speaker_1")),
            length
        )
        assertEquals(
            listOf(28 * rate, 28 * rate, 7 * rate),
            ranges.map { it.interval.durationSamples })
        assertTrue(ranges.all { it.speakerId == "speaker_1" })
    }

    private fun turn(start: Long, end: Long, speaker: String) =
        SpeakerTurn(SpeechInterval(start, end), speaker, 1f)
}
