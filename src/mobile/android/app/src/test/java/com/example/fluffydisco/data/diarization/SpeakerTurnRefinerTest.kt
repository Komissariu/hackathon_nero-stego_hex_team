package com.example.fluffydisco.data.diarization

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerTurnRefinerTest {

    @Test
    fun snapsSpeakerTransitionToNearbyDetectedChangePoint() {
        val turns = listOf(
            SpeakerTurn(SpeechInterval(0, 110), "speaker_1", 0.8f),
            SpeakerTurn(SpeechInterval(110, 200), "speaker_2", 0.9f)
        )
        val refined = SpeakerTurnRefiner(
            maxBoundaryAdjustmentSamples = 30,
            minTurnSamples = 20
        ).refine(turns, listOf(SpeakerChangePoint(100, 0.7f)))

        assertEquals(100L, refined[0].interval.endSample)
        assertEquals(100L, refined[1].interval.startSample)
        assertEquals("speaker_1", refined[0].speakerId)
        assertEquals("speaker_2", refined[1].speakerId)
    }

    @Test
    fun ignoresDistantChangePointsAndSameSpeakerBoundaries() {
        val turns = listOf(
            SpeakerTurn(SpeechInterval(0, 100), "speaker_1", 0.8f),
            SpeakerTurn(SpeechInterval(100, 200), "speaker_1", 0.9f)
        )

        assertEquals(
            turns,
            SpeakerTurnRefiner(10, 20).refine(
                turns,
                listOf(SpeakerChangePoint(150, 0.8f))
            )
        )
    }
}
