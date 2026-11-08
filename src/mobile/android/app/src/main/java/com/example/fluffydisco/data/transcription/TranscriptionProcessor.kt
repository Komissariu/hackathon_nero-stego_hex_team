package com.example.fluffydisco.data.transcription

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.net.toUri
import com.example.fluffydisco.data.audio.AudioDecoder
import com.example.fluffydisco.data.diarization.DiarizationModelFiles
import com.example.fluffydisco.data.diarization.OfflineDiarizationProcessor
import com.example.fluffydisco.data.diarization.SpeakerAwareAsrCoordinator
import com.example.fluffydisco.data.diarization.UNKNOWN_SPEAKER_ID
import com.example.fluffydisco.data.diarization.initialDiarizationConfig
import com.example.fluffydisco.data.local.FluffyDiscoDatabase
import com.example.fluffydisco.data.storage.TranscriptStorage
import com.example.fluffydisco.data.transcription.models.ModelPreferences
import com.example.fluffydisco.domain.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranscriptionProcessor(
    private val context: Context
) {

    private val appContext = context.applicationContext

    private val dao = FluffyDiscoDatabase
        .getInstance(appContext)
        .recordingDao()

    private val engineFactory = TranscriptionEngineFactory(
        appContext
    )

    private val modelPreferences = ModelPreferences(
        appContext
    )

    private val storage = TranscriptStorage(
        appContext
    )

    suspend fun process(recordingId: String) =
        withContext(Dispatchers.IO) {

            val recording = dao.getById(recordingId)
                ?: error("Recording not found: $recordingId")

            if (
                recording.status == "TRANSCRIBED" &&
                recording.transcriptUri != null
            ) {
                return@withContext
            }

            dao.updateTranscription(
                id = recordingId,
                status = "PROCESSING",
                transcriptUri = null
            )

            val tempWavFile = File(
                appContext.cacheDir,
                "decoded_$recordingId.wav"
            )

            try {
                val selectedModelId =
                    modelPreferences.getSelectedModelId()

                val engine = engineFactory.create(
                    selectedModelId
                )

                val originalUri = recording.audioUri.toUri()

                AudioDecoder.decodeToWav(
                    appContext,
                    originalUri,
                    tempWavFile
                )

                val segments = transcribeWithDiarization(tempWavFile, engine)

                val transcriptUri = storage.save(
                    fileName = "$recordingId.json",
                    segments = segments
                )

                dao.updateTranscription(
                    id = recordingId,
                    status = "TRANSCRIBED",
                    transcriptUri = transcriptUri.toString()
                )

            } catch (e: Exception) {
                dao.updateTranscription(
                    id = recordingId,
                    status = "ERROR",
                    transcriptUri = null
                )

                throw e
            } finally {
                tempWavFile.delete()
            }
        }

    private suspend fun transcribeWithDiarization(
        wavFile: File,
        engine: TranscriptionEngine
    ): List<TranscriptSegment> {
        val models = DiarizationModelFiles.prepare(appContext)
        val modelProblem = models.validationProblem()
        if (modelProblem != null) {
            Log.w(
                TAG, "Speaker model unavailable; returning ASR text with  " +
                        "$UNKNOWN_SPEAKER_ID. $modelProblem"
            )
            return engine.transcribe(wavFile.toUri()).map {
                it.copy(speakerId = UNKNOWN_SPEAKER_ID)
            }
        }

        // Diarize the complete recording first: speaker identities are global.
        val diarization = OfflineDiarizationProcessor(
            vadModel = models.sileroVad,
            speakerEmbeddingModel = models.ecapaGraph,
            config = initialDiarizationConfig()
        ).process(wavFile)

        if (diarization.speechIntervals.isEmpty()) {
            // Conservative fallback: do not silently lose all ASR text when VAD misses speech.
            Log.w(TAG, "VAD produced no intervals; falling back to unassigned ASR")
            return engine.transcribe(wavFile.toUri()).map {
                it.copy(speakerId = UNKNOWN_SPEAKER_ID)
            }
        }

        val segments = SpeakerAwareAsrCoordinator(
            engine = engine,
            cacheDirectory = appContext.cacheDir
        ).transcribe(wavFile, diarization)
        val isDebuggable = appContext.applicationInfo.flags and
                ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (isDebuggable) {
            diarization.clusteringDiagnostics?.let { info ->
                Log.d(
                    TAG, "DIAR_CLUSTER mode=automatic " +
                            "reliableWindows=${info.reliableWindows} " +
                            "inferredSpeakers=${info.detectedSpeakers} " +
                            "selectedMaxDistance=${info.selectedMaxDistance}" +
                            "adaptive=${info.adaptiveCutUsed} gap=${info.evidenceGap}"
                )
            }
            Log.d(
                TAG, "SPEAKER_AWARE_ASR speechIntervals=${diarization.speechIntervals.size} " +
                    "speakerTurns=${diarization.turns.size} " +
                    "outputSegments=${segments.size} " +
                    "speakers=${segments.map { it.speakerId }.distinct().size}"
            )
        }
        return segments
    }

    private companion object {
        const val TAG = "TranscriptionProcessor"
    }
}
