package com.example.fluffydisco.data.diarization

import kotlin.math.sqrt

/**
 * An immutable, L2-normalized speaker embedding.
 *
 * This value type deliberately contains no similarity threshold or clustering policy;
 * those require calibration against labeled speech data.
 */
class SpeakerEmbedding private constructor(
    private val unitVector: FloatArray
) {
    val dimensions: Int
        get() = unitVector.size

    fun cosineSimilarity(other: SpeakerEmbedding): Float {
        require(dimensions == other.dimensions) {
            "Speaker embeddings must have the same dimension"
        }

        var dotProduct = 0.0
        for (index in unitVector.indices) {
            dotProduct += unitVector[index].toDouble() *
                    other.unitVector[index].toDouble()
        }
        return dotProduct.coerceIn(-1.0, 1.0).toFloat()
    }

    fun cosineDistance(other: SpeakerEmbedding): Float =
        1f - cosineSimilarity(other)

    companion object {
        fun from(values: FloatArray): SpeakerEmbedding {
            require(values.isNotEmpty()) {
                "Speaker embedding must not be empty"
            }

            var squaredNorm = 0.0
            for (value in values) {
                require(value.isFinite()) {
                    "Speaker embedding values must be finite"
                }
                squaredNorm += value.toDouble() * value.toDouble()
            }
            require(squaredNorm > 0.0 && squaredNorm.isFinite()) {
                "Speaker embedding must have a finite, non-zero norm"
            }

            val inverseNorm = 1.0 / sqrt(squaredNorm)
            val normalized = FloatArray(values.size) { index ->
                (values[index].toDouble() * inverseNorm).toFloat()
            }
            return SpeakerEmbedding(normalized)
        }
    }
}
