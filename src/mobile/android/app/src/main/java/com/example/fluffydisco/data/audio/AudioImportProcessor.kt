package com.example.fluffydisco.data.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.fluffydisco.data.local.FluffyDiscoDatabase
import com.example.fluffydisco.data.local.RecordingEntity
import com.example.fluffydisco.data.storage.RecordingRepository
import com.example.fluffydisco.data.transcription.TranscriptionScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class AudioImportProcessor(
    private val context: Context
) {
    private val repository = RecordingRepository(context)

    private val dao = FluffyDiscoDatabase
        .getInstance(context)
        .recordingDao()

    suspend fun importAudio(sourceUri: Uri): String =
        withContext(Dispatchers.IO) {

            val title = getFileName(sourceUri)
            val durationMs = getDuration(sourceUri)

            val directory = File(context.cacheDir, "imports")
            check(directory.exists() || directory.mkdirs()) {
                "Cannot create import directory"
            }

            // TODO: Add import progress reporting for large files.
            // TODO: Support cancellation of an ongoing import.

            val temporaryFile = File.createTempFile(
                "import_",
                ".m4a",
                directory
            )

            try {
                val input = context.contentResolver
                    .openInputStream(sourceUri)
                    ?: error("Cannot open selected audio")

                // TODO: Validate the actual audio format using metadata,
                // instead of relying only on MIME type or file extension.

                input.use { stream ->
                    temporaryFile.outputStream().use { output ->
                        stream.copyTo(output)
                    }
                }

                // RecordingRepository currently stores audio as M4A.
                // TODO: Preserve the actual source format and MIME type.
                // TODO: Avoid transcoding assumptions for MP3, WAV, OGG, etc.
                val audioUri = repository.save(temporaryFile)

                val id = UUID.randomUUID().toString()

                dao.insert(
                    RecordingEntity(
                        id = id,
                        title = title,
                        createdAt = System.currentTimeMillis(),
                        durationMs = durationMs,
                        audioUri = audioUri.toString(),
                        status = "RECORDED"
                    )
                )

                // TODO: Display import and transcription as separate progress stages.
                TranscriptionScheduler(context).enqueue(id)

                id
            } finally {
                temporaryFile.delete()
            }
        }

    private fun getFileName(uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(
                    OpenableColumns.DISPLAY_NAME
                )

                if (index >= 0) {
                    return cursor.getString(index)
                        ?: "Imported audio"
                }
            }
        }

        return "Imported audio"
    }

    private fun getDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(context, uri)

            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }
}
