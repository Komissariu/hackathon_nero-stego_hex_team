package com.example.fluffydisco.data.diarization

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeakerEmbeddingOnnxEngine(
    modelFile: File,
    intraOpThreads: Int = 1
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        require(modelFile.isFile) { "Speaker embedding model is missing: $modelFile" }
        require(File(modelFile.absolutePath + EXTERNAL_DATA_SUFFIX).isFile) {
            "Speaker embedding external data is missing: ${modelFile.absolutePath}$EXTERNAL_DATA_SUFFIX"
        }
        require(intraOpThreads >= 1)
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
        }
        session = options.use {
            environment.createSession(modelFile.absolutePath, it)
        }
        require(session.inputNames.contains(INPUT_WAVEFORM)) {
            "Speaker embedding graph is missing input '$INPUT_WAVEFORM'"
        }
        require(session.outputNames.contains(OUTPUT_EMBEDDING)) {
            "Speaker embedding graph is missing output '$OUTPUT_EMBEDDING'"
        }
    }

    suspend fun embed(
        wav: Pcm16WavFile,
        speechIntervals: List<SpeechInterval>
    ): List<SpeakerObservation> {
        require(wav.sampleRate == SAMPLE_RATE) {
            "Speaker embedding requires $SAMPLE_RATE Hz audio"
        }
        val observations = ArrayList<SpeakerObservation>()
        speechIntervals.forEach { interval ->
            currentCoroutineContext().ensureActive()
            require(interval.endSample <= wav.sampleCount) {
                "Speech interval exceeds WAV sample count"
            }
            val speechSamples = interval.durationSamples
            if (speechSamples < MIN_EMBEDDABLE_SAMPLES) {
                observations += SpeakerObservation(interval, null, 0f)
                return@forEach
            }
            windows(interval).forEach { (windowStart, windowEnd, voicedSamples) ->
                currentCoroutineContext().ensureActive()
                val samples = FloatArray(WINDOW_SAMPLES)
                val actualSamples = (windowEnd - windowStart).toInt()
                val destinationOffset = if (speechSamples < WINDOW_SAMPLES) {
                    (WINDOW_SAMPLES - actualSamples) / 2
                } else {
                    0
                }
                wav.readNormalized(
                    startSample = windowStart,
                    sampleCount = actualSamples,
                    destination = samples,
                    destinationOffset = destinationOffset
                )
                val embedding = infer(samples)
                observations += SpeakerObservation(
                    interval = SpeechInterval(windowStart, windowEnd),
                    embedding = embedding,
                    confidence = (voicedSamples.toFloat() / WINDOW_SAMPLES)
                        .coerceIn(0f, 1f)
                )
            }
        }
        return observations
    }

    private fun windows(
        interval: SpeechInterval
    ): List<Window> {
        if (interval.durationSamples <= WINDOW_SAMPLES) {
            return listOf(
                Window(
                    interval.startSample,
                    interval.endSample,
                    interval.durationSamples
                )
            )
        }

        val result = ArrayList<Window>()
        var start = interval.startSample
        val lastStart = interval.endSample - WINDOW_SAMPLES
        while (start <= lastStart) {
            result += Window(start, start + WINDOW_SAMPLES, WINDOW_SAMPLES.toLong())
            start += WINDOW_SHIFT_SAMPLES
        }
        if (result.last().startSample != lastStart) {
            result += Window(lastStart, interval.endSample, WINDOW_SAMPLES.toLong())
        }
        return result
    }

    private fun infer(samples: FloatArray): SpeakerEmbedding {
        val inputBuffer = ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        inputBuffer.put(samples)
        inputBuffer.rewind()
        val input = OnnxTensor.createTensor(
            environment,
            inputBuffer,
            longArrayOf(1, WINDOW_SAMPLES.toLong())
        )
        return input.use { waveform ->
            session.run(mapOf(INPUT_WAVEFORM to waveform)).use { result ->
                val tensor = result.get(OUTPUT_EMBEDDING)
                    .orElseThrow {
                        IllegalStateException("Speaker embedding graph omitted its output")
                    } as? OnnxTensor
                    ?: error("Speaker embedding graph returned a non-tensor output")
                val info = tensor.info
                require(info.shape.contentEquals(OUTPUT_SHAPE)) {
                    "Speaker embedding graph returned shape ${info.shape.contentToString()}"
                }
                val buffer = tensor.floatBuffer
                require(buffer.remaining() == EMBEDDING_DIMENSIONS) {
                    "Speaker embedding graph returned an unexpected embedding size"
                }
                val values = FloatArray(EMBEDDING_DIMENSIONS)
                buffer.get(values)
                SpeakerEmbedding.from(values)
            }
        }
    }

    override fun close() {
        session.close()
    }

    private data class Window(
        val startSample: Long,
        val endSample: Long,
        val voicedSamples: Long
    )

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SECONDS = 3
        const val WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_SECONDS
        const val WINDOW_SHIFT_SAMPLES = WINDOW_SAMPLES / 2
        const val MIN_EMBEDDABLE_SAMPLES = SAMPLE_RATE * 8 / 10
        const val EMBEDDING_DIMENSIONS = 192
        const val INPUT_WAVEFORM = "waveform"
        const val OUTPUT_EMBEDDING = "embedding"
        const val EXTERNAL_DATA_SUFFIX = ".data"
        val OUTPUT_SHAPE = longArrayOf(1, 1, EMBEDDING_DIMENSIONS.toLong())
    }
}
