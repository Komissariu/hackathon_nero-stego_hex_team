package com.example.fluffydisco.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class AudioStorage(
    private val context: Context
) {
    private val preferences = StoragePreferences(
        context.applicationContext
    )

    suspend fun saveAudio(
        source: File,
        fileName: String
    ): Uri = withContext(Dispatchers.IO) {

        require(source.exists() && source.isFile) {
            "Source audio file does not exist: ${source.absolutePath}"
        }

        val customFolderUri = preferences.folderUri.first()

        // Пользовательская директория
        if (customFolderUri != null) {

            val folder = DocumentFile.fromTreeUri(
                context,
                customFolderUri
            ) ?: error("Cannot open custom audio folder")

            check(folder.isDirectory && folder.canWrite()) {
                "Custom audio folder is not writable"
            }

            // Удаляем старый файл с таким именем
            folder.findFile(fileName)?.delete()

            val newFile = folder.createFile(
                "audio/mp4",
                fileName
            ) ?: error("Cannot create audio file in custom folder")

            try {
                context.contentResolver
                    .openOutputStream(newFile.uri, "wt")
                    ?.use { output ->
                        source.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: error("Cannot open audio output stream")

                return@withContext newFile.uri

            } catch (e: Exception) {
                newFile.delete()
                throw e
            }
        }

        // Директория по умолчанию: Music/FluffyDisco

        val values = ContentValues().apply {
            put(
                MediaStore.Audio.Media.DISPLAY_NAME,
                fileName
            )
            put(
                MediaStore.Audio.Media.MIME_TYPE,
                "audio/mp4"
            )
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/FluffyDisco"
            )
            put(
                MediaStore.Audio.Media.IS_PENDING,
                1
            )
        }

        val resolver = context.contentResolver

        val uri = resolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("Cannot create audio file")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Cannot open audio output stream")

            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
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
