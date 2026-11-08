package com.example.fluffydisco.data.transcription

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal class GigaAmFeatureExtractor {

    fun extract(
        samples: FloatArray,
        ensureActive: () -> Unit = {}
    ): FloatArray {
        require(samples.all { it.isFinite() }) { "Audio samples must be finite" }
        if (samples.size < WINDOW_SIZE) return FloatArray(0)

        val frameCount = (samples.size - WINDOW_SIZE) / HOP_SIZE + 1
        val features = FloatArray(MEL_BINS * frameCount)
        val spectrum = FloatArray(SPECTRUM_BINS)

        repeat(frameCount) { frame ->
            if (frame % CANCELLATION_CHECK_INTERVAL == 0) ensureActive()
            val frameOffset = frame * HOP_SIZE

            for (bin in 0 until SPECTRUM_BINS) {
                var real = 0.0
                var imaginary = 0.0
                val cosine = cosines[bin]
                val sine = sines[bin]
                for (sample in 0 until WINDOW_SIZE) {
                    val value = samples[frameOffset + sample].toDouble() * window[sample]
                    real += value * cosine[sample]
                    imaginary -= value * sine[sample]
                }
                val magnitude = sqrt(real * real + imaginary * imaginary).toFloat()
                spectrum[bin] = magnitude * magnitude
            }

            for (mel in 0 until MEL_BINS) {
                var energy = 0f
                for (bin in 0 until SPECTRUM_BINS) {
                    energy += spectrum[bin] * melFilters[mel][bin]
                }
                val boundedEnergy = energy.coerceIn(MIN_MEL_ENERGY, MAX_MEL_ENERGY)
                features[mel * frameCount + frame] = ln(boundedEnergy)
            }
        }
        ensureActive()
        return features
    }

    companion object {
        const val MEL_BINS = 64
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 320
        const val HOP_SIZE = 160
        const val SPECTRUM_BINS = WINDOW_SIZE / 2 + 1
        private const val CANCELLATION_CHECK_INTERVAL = 16
        private const val MIN_MEL_ENERGY = 1e-9f
        private const val MAX_MEL_ENERGY = 1e9f

        private val window = FloatArray(WINDOW_SIZE) { index ->
            roundToBFloat16(
                (0.5 - 0.5 * cos(2.0 * PI * index / WINDOW_SIZE)).toFloat()
            )
        }
        private val cosines = Array(SPECTRUM_BINS) { bin ->
            DoubleArray(WINDOW_SIZE) { sample ->
                cos(2.0 * PI * bin * sample / WINDOW_SIZE)
            }
        }
        private val sines = Array(SPECTRUM_BINS) { bin ->
            DoubleArray(WINDOW_SIZE) { sample ->
                sin(2.0 * PI * bin * sample / WINDOW_SIZE)
            }
        }
        private val melFilters = createMelFilters()

        private fun createMelFilters(): Array<FloatArray> {
            val melMinimum = hzToMel(0f)
            val melMaximum = hzToMel(SAMPLE_RATE / 2f)
            val melPoints = FloatArray(MEL_BINS + 2) { index ->
                melMinimum + (melMaximum - melMinimum) * index / (MEL_BINS + 1)
            }
            val frequencies = FloatArray(MEL_BINS + 2) { index ->
                melToHz(melPoints[index])
            }
            val filters = Array(MEL_BINS) { FloatArray(SPECTRUM_BINS) }

            for (mel in 0 until MEL_BINS) {
                val lower = frequencies[mel]
                val center = frequencies[mel + 1]
                val upper = frequencies[mel + 2]
                for (bin in 0 until SPECTRUM_BINS) {
                    val frequency = SAMPLE_RATE.toFloat() * bin / WINDOW_SIZE
                    val lowerSlope = (frequency - lower) / (center - lower)
                    val upperSlope = (upper - frequency) / (upper - center)
                    filters[mel][bin] = roundToBFloat16(
                        minOf(lowerSlope, upperSlope).coerceAtLeast(0f)
                    )
                }
            }
            return filters
        }

        private fun hzToMel(hertz: Float): Float =
            (2595.0 * kotlin.math.log10(1.0 + hertz / 700.0)).toFloat()

        private fun melToHz(mel: Float): Float =
            (700.0 * 10.0.pow(mel / 2595.0) - 700.0).toFloat()

        private fun roundToBFloat16(value: Float): Float {
            val bits = java.lang.Float.floatToRawIntBits(value)
            val roundedBits = bits + 0x7fff + ((bits ushr 16) and 1)
            return java.lang.Float.intBitsToFloat(roundedBits and -0x10000)
        }
    }
}
