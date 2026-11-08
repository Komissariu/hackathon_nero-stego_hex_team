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

data class SileroVadConfig(
    val speechThreshold: Float = 0.5f,
    val silenceThreshold: Float = 0.35f,
    val minSpeechSamples: Long = 4_000L,
    val minSilenceSamples: Long = 2_400L,
    val mergeGapSamples: Long = 1_600L
) {
    init {
        require(speechThreshold.isFinite() && speechThreshold in 0f..1f)
        require(
            silenceThreshold.isFinite() &&
                    silenceThreshold in 0f..speechThreshold
        )
        require(minSpeechSamples > 0L)
        require(minSilenceSamples > 0L)
        require(mergeGapSamples >= 0L)
    }
}

class SileroVadOnnxEngine(
    modelFile: File,
    private val config: SileroVadConfig = SileroVadConfig(),
    intraOpThreads: Int = 1
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        require(modelFile.isFile) { "Silero VAD model is missing: $modelFile" }
        require(intraOpThreads >= 1)
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
        }
        session = options.use {
            environment.createSession(modelFile.absolutePath, it)
        }
        require(session.inputNames.containsAll(INPUT_NAMES)) {
            "Silero VAD graph has unexpected input names: ${session.inputNames}"
        }
        require(session.outputNames.containsAll(OUTPUT_NAMES)) {
            "Silero VAD graph has unexpected output names: ${session.outputNames}"
        }
    }

    suspend fun detect(wav: Pcm16WavFile): List<SpeechInterval> {
        require(wav.sampleRate == SAMPLE_RATE) {
            "Silero VAD requires $SAMPLE_RATE Hz audio"
        }
        val inputSamples = FloatArray(FRAME_SAMPLES)
        val contextSamples = FloatArray(CONTEXT_SAMPLES)
        var state = FloatArray(STATE_FLOATS)
        val intervals = ArrayList<SpeechInterval>()
        var speechStart: Long? = null
        var lastSpeechEnd = 0L
        var silenceStart: Long? = null
        var frameStart = 0L

        while (frameStart < wav.sampleCount) {
            currentCoroutineContext().ensureActive()
            val count = minOf(FRAME_SAMPLES.toLong(), wav.sampleCount - frameStart).toInt()
            inputSamples.fill(0f)
            wav.readNormalized(frameStart, count, inputSamples)
            val (probability, nextState) = runFrame(inputSamples, state, contextSamples)
            state = nextState
            inputSamples.copyInto(
                contextSamples,
                destinationOffset = 0,
                startIndex = FRAME_SAMPLES - CONTEXT_SAMPLES,
                endIndex = FRAME_SAMPLES
            )
            val frameEnd = frameStart + count

            val isSpeechFrame = if (speechStart == null) {
                probability >= config.speechThreshold
            } else {
                probability >= config.silenceThreshold
            }
            when {
                isSpeechFrame -> {
                    if (speechStart == null) speechStart = frameStart
                    lastSpeechEnd = frameEnd
                    silenceStart = null
                }

                speechStart != null -> {
                    if (silenceStart == null) silenceStart = frameStart
                    if (frameEnd - requireNotNull(silenceStart) >= config.minSilenceSamples) {
                        addInterval(intervals, requireNotNull(speechStart), lastSpeechEnd)
                        speechStart = null
                        silenceStart = null
                    }
                }
            }
            frameStart = frameEnd
        }
        speechStart?.let { addInterval(intervals, it, lastSpeechEnd) }
        return intervals
    }

    private fun runFrame(
        input: FloatArray,
        state: FloatArray,
        context: FloatArray
    ): Pair<Float, FloatArray> {
        val modelInput = FloatArray(MODEL_INPUT_SAMPLES)
        context.copyInto(modelInput)
        input.copyInto(modelInput, destinationOffset = CONTEXT_SAMPLES)
        val inputTensor = floatTensor(modelInput, longArrayOf(1, MODEL_INPUT_SAMPLES.toLong()))
        val stateTensor = floatTensor(state, longArrayOf(2, 1, STATE_SIZE.toLong()))
        val sampleRateTensor = OnnxTensor.createTensor(
            environment,
            ByteBuffer.allocateDirect(Long.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asLongBuffer()
                .put(SAMPLE_RATE.toLong())
                .apply { rewind() },
            longArrayOf()
        )

        return inputTensor.use { audio ->
            stateTensor.use { recurrentState ->
                sampleRateTensor.use { sampleRate ->
                    session.run(
                        mapOf(
                            INPUT_AUDIO to audio,
                            INPUT_STATE to recurrentState,
                            INPUT_SAMPLE_RATE to sampleRate
                        )
                    ).use { result ->
                        val probability = result.get(OUTPUT_PROBABILITY)
                            .orElseThrow { IllegalStateException("Silero VAD omitted output") }
                            .let { it as? OnnxTensor }
                            ?.floatBuffer
                            ?.get()
                            ?: error("Silero VAD returned an invalid probability tensor")
                        val nextStateTensor = result.get(OUTPUT_STATE)
                            .orElseThrow { IllegalStateException("Silero VAD omitted recurrent state") }
                            .let { it as? OnnxTensor }
                            ?: error("Silero VAD returned an invalid recurrent-state tensor")
                        val nextStateBuffer = nextStateTensor.floatBuffer
                        require(nextStateBuffer.remaining() == STATE_FLOATS) {
                            "Silero VAD returned an unexpected recurrent-state shape"
                        }
                        val nextState = FloatArray(STATE_FLOATS)
                        nextStateBuffer.get(nextState)
                        require(probability.isFinite() && probability in 0f..1f) {
                            "Silero VAD returned an invalid probability"
                        }
                        probability to nextState
                    }
                }
            }
        }
    }

    private fun addInterval(
        intervals: MutableList<SpeechInterval>,
        startSample: Long,
        endSample: Long
    ) {
        if (endSample - startSample < config.minSpeechSamples) return
        val previous = intervals.lastOrNull()
        if (previous != null &&
            startSample - previous.endSample <= config.mergeGapSamples
        ) {
            intervals[intervals.lastIndex] = SpeechInterval(
                previous.startSample,
                endSample
            )
        } else {
            intervals += SpeechInterval(startSample, endSample)
        }
    }

    private fun floatTensor(values: FloatArray, shape: LongArray): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(values)
        buffer.rewind()
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    override fun close() {
        session.close()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512
        const val CONTEXT_SAMPLES = 64
        const val MODEL_INPUT_SAMPLES = FRAME_SAMPLES + CONTEXT_SAMPLES
        const val STATE_SIZE = 128
        const val STATE_FLOATS = 2 * STATE_SIZE
        const val INPUT_AUDIO = "input"
        const val INPUT_STATE = "state"
        const val INPUT_SAMPLE_RATE = "sr"
        const val OUTPUT_PROBABILITY = "output"
        const val OUTPUT_STATE = "stateN"
        val INPUT_NAMES = setOf(INPUT_AUDIO, INPUT_STATE, INPUT_SAMPLE_RATE)
        val OUTPUT_NAMES = setOf(OUTPUT_PROBABILITY, OUTPUT_STATE)
    }
}
