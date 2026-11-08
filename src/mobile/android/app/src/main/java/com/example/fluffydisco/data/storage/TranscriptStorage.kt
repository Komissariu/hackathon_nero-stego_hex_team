package com.example.fluffydisco.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.example.fluffydisco.domain.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class TranscriptStorage(
    private val context: Context
) {
    private val preferences = StoragePreferences(context)

    suspend fun save(
        fileName: String,
        segments: List<TranscriptSegment>
    ): Uri = withContext(Dispatchers.IO) {

        val content = JSONObject().apply {
            put("schemaVersion", 1)
            put("source", "local")
            put("segments", JSONArray().apply {
                segments.forEach { segment ->
                    put(JSONObject().apply {
                        put("startMs", segment.startMs)
                        put("endMs", segment.endMs)
                        put("speakerId", segment.speakerId)
                        put("text", segment.text)
                    })
                }
            })
        }.toString(2)

        val customFolderUri = preferences.transcriptFolderUri.first()

        if (customFolderUri != null) {
            val folder = DocumentFile.fromTreeUri(
                context,
                customFolderUri
            ) ?: error("Cannot open custom transcript folder")

            check(folder.isDirectory && folder.canWrite()) {
                "Custom transcript folder is not writable"
            }

            folder.findFile(fileName)?.delete()

            val newFile = folder.createFile(
                "application/json",
                fileName
            ) ?: error("Cannot create JSON file in custom folder")

            try {
                context.contentResolver
                    .openOutputStream(newFile.uri, "wt")
                    ?.bufferedWriter(Charsets.UTF_8)
                    ?.use { writer ->
                        writer.write(content)
                    } ?: error("Cannot open JSON output stream")

                return@withContext newFile.uri
            } catch (e: Exception) {
                newFile.delete()
                throw e
            }
        }


        val values = ContentValues().apply {
            put(
                MediaStore.MediaColumns.DISPLAY_NAME,
                fileName
            )
            put(
                MediaStore.MediaColumns.MIME_TYPE,
                "application/json"
            )
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOCUMENTS}/FluffyDisco"
            )
            put(
                MediaStore.MediaColumns.IS_PENDING,
                1
            )
        }

        val resolver = context.contentResolver

        val uri = resolver.insert(
            MediaStore.Files.getContentUri("external"),
            values
        ) ?: error("Cannot create JSON transcript")

        try {
            resolver.openOutputStream(uri, "wt")
                ?.bufferedWriter(Charsets.UTF_8)
                ?.use { writer ->
                    writer.write(content)
                } ?: error("Cannot open JSON output stream")

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
