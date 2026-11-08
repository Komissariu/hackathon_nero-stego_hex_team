package com.example.fluffydisco.data.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
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
            // проверяем MIME-тип файла
            val mimeType = context.contentResolver.getType(sourceUri)
            if (mimeType != null && !mimeType.startsWith("audio/")) {
                throw IllegalArgumentException("Invalid file type")
            }

            val title = getFileName(sourceUri)
            val durationMs = getDuration(sourceUri)

            val extension = getFileExtension(sourceUri)

            val directory = File(context.cacheDir, "imports")
            check(directory.exists() || directory.mkdirs()) {
                "Cannot create import directory"
            }

            val temporaryFile = File.createTempFile(
                "import_",
                ".$extension",
                directory
            )

            try {
                val input = context.contentResolver
                    .openInputStream(sourceUri)
                    ?: error("Cannot open selected audio")

                input.use { stream ->
                    temporaryFile.outputStream().use { output ->
                        stream.copyTo(output)
                    }
                }

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
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return cursor.getString(index) ?: "Imported audio"
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

    private fun getFileExtension(uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri)
        if (mimeType != null) {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            if (extension != null) return extension
        }

        val fileName = getFileName(uri)
        val extFromName = fileName.substringAfterLast('.', "")
        if (extFromName.isNotEmpty()) {
            return extFromName.lowercase()
        }

        return "m4a"
    }
}