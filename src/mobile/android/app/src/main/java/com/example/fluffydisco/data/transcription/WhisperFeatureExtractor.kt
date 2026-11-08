package com.example.fluffydisco.data.transcription

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

internal object WhisperFeatureExtractor {
    private const val SAMPLE_RATE = 16_000
    private const val FFT_SIZE = 400
    private const val CONVOLUTION_SIZE = 1024
    private const val HOP_LENGTH = 160
    private const val MEL_BINS = 80
    private const val FRAME_COUNT = 3000
    private const val MIN_POWER = 1e-10
    private const val LOG_FLOOR = 8f

    private val chirpReal by lazy {
        DoubleArray(FFT_SIZE) { index -> chirp(index).first }
    }
    private val chirpImaginary by lazy {
        DoubleArray(FFT_SIZE) { index -> chirp(index).second }
    }
    private val transformedChirpKernel by lazy { createTransformedChirpKernel() }
    private val melFilterBank by lazy { createMelFilterBank() }
    private val window by lazy {
        FloatArray(FFT_SIZE) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / FFT_SIZE)).toFloat()
        }
    }

    fun extract(samples: FloatArray): FloatArray {
        require(samples.size == SAMPLE_RATE * 30) {
            "Whisper requires a padded 30-second audio chunk"
        }
        val features = FloatArray(MEL_BINS * FRAME_COUNT)
        val real = DoubleArray(CONVOLUTION_SIZE)
        val imaginary = DoubleArray(CONVOLUTION_SIZE)
        val (kernelReal, kernelImaginary) = transformedChirpKernel
        var maximumLogPower = Float.NEGATIVE_INFINITY

        repeat(FRAME_COUNT) { frame ->
            real.fill(0.0)
            imaginary.fill(0.0)
            val frameStart = frame * HOP_LENGTH - FFT_SIZE / 2
            for (index in 0 until FFT_SIZE) {
                val sampleIndex = reflectIndex(frameStart + index, samples.size)
                val value = samples[sampleIndex] * window[index]
                real[index] = value * chirpReal[index]
                imaginary[index] = value * chirpImaginary[index]
            }
            fft(real, imaginary)
            for (index in 0 until CONVOLUTION_SIZE) {
                val productReal = real[index] * kernelReal[index] -
                        imaginary[index] * kernelImaginary[index]
                imaginary[index] = real[index] * kernelImaginary[index] +
                        imaginary[index] * kernelReal[index]
                real[index] = productReal
            }
            fft(real, imaginary, inverse = true)

            for (mel in 0 until MEL_BINS) {
                var power = 0.0
                val weights = melFilterBank[mel]
                for (bin in weights.indices) {
                    if (weights[bin] != 0f) {
                        val outputReal = real[bin] * chirpReal[bin] -
                                imaginary[bin] * chirpImaginary[bin]
                        val outputImaginary = real[bin] * chirpImaginary[bin] +
                                imaginary[bin] * chirpReal[bin]
                        power += (outputReal * outputReal +
                                outputImaginary * outputImaginary) * weights[bin]
                    }
                }
                val logPower = log10(max(power, MIN_POWER)).toFloat()
                features[mel * FRAME_COUNT + frame] = logPower
                maximumLogPower = max(maximumLogPower, logPower)
            }
        }

        val floor = maximumLogPower - LOG_FLOOR
        features.indices.forEach { index ->
            features[index] = (max(features[index], floor) + 4f) / 4f
        }
        return features
    }

    private fun createMelFilterBank(): Array<FloatArray> {
        val minMel = hzToMel(0.0)
        val maxMel = hzToMel(SAMPLE_RATE / 2.0)
        val melPoints = DoubleArray(MEL_BINS + 2) { index ->
            melToHz(minMel + (maxMel - minMel) * index / (MEL_BINS + 1))
        }
        val filters = Array(MEL_BINS) { FloatArray(FFT_SIZE / 2 + 1) }
        for (mel in 0 until MEL_BINS) {
            val lower = melPoints[mel]
            val center = melPoints[mel + 1]
            val upper = melPoints[mel + 2]
            val normalization = 2.0 / (upper - lower)
            for (bin in 0..FFT_SIZE / 2) {
                val frequency = bin.toDouble() * SAMPLE_RATE / FFT_SIZE
                val rising = (frequency - lower) / (center - lower)
                val falling = (upper - frequency) / (upper - center)
                val weight = max(0.0, minOf(rising, falling)) * normalization
                filters[mel][bin] = weight.toFloat()
            }
        }
        return filters
    }

    private fun hzToMel(hz: Double): Double {
        val linearScale = 3.0 / 200.0
        val logThreshold = 1000.0
        val logStep = ln(6.4) / 27.0
        val logScale = 1.0 / logStep
        return if (hz < logThreshold) {
            hz * linearScale
        } else {
            15.0 + ln(hz / logThreshold) * logScale
        }
    }

    private fun melToHz(mel: Double): Double {
        val linearScale = 200.0 / 3.0
        val logThreshold = 1000.0
        val logStep = ln(6.4) / 27.0
        return if (mel < 15.0) {
            mel * linearScale
        } else {
            logThreshold * exp(logStep * (mel - 15.0))
        }
    }

    private fun reflectIndex(index: Int, sampleCount: Int): Int = when {
        index < 0 -> -index
        index >= sampleCount -> 2 * sampleCount - index - 2
        else -> index
    }

    private fun createTransformedChirpKernel(): Pair<DoubleArray, DoubleArray> {
        val real = DoubleArray(CONVOLUTION_SIZE)
        val imaginary = DoubleArray(CONVOLUTION_SIZE)
        for (index in 0 until FFT_SIZE) {
            val angle = PI * index * index / FFT_SIZE
            val realValue = cos(angle)
            val imaginaryValue = sin(angle)
            real[index] = realValue
            imaginary[index] = imaginaryValue
            if (index > 0) {
                real[CONVOLUTION_SIZE - index] = realValue
                imaginary[CONVOLUTION_SIZE - index] = imaginaryValue
            }
        }
        fft(real, imaginary)
        return real to imaginary
    }

    private fun chirp(index: Int): Pair<Double, Double> {
        val angle = PI * index * index / FFT_SIZE
        return cos(angle) to -sin(angle)
    }

    private fun fft(
        real: DoubleArray,
        imaginary: DoubleArray,
        inverse: Boolean = false
    ) {
        val size = real.size
        var reversed = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            if (index < reversed) {
                val realValue = real[index]
                real[index] = real[reversed]
                real[reversed] = realValue
                val imaginaryValue = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = imaginaryValue
            }
        }

        var length = 2
        while (length <= size) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / length
            val rootReal = cos(angle)
            val rootImaginary = sin(angle)
            for (block in 0 until size step length) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val even = block + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * twiddleReal -
                            imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary +
                            imaginary[odd] * twiddleReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextTwiddleReal = twiddleReal * rootReal -
                            twiddleImaginary * rootImaginary
                    twiddleImaginary = twiddleReal * rootImaginary +
                            twiddleImaginary * rootReal
                    twiddleReal = nextTwiddleReal
                }
            }
            length = length shl 1
        }
        if (inverse) {
            real.indices.forEach { index ->
                real[index] /= size
                imaginary[index] /= size
            }
        }
    }
}
