package com.example.fluffydisco.data.transcription

import android.content.Context
import android.net.Uri
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.fluffydisco.domain.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class WhisperOnnxTranscriptionEngine(
    context: Context,
    private val modelDirectory: File
) : TranscriptionEngine {

    private val appContext = context.applicationContext

    override suspend fun transcribe(audioUri: Uri): List<TranscriptSegment> =
        withContext(Dispatchers.IO) {
            val tokenizer = WhisperTokenizer(File(modelDirectory, TOKENIZER_FILE))
            val generation = WhisperGenerationConfig(
                File(modelDirectory, GENERATION_CONFIG_FILE).readText()
            )
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }

            options.use {
                environment.createSession(
                    File(modelDirectory, ENCODER_FILE).absolutePath,
                    it
                ).use { encoder ->
                    environment.createSession(
                        File(modelDirectory, DECODER_FILE).absolutePath,
                        it
                    ).use { decoder ->
                        val output = mutableListOf<TranscriptSegment>()
                        readPcmWav(audioUri) { samples, startSample, sampleCount ->
                            currentCoroutineContext().ensureActive()
                            val text = transcribeChunk(
                                environment = environment,
                                encoder = encoder,
                                decoder = decoder,
                                tokenizer = tokenizer,
                                generation = generation,
                                samples = samples
                            )
                            if (text.isNotBlank()) {
                                output += TranscriptSegment(
                                    startMs = startSample * 1000 / SAMPLE_RATE,
                                    endMs = (startSample + sampleCount) * 1000 / SAMPLE_RATE,
                                    speakerId = "speaker_1",
                                    text = text.trim()
                                )
                            }
                        }
                        output
                    }
                }
            }
        }

    private suspend fun transcribeChunk(
        environment: OrtEnvironment,
        encoder: OrtSession,
        decoder: OrtSession,
        tokenizer: WhisperTokenizer,
        generation: WhisperGenerationConfig,
        samples: FloatArray
    ): String {
        val features = WhisperFeatureExtractor.extract(samples)
        val encoderTensor = floatTensor(
            environment,
            features,
            longArrayOf(1, MEL_BINS.toLong(), FEATURE_FRAMES.toLong())
        )
        val hiddenStates = encoderTensor.use { tensor ->
            encoder.run(mapOf("input_features" to tensor)).use { result ->
                val value = result.get("last_hidden_state").orElse(null)?.value
                    ?: error("Whisper encoder did not return hidden states")

                @Suppress("UNCHECKED_CAST")
                val hidden = value as Array<Array<FloatArray>>
                check(
                    hidden.size == 1 &&
                            hidden[0].size == ENCODER_FRAMES &&
                            hidden[0].all { it.size == HIDDEN_SIZE }
                ) {
                    "Whisper encoder returned an unexpected hidden-state shape"
                }
                val flattened = FloatArray(ENCODER_FRAMES * HIDDEN_SIZE)
                hidden[0].forEachIndexed { index, row ->
                    row.copyInto(flattened, index * row.size)
                }
                flattened
            }
        }

        val tokens = mutableListOf(
            tokenizer.tokenId("<|startoftranscript|>"),
            tokenizer.tokenId("<|ru|>"),
            tokenizer.tokenId("<|transcribe|>"),
            tokenizer.tokenId("<|notimestamps|>")
        )
        val generated = ArrayList<Int>(MAX_NEW_TOKENS)
        val hiddenTensor = floatTensor(
            environment,
            hiddenStates,
            longArrayOf(1, ENCODER_FRAMES.toLong(), HIDDEN_SIZE.toLong())
        )

        hiddenTensor.use { encoderHidden ->
            repeat(MAX_NEW_TOKENS) { step ->
                currentCoroutineContext().ensureActive()
                val inputTensor = longTensor(
                    environment,
                    tokens,
                    longArrayOf(1, tokens.size.toLong())
                )
                val nextToken = inputTensor.use { input ->
                    decoder.run(
                        mapOf(
                            "input_ids" to input,
                            "encoder_hidden_states" to encoderHidden
                        )
                    ).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val value = result.get("logits").orElse(null)?.value
                            ?: error("Whisper decoder did not return logits")

                        @Suppress("UNCHECKED_CAST")
                        val logits = value as Array<Array<FloatArray>>
                        check(
                            logits.size == 1 &&
                                    logits[0].isNotEmpty() &&
                                    logits[0].all { it.size == VOCAB_SIZE }
                        ) {
                            "Whisper decoder returned an unexpected logits shape"
                        }
                        selectToken(
                            logits[0].last(),
                            generation.suppressTokens,
                            if (step == 0) generation.beginSuppressTokens else emptySet()
                        )
                    }
                }
                tokens += nextToken
                generated += nextToken
                if (nextToken == tokenizer.eosTokenId) return tokenizer.decode(generated)
            }
        }
        return tokenizer.decode(generated)
    }

    private fun selectToken(
        logits: FloatArray,
        suppress: Set<Int>,
        beginSuppress: Set<Int>
    ): Int {
        var bestIndex = -1
        var bestScore = Float.NEGATIVE_INFINITY
        logits.forEachIndexed { index, score ->
            if (index !in suppress && index !in beginSuppress && score > bestScore) {
                bestIndex = index
                bestScore = score
            }
        }
        check(bestIndex >= 0 && bestScore.isFinite()) {
            "Whisper decoder did not produce a valid token"
        }
        return bestIndex
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
                                    wav.sampleRate == SAMPLE_RATE &&
                                    wav.bitsPerSample == 16
                        ) {
                            "Whisper requires mono 16 kHz 16-bit PCM audio"
                        }
                        require(chunkSize % 2L == 0L) { "Invalid WAV sample data" }
                        var remaining = chunkSize
                        var sampleOffset = 0L
                        while (remaining > 0) {
                            val count = minOf(remaining / 2, CHUNK_SAMPLES.toLong()).toInt()
                            val samples = FloatArray(CHUNK_SAMPLES)
                            repeat(count) { index ->
                                val sample = input.readUnsignedShortLE().toShort().toInt()
                                samples[index] = sample / 32768f
                            }
                            onChunk(samples, sampleOffset, count)
                            sampleOffset += count
                            remaining -= count * 2L
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
        values: List<Int>,
        shape: LongArray
    ): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(values.size * Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
        values.forEach { buffer.put(it.toLong()) }
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
        const val TOKENIZER_FILE = "tokenizer.json"
        const val GENERATION_CONFIG_FILE = "generation_config.json"
        const val ENCODER_FILE = "encoder_model.onnx"
        const val DECODER_FILE = "decoder_model.onnx"
        const val PCM_ENCODING = 1
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 30 * SAMPLE_RATE
        const val MEL_BINS = 80
        const val FEATURE_FRAMES = 3000
        const val ENCODER_FRAMES = 1500
        const val HIDDEN_SIZE = 384
        const val VOCAB_SIZE = 51_865
        const val MAX_NEW_TOKENS = 128
    }
}

