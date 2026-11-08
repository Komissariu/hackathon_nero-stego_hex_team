package com.example.fluffydisco.data.diarization

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class SileroVadOnnxInstrumentedTest {

    @Test
    fun bundledSileroRunsAndResetsRecurrentStateForEachFile(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.cacheDir, MODEL_FILE)
        context.assets.open("models/diarization/$MODEL_FILE").use { input ->
            model.outputStream().use { output -> input.copyTo(output) }
        }
        val silenceA = createSilenceWav(File(context.cacheDir, "silero-silence-a.wav"))
        val silenceB = createSilenceWav(File(context.cacheDir, "silero-silence-b.wav"))
        try {
            SileroVadOnnxEngine(model).use { vad ->
                val first = Pcm16WavFile.open(silenceA).use { vad.detect(it) }
                val second = Pcm16WavFile.open(silenceB).use { vad.detect(it) }
                assertEquals(emptyList<SpeechInterval>(), first)
                assertEquals(first, second)
            }
        } finally {
            model.delete()
            silenceA.delete()
            silenceB.delete()
        }
    }

    private fun createSilenceWav(file: File): File {
        val sampleCount = 16_000
        val dataBytes = sampleCount * 2
        val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray(Charsets.US_ASCII))
        wav.putInt(36 + dataBytes)
        wav.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        wav.putInt(16)
        wav.putShort(1)
        wav.putShort(1)
        wav.putInt(16_000)
        wav.putInt(32_000)
        wav.putShort(2)
        wav.putShort(16)
        wav.put("data".toByteArray(Charsets.US_ASCII))
        wav.putInt(dataBytes)
        repeat(sampleCount) { wav.putShort(0) }
        file.writeBytes(wav.array())
        return file
    }

    private companion object {
        const val MODEL_FILE = "silero_vad_16k_op15.onnx"
    }
}
