package com.example.fluffydisco.data.transcription

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fluffydisco.data.transcription.models.ModelCatalog
import com.example.fluffydisco.data.transcription.models.ModelRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GigaAmOnnxInstrumentedTest {

    @Test
    fun installedBundledModelRunsOfflineOnRussianPcm() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val audioFile = copyReferenceAudio()

        val repository = ModelRepository(appContext)
        val installed = repository.installBundledModel(ModelCatalog.GIGAAM_ID)
        val engine = GigaAmOnnxTranscriptionEngine(appContext, installed)
        val heap = Runtime.getRuntime()
        val heapBefore = heap.totalMemory() - heap.freeMemory()
        val startMs = android.os.SystemClock.elapsedRealtime()

        val segments = engine.transcribe(Uri.fromFile(audioFile))

        val elapsedMs = android.os.SystemClock.elapsedRealtime() - startMs
        val heapAfter = heap.totalMemory() - heap.freeMemory()
        val transcript = segments.joinToString(" ") { it.text }
        android.util.Log.i(
            LOG_TAG,
            "GigaAM inference elapsedMs=$elapsedMs heapBefore=$heapBefore " +
                    "heapAfter=$heapAfter transcript=$transcript"
        )
        assertTrue("GigaAM returned no transcript segments", segments.isNotEmpty())
        assertTrue(
            "GigaAM transcript did not contain reference speech",
            transcript.contains("Ничьих")
        )
        assertTrue(
            "GigaAM transcript did not contain later speech",
            transcript.contains("лукоморья")
        )
        assertTrue("GigaAM transcript contains invalid segment timing", segments.all {
            it.startMs >= 0 && it.endMs > it.startMs
        })
    }

    @Test
    fun whisperStillRunsOfflineOnTheSameRussianPcm() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val audioFile = copyReferenceAudio()
        val repository = ModelRepository(appContext)
        val installed = repository.installBundledModel(ModelCatalog.BASIC_ID)
        val engine = WhisperOnnxTranscriptionEngine(appContext, installed)
        val startMs = android.os.SystemClock.elapsedRealtime()

        val segments = engine.transcribe(Uri.fromFile(audioFile))

        val elapsedMs = android.os.SystemClock.elapsedRealtime() - startMs
        val transcript = segments.joinToString(" ") { it.text }
        android.util.Log.i(
            LOG_TAG,
            "Whisper inference elapsedMs=$elapsedMs transcript=$transcript"
        )
        assertTrue("Whisper returned no transcript segments", segments.isNotEmpty())
        assertTrue("Whisper returned an empty transcript", transcript.isNotBlank())
    }

    private fun copyReferenceAudio(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val audioFile = File(instrumentation.targetContext.cacheDir, REFERENCE_AUDIO_FILE)
        instrumentation.context.assets.open(REFERENCE_AUDIO_FILE).use { input ->
            audioFile.outputStream().use { output -> input.copyTo(output) }
        }
        return audioFile
    }

    private companion object {
        const val REFERENCE_AUDIO_FILE = "gigaam-reference.wav"
        const val LOG_TAG = "GigaAmOnnxTest"
    }
}
