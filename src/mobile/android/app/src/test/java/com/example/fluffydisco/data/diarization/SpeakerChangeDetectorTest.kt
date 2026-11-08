package com.example.fluffydisco.data.diarization

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerChangeDetectorTest {

    @Test
    fun findsAtoBtoAChangePointsFromSmoothedContext() {
        val observations = listOf(
            item(0, floatArrayOf(1f, 0f)),
            item(100, floatArrayOf(1f, 0f)),
            item(200, floatArrayOf(0f, 1f)),
            item(300, floatArrayOf(0f, 1f)),
            item(400, floatArrayOf(1f, 0f)),
            item(500, floatArrayOf(1f, 0f))
        )

        val changes = SpeakerChangeDetector(
            SpeakerChangeConfig(
                minMeanCosineDistance = 0.5f,
                contextWindows = 1,
                minTurnSamples = 100
            )
        ).detect(observations)

        assertEquals(listOf(200L, 400L), changes.map { it.sample })
    }

    @Test
    fun doesNotInventChangesForSimilarEmbeddingsOrTooFewWindows() {
        val similar = listOf(
            item(0, floatArrayOf(1f, 0f)),
            item(100, floatArrayOf(0.99f, 0.01f)),
            item(200, floatArrayOf(0.98f, 0.02f))
        )
        val detector = SpeakerChangeDetector(
            SpeakerChangeConfig(0.5f, contextWindows = 1, minTurnSamples = 1)
        )

        assertEquals(emptyList<SpeakerChangePoint>(), detector.detect(similar))
        assertEquals(emptyList<SpeakerChangePoint>(), detector.detect(similar.take(1)))
    }

    private fun item(index: Long, values: FloatArray) = SpeakerObservation(
        interval = SpeechInterval(index, index + 100),
        embedding = SpeakerEmbedding.from(values),
        confidence = 1f
    )
}
