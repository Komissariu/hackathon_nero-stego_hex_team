package com.example.fluffydisco.data.diarization

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class EcapaDiagnosticInstrumentedTest {

    @Test
    fun stagedEcapaLoadsAndEmbedsRealReferenceAudio(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val models = DiarizationModelFiles.prepare(context)
        assumeTrue(
            "Package or stage the inspected ECAPA graph and sidecar",
            models.ecapaGraph.isFile && models.ecapaExternalData.isFile
        )
        assertNull(models.validationProblem())

        val audioFile = copyTestAsset(REFERENCE_AUDIO_FILE)
        try {
            val diarization = OfflineDiarizationProcessor(
                vadModel = models.sileroVad,
                speakerEmbeddingModel = models.ecapaGraph,
                config = initialDiarizationConfig()
            ).process(audioFile)

            val intervals = listOf(0L, 24_000L, 48_000L, 72_000L, 96_000L, 120_000L)
                .map { start -> SpeechInterval(start, start + WINDOW_SAMPLES) }
            val pcmDiagnostics = Pcm16WavFile.open(audioFile).use { wav ->
                assertEquals(SAMPLE_RATE, wav.sampleRate)
                assertEquals(180_640L, wav.sampleCount)
                val samples = FloatArray(WINDOW_SAMPLES.toInt())
                intervals.map { interval ->
                    wav.readNormalized(interval.startSample, samples.size, samples)
                    var sumSquares = 0.0
                    var peak = 0f
                    samples.forEach { sample ->
                        sumSquares += sample.toDouble() * sample
                        peak = max(peak, kotlin.math.abs(sample))
                    }
                    "rms=%.6f,peak=%.6f".format(
                        sqrt(sumSquares / samples.size),
                        peak
                    )
                }
            }
            val observations = Pcm16WavFile.open(audioFile).use { wav ->
                SpeakerEmbeddingOnnxEngine(models.ecapaGraph).use { engine ->
                    engine.embed(wav, intervals)
                }
            }

            assertEquals(intervals.size, observations.size)
            observations.forEach { observation ->
                assertNotNull(observation.embedding)
                assertEquals(192, requireNotNull(observation.embedding).dimensions)
            }
            val similarities = observations.indices.flatMap { left ->
                (left + 1 until observations.size).map { right ->
                    requireNotNull(observations[left].embedding)
                        .cosineSimilarity(requireNotNull(observations[right].embedding))
                }
            }
            assertTrue(similarities.all(Float::isFinite))

            Log.i(
                LOG_TAG,
                "ecapa=loaded dims=192 wavRate=$SAMPLE_RATE samples=180640 " +
                        "vadIntervals=${diarization.speechIntervals.size} " +
                        "observations=${diarization.observations.size} " +
                        "turns=${diarization.turns.size} manualWindows=${observations.size} " +
                        "pcm=[${pcmDiagnostics.joinToString(";")}] " +
                        "pairCosineMin=${similarities.minOrNull()} " +
                        "pairCosineMax=${similarities.maxOrNull()}"
            )
        } finally {
            audioFile.delete()
        }
    }

    @Test
    fun contextAwareVadAndEcapaSeparateKnownPublicSpeakerSamples(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = DiarizationModelFiles.prepare(context)
        val diagnosticDirectory = File(context.cacheDir, DIAGNOSTIC_DIRECTORY)
        val speakerAFile = File(diagnosticDirectory, SPEAKER_A_FILE)
        val speakerBFile = File(diagnosticDirectory, SPEAKER_B_FILE)
        assumeTrue(
            "Stage verified ECAPA files and the two public labeled test clips",
            models.ecapaGraph.isFile &&
                    models.ecapaExternalData.isFile &&
                    speakerAFile.isFile &&
                    speakerBFile.isFile
        )
        assertNull(models.validationProblem())

        val audioFiles = listOf(speakerAFile, speakerBFile)
        val vadIntervals = SileroVadOnnxEngine(models.sileroVad).use { vad ->
            audioFiles.map { audioFile ->
                Pcm16WavFile.open(audioFile).use { wav -> vad.detect(wav) }
            }
        }
        vadIntervals.forEachIndexed { index, intervals ->
            assertTrue("Silero missed labeled speaker ${index + 1}", intervals.isNotEmpty())
            val sampleCount = Pcm16WavFile.open(audioFiles[index]).use { it.sampleCount }
            assertTrue(intervals.all {
                it.startSample >= 0L && it.endSample <= sampleCount
            })
        }

        val speakerObservations = SpeakerEmbeddingOnnxEngine(models.ecapaGraph).use { engine ->
            audioFiles.map { audioFile ->
                Pcm16WavFile.open(audioFile).use { wav ->
                    engine.embed(wav, listOf(SpeechInterval(0L, wav.sampleCount)))
                }
            }
        }
        val firstSpeaker = speakerObservations[0]
        val secondSpeaker = speakerObservations[1]
        assertTrue(
            "Same-speaker clip did not produce overlapping-window embeddings",
            firstSpeaker.size >= 2
        )
        assertTrue("Second speaker clip did not produce an embedding", secondSpeaker.isNotEmpty())

        val sameSpeakerSimilarity = requireNotNull(firstSpeaker[0].embedding)
            .cosineSimilarity(requireNotNull(firstSpeaker[1].embedding))
        val crossSpeakerSimilarities = firstSpeaker.flatMap { first ->
            secondSpeaker.map { second ->
                requireNotNull(first.embedding)
                    .cosineSimilarity(requireNotNull(second.embedding))
            }
        }
        val meanCrossSpeakerSimilarity = crossSpeakerSimilarities.average().toFloat()
        assertTrue(sameSpeakerSimilarity > meanCrossSpeakerSimilarity)

        val observations = listOf(
            firstSpeaker[0].copy(interval = SpeechInterval(0L, WINDOW_SAMPLES)),
            secondSpeaker[0].copy(
                interval = SpeechInterval(WINDOW_SAMPLES, 2L * WINDOW_SAMPLES)
            ),
            firstSpeaker[1].copy(
                interval = SpeechInterval(2L * WINDOW_SAMPLES, 3L * WINDOW_SAMPLES)
            )
        )
        val abaLabels = SpeakerClustering(initialDiarizationConfig().clustering)
            .cluster(observations)
            .map { it.speakerId }
        assertEquals(
            listOf("speaker_1", "speaker_2", "speaker_1"),
            abaLabels
        )

        Log.i(
            LOG_TAG,
            "labeledVoxCeleb speakers=2 vadIntervals=${vadIntervals.map { it.size }} " +
                    "embeddingWindows=${speakerObservations.map { it.size }} " +
                    "sameSpeakerCosine=$sameSpeakerSimilarity " +
                    "crossSpeakerMeanCosine=$meanCrossSpeakerSimilarity " +
                    "realEmbeddingA-B-A=$abaLabels"
        )
    }

    private fun copyTestAsset(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, name)
        instrumentation.context.assets.open(name).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private companion object {
        const val REFERENCE_AUDIO_FILE = "gigaam-reference.wav"
        const val LOG_TAG = "DiarizationDiag"
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SAMPLES = SAMPLE_RATE * 3L
        const val DIAGNOSTIC_DIRECTORY = "diarization-diagnostics"
        const val SPEAKER_A_FILE = "voxceleb_speaker_10003.wav"
        const val SPEAKER_B_FILE = "voxceleb_speaker_10004.wav"
    }
}
