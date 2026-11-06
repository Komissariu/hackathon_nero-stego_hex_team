package com.example.fluffydisco.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.example.fluffydisco.domain.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TranscriptStorage(
    private val context: Context
) {
    suspend fun save(
        fileName: String,
        segments: List<TranscriptSegment>,
        isDemo: Boolean
    ): Uri = withContext(Dispatchers.IO) {

        val content = buildString {
            appendLine("FluffyDisco")
            appendLine()

            if (isDemo) {
                appendLine(
                    "DEMO TRANSCRIPT — NOT GENERATED FROM AUDIO"
                )
                appendLine()
            }

            segments.forEach { segment ->
                val seconds = segment.startMs / 1000
                val minutes = seconds / 60

                appendLine(
                    "[%02d:%02d] %s: %s".format(
                        minutes,
                        seconds % 60,
                        segment.speakerId,
                        segment.text
                    )
                )
            }
        }

        val values = ContentValues().apply {
            put(
                MediaStore.MediaColumns.DISPLAY_NAME,
                fileName
            )
            put(
                MediaStore.MediaColumns.MIME_TYPE,
                "text/plain"
            )
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOCUMENTS}/FluffyDisco"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver

        val uri = resolver.insert(
            MediaStore.Files.getContentUri("external"),
            values
        ) ?: error("Cannot create transcript")

        try {
            val output = resolver.openOutputStream(uri)
                ?: error("Cannot open transcript output")

            output.bufferedWriter(Charsets.UTF_8).use {
                it.write(content)
            }

            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null
            )

            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}