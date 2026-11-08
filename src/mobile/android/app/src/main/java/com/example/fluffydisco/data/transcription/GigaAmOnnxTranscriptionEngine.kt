package com.example.fluffydisco.data.transcription

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import com.example.fluffydisco.domain.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class GigaAmOnnxTranscriptionEngine(
    context: Context,
    private val modelDirectory: File
) : TranscriptionEngine {

    private val appContext = context.applicationContext

    override suspend fun transcribe(audioUri: Uri): List<TranscriptSegment> =
        withContext(Dispatchers.IO) {
            val decoder = GigaAmCtcDecoder(File(modelDirectory, TOKENS_FILE))
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }

            options.use {
                environment.createSession(
                    File(modelDirectory, MODEL_FILE).absolutePath,
                    it
                ).use { session ->
                    validateModelInputs(session)
                    val output = mutableListOf<TranscriptSegment>()
                    readPcmWav(audioUri) { samples, startSample, sampleCount ->
                        currentCoroutineContext().ensureActive()
                        if (samples.size >= GigaAmFeatureExtractor.WINDOW_SIZE) {
                            val text = transcribeChunk(
                                environment = environment,
                                session = session,
                                decoder = decoder,
                                samples = samples
                            )
                            if (text.isNotBlank()) {
                                output += TranscriptSegment(
                                    startMs = startSample * 1000 /
                                            GigaAmFeatureExtractor.SAMPLE_RATE,
                                    endMs = (startSample + sampleCount) * 1000 /
                                            GigaAmFeatureExtractor.SAMPLE_RATE,
                                    speakerId = SPEAKER_ID,
                                    text = text
                                )
                            }
                        }
                    }
                    output
                }
            }
        }

    private suspend fun transcribeChunk(
        environment: OrtEnvironment,
        session: OrtSession,
        decoder: GigaAmCtcDecoder,
        samples: FloatArray
    ): String {
        val coroutineContext = currentCoroutineContext()
        val features = GigaAmFeatureExtractor().extract(samples) {
            coroutineContext.ensureActive()
        }
        val frameCount = features.size / GigaAmFeatureExtractor.MEL_BINS
        check(
            frameCount > 0 && features.size ==
                    GigaAmFeatureExtractor.MEL_BINS * frameCount
        ) { "GigaAM feature extraction returned an invalid shape" }

        val featureTensor = floatTensor(
            environment,
            features,
            longArrayOf(
                1,
                GigaAmFeatureExtractor.MEL_BINS.toLong(),
                frameCount.toLong()
            )
        )
        val lengthTensor = longTensor(
            environment,
            frameCount.toLong(),
            longArrayOf(1)
        )

        return featureTensor.use { featureInput ->
            lengthTensor.use { lengthInput ->
                session.run(
                    mapOf(
                        FEATURES_INPUT to featureInput,
                        FEATURE_LENGTHS_INPUT to lengthInput
                    )
                ).use { result ->
                    val value = result.get(LOG_PROBS_OUTPUT).orElse(null)?.value
                        ?: error("GigaAM ONNX graph did not return log probabilities")
                    decoder.decode(readLogProbabilities(value))
                }
            }
        }
    }

    private fun validateModelInputs(session: OrtSession) {
        require(
            FEATURES_INPUT in session.inputNames &&
                    FEATURE_LENGTHS_INPUT in session.inputNames
        ) { "GigaAM ONNX graph is missing required inputs" }
        require(LOG_PROBS_OUTPUT in session.outputNames) {
            "GigaAM ONNX graph is missing its log_probs output"
        }
    }

    private fun readLogProbabilities(value: Any): Array<FloatArray> {
        val batch = value as? Array<*>
            ?: error("GigaAM returned an invalid log_probs tensor")
        require(batch.size == 1) { "GigaAM returned an unexpected batch size" }
        val frames = batch[0] as? Array<*>
            ?: error("GigaAM returned an invalid log_probs sequence")
        require(frames.isNotEmpty()) { "GigaAM returned an empty log_probs sequence" }
        return Array(frames.size) { frameIndex ->
            val frame = frames[frameIndex] as? FloatArray
                ?: error("GigaAM returned an invalid log_probs frame")
            require(frame.size == VOCABULARY_SIZE) {
                "GigaAM returned ${frame.size} classes; expected $VOCABULARY_SIZE"
            }
            frame
        }
    }

    private suspend fun readPcmWav(
        uri: Uri,
        onChunk: suspend (FloatArray, Long, Int) -> Unit
    ) {
        val source = appContext.contentResolver.openInputStream(uri)
            ?: error("Cannot open decoded audio")
        DataInputStream(BufferedInputStream(source)).use { input ->
            require(input.readAscii(4) == "RIFF") { "Expected a RIFF WAV file" }
            input.readUInt32()
            require(input.readAscii(4) == "WAVE") { "Expected a WAV file" }

            var format: WavFormat? = null
            while (true) {
                val chunkId = input.readAsciiOrNull(4) ?: break
                val chunkSize = input.readUInt32()
                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize in 16..1024) { "Invalid WAV format chunk" }
                        val bytes = ByteArray(chunkSize.toInt())
                        input.readFully(bytes)
                        val fields = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        format = WavFormat(
                            encoding = fields.short.toInt() and 0xffff,
                            channels = fields.short.toInt() and 0xffff,
                            sampleRate = fields.int,
                            bitsPerSample = ByteBuffer.wrap(bytes, 14, 2)
                                .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
                        )
                    }

                    "data" -> {
                        val wav = format ?: error("WAV format chunk is missing")
                        require(
                            wav.encoding == PCM_ENCODING &&
                                    wav.channels == 1 &&
                                    wav.sampleRate == GigaAmFeatureExtractor.SAMPLE_RATE &&
                                    wav.bitsPerSample == 16
                        ) {
                            "GigaAM requires mono 16 kHz 16-bit PCM audio"
                        }
                        require(chunkSize % 2L == 0L) { "Invalid WAV sample data" }
                        var remainingBytes = chunkSize
                        var sampleOffset = 0L
                        while (remainingBytes > 0) {
                            currentCoroutineContext().ensureActive()
                            val sampleCount = minOf(
                                remainingBytes / 2,
                                MAX_CHUNK_SAMPLES.toLong()
                            ).toInt()
                            val samples = FloatArray(sampleCount)
                            repeat(sampleCount) { index ->
                                samples[index] = input.readUnsignedShortLE().toShort() / 32768f
                            }
                            onChunk(samples, sampleOffset, sampleCount)
                            sampleOffset += sampleCount
                            remainingBytes -= sampleCount * 2L
                        }
                        return
                    }

                    else -> input.skipFully(chunkSize)
                }
                if (chunkSize % 2L != 0L) input.skipFully(1)
            }
            error("WAV audio data chunk is missing")
        }
    }

    private fun floatTensor(
        environment: OrtEnvironment,
        values: FloatArray,
        shape: LongArray
    ): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(values)
        buffer.rewind()
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    private fun longTensor(
        environment: OrtEnvironment,
        value: Long,
        shape: LongArray
    ): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
        buffer.put(value)
        buffer.rewind()
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    private data class WavFormat(
        val encoding: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int
    )

    private companion object {
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val FEATURES_INPUT = "features"
        const val FEATURE_LENGTHS_INPUT = "feature_lengths"
        const val LOG_PROBS_OUTPUT = "log_probs"
        const val PCM_ENCODING = 1
        const val SPEAKER_ID = "speaker_1"
        const val MAX_CHUNK_SAMPLES = 30 * GigaAmFeatureExtractor.SAMPLE_RATE
        const val VOCABULARY_SIZE = 257
    }
}

private fun DataInputStream.readAscii(length: Int): String {
    val bytes = ByteArray(length)
    readFully(bytes)
    return bytes.toString(Charsets.US_ASCII)
}

private fun DataInputStream.readAsciiOrNull(length: Int): String? {
    val first = read()
    if (first < 0) return null
    val bytes = ByteArray(length)
    bytes[0] = first.toByte()
    readFully(bytes, 1, length - 1)
    return bytes.toString(Charsets.US_ASCII)
}

private fun DataInputStream.readUInt32(): Long {
    val bytes = ByteArray(4)
    readFully(bytes)
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
}

private fun DataInputStream.readUnsignedShortLE(): Int {
    val low = readUnsignedByte()
    val high = readUnsignedByte()
    return low or (high shl 8)
}

private fun DataInputStream.skipFully(byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            if (read() < 0) throw java.io.EOFException("Unexpected end of WAV file")
            remaining--
        }
    }
}
