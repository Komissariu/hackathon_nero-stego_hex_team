package com.example.fluffydisco.data.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GigaAmFeatureExtractorTest {

    @Test
    fun matchesOnnxAsrReferenceFeaturesForDeterministicPcm() {
        val samples = FloatArray(8_000) { index ->
            (((index * 37) % 201) - 100) / 32768f
        }
        val actual = GigaAmFeatureExtractor().extract(samples)
        val frameCount = (samples.size - 320) / 160 + 1
        val expectedBytes = checkNotNull(
            javaClass.getResourceAsStream("/gigaam-v3-reference-features.f32")
        ).use { it.readBytes() }
        val expectedBuffer = ByteBuffer.wrap(expectedBytes).order(ByteOrder.LITTLE_ENDIAN)
        val expected = FloatArray(GigaAmFeatureExtractor.MEL_BINS * frameCount) {
            expectedBuffer.float
        }

        assertEquals(expected.size, actual.size)
        actual.indices.forEach { index ->
            assertEquals("Feature $index", expected[index], actual[index], 0.0001f)
        }
    }

    @Test
    fun silenceProducesFiniteClampedLogMelValues() {
        val actual = GigaAmFeatureExtractor().extract(FloatArray(1_600))

        assertEquals(64 * 9, actual.size)
        assertTrue(actual.all { it.isFinite() && kotlin.math.abs(it + 20.723267f) < 0.0001f })
    }
}
