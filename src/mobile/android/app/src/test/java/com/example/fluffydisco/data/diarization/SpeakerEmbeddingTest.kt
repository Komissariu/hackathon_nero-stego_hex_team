package com.example.fluffydisco.data.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeakerEmbeddingTest {

    @Test
    fun cosineSimilarityIsNormalizedAndScaleInvariant() {
        val embedding = SpeakerEmbedding.from(floatArrayOf(3f, 4f))
        val scaledEmbedding = SpeakerEmbedding.from(floatArrayOf(6f, 8f))
        val orthogonalEmbedding = SpeakerEmbedding.from(floatArrayOf(-4f, 3f))

        assertEquals(1f, embedding.cosineSimilarity(embedding), 1e-6f)
        assertEquals(1f, embedding.cosineSimilarity(scaledEmbedding), 1e-6f)
        assertEquals(0f, embedding.cosineSimilarity(orthogonalEmbedding), 1e-6f)
        assertEquals(1f, embedding.cosineDistance(orthogonalEmbedding), 1e-6f)
        assertEquals(
            2f, embedding.cosineDistance(
                SpeakerEmbedding.from(floatArrayOf(-3f, -4f))
            ), 1e-6f
        )
    }

    @Test
    fun embeddingCopiesCallerArray() {
        val values = floatArrayOf(3f, 4f)
        val embedding = SpeakerEmbedding.from(values)
        values[0] = 0f
        values[1] = 0f

        assertEquals(
            1f,
            embedding.cosineSimilarity(SpeakerEmbedding.from(floatArrayOf(3f, 4f))),
            1e-6f
        )
    }

    @Test
    fun rejectsInvalidVectors() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeakerEmbedding.from(floatArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeakerEmbedding.from(floatArrayOf(0f, 0f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeakerEmbedding.from(floatArrayOf(1f, Float.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeakerEmbedding.from(floatArrayOf(Float.POSITIVE_INFINITY))
        }
    }

    @Test
    fun rejectsComparisonsBetweenDifferentDimensions() {
        val twoDimensional = SpeakerEmbedding.from(floatArrayOf(1f, 0f))
        val threeDimensional = SpeakerEmbedding.from(floatArrayOf(1f, 0f, 0f))

        assertThrows(IllegalArgumentException::class.java) {
            twoDimensional.cosineSimilarity(threeDimensional)
        }
    }
}
