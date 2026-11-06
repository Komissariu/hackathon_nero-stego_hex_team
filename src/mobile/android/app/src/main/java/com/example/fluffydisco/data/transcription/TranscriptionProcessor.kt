package com.example.fluffydisco.data.transcription

import android.content.Context
import android.net.Uri
import com.example.fluffydisco.data.local.FluffyDiscoDatabase
import com.example.fluffydisco.data.storage.TranscriptStorage
import androidx.core.net.toUri

class TranscriptionProcessor(context: Context) {

    private val dao = FluffyDiscoDatabase
        .getInstance(context.applicationContext)
        .recordingDao()

    private val engine: TranscriptionEngine =
        DemoTranscriptionEngine()

    private val storage = TranscriptStorage(
        context.applicationContext
    )

    suspend fun process(recordingId: String) {
        val recording = dao.getById(recordingId)
            ?: error("Recording not found: $recordingId")

        // Do not regenerate a completed transcript.
        if (recording.status == "TRANSCRIBED" &&
            recording.transcriptUri != null
        ) {
            return
        }

        dao.updateTranscription(
            id = recordingId,
            status = "PROCESSING",
            transcriptUri = null
        )

        // TODO: Replace DemoTranscriptionEngine with local ASR.
        val segments = engine.transcribe(
            recording.audioUri.toUri()
        )

        // TODO: Support a user-selected documents folder.
        // TODO: Store structured segments for the speaker-based UI.
        val transcriptUri = storage.save(
            fileName = "$recordingId.txt",
            segments = segments,
            isDemo = true
        )

        dao.updateTranscription(
            id = recordingId,
            status = "TRANSCRIBED",
            transcriptUri = transcriptUri.toString()
        )
    }
}