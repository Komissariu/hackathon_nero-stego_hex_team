package com.example.fluffydisco.data.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WhisperFeatureExtractorTest {

    @Test
    fun extractsFiniteWhisperFeaturesFromSilence() {
        val features = WhisperFeatureExtractor.extract(FloatArray(16_000 * 30))

        assertEquals(80 * 3000, features.size)
        assertTrue(features.all { it.isFinite() })
        assertTrue(features.all { kotlin.math.abs(it + 1.5f) < 0.00001f })
    }

    @Test
    fun rejectsAudioThatIsNotPaddedToThirtySeconds() {
        try {
            WhisperFeatureExtractor.extract(FloatArray(16_000))
            throw AssertionError("Expected invalid chunk size to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: ONNX input has a fixed 30-second feature shape.
        }
    }

    @Test
    fun matchesWhisperLogMelReferenceForOneKilohertzTone() {
        val samples = FloatArray(16_000 * 30) { index ->
            (0.5 * sin(2.0 * PI * 1000 * index / 16_000)).toFloat()
        }

        val features = WhisperFeatureExtractor.extract(samples)

        assertEquals(-0.56035f, features[20 * 3000 + 1500], 0.0001f)
        assertEquals(1.43965f, features[26 * 3000 + 1500], 0.0001f)
    }
}