private data class WhisperGenerationConfig(
    val suppressTokens: Set<Int>,
    val beginSuppressTokens: Set<Int>
) {
    constructor(json: String) : this(
        suppressTokens = JSONObject(json).getIntSet("suppress_tokens"),
        beginSuppressTokens = JSONObject(json).getIntSet("begin_suppress_tokens")
    )
}

private class WhisperTokenizer(file: File) {
    private var tokensById: Array<String?>
    private val tokenIds: Map<String, Int>
    private val byteByCodePoint: Map<Int, Int> = createByteDecoder()

    val eosTokenId: Int

    init {
        val root = JSONObject(file.readText())
        val vocabulary = root.getJSONObject("model").getJSONObject("vocab")
        var maximumId = 0
        val vocabIds = HashMap<String, Int>(vocabulary.length())
        val keys = vocabulary.keys()
        while (keys.hasNext()) {
            val token = keys.next()
            val id = vocabulary.getInt(token)
            vocabIds[token] = id
            maximumId = maxOf(maximumId, id)
        }
        tokensById = arrayOfNulls(maximumId + 1)
        vocabIds.forEach { (token, id) -> tokensById[id] = token }

        val addedTokens = root.getJSONArray("added_tokens")
        val specialIds = HashMap<String, Int>(addedTokens.length())
        for (index in 0 until addedTokens.length()) {
            val added = addedTokens.getJSONObject(index)
            val content = added.getString("content")
            val id = added.getInt("id")
            specialIds[content] = id
            if (id >= tokensById.size) tokensById = tokensById.copyOf(id + 1)
            tokensById[id] = content
        }
        tokenIds = specialIds + vocabIds
        eosTokenId = tokenId("<|endoftext|>")
    }

    fun tokenId(content: String): Int =
        tokenIds[content] ?: error("Whisper tokenizer is missing $content")

    fun decode(tokenIds: List<Int>): String {
        val bytes = ByteArrayOutputStream()
        tokenIds.forEach { id ->
            if (id >= SPECIAL_TOKEN_MIN_ID) return@forEach
            val piece = tokensById.getOrNull(id)
                ?: error("Whisper tokenizer has no vocabulary entry for token $id")
            var offset = 0
            while (offset < piece.length) {
                val codePoint = piece.codePointAt(offset)
                val byte = byteByCodePoint[codePoint]
                    ?: error("Whisper tokenizer contains an invalid byte token")
                bytes.write(byte)
                offset += Character.charCount(codePoint)
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun createByteDecoder(): Map<Int, Int> {
        val bytes = ArrayList<Int>(256)
        (33..126).forEach(bytes::add)
        (161..172).forEach(bytes::add)
        (174..255).forEach(bytes::add)
        val codePoints = bytes.toMutableList()
        var extra = 0
        for (value in 0..255) {
            if (value !in bytes) {
                bytes += value
                codePoints += 256 + extra
                extra++
            }
        }
        return codePoints.indices.associate { index ->
            codePoints[index] to bytes[index]
        }
    }

    private companion object {
        const val SPECIAL_TOKEN_MIN_ID = 50_257
    }
}

private fun JSONObject.getIntSet(name: String): Set<Int> {
    val array = optJSONArray(name) ?: return emptySet()
    return buildSet(array.length()) {
        for (index in 0 until array.length()) {
            if (!array.isNull(index)) add(array.getInt(index))
        }
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
