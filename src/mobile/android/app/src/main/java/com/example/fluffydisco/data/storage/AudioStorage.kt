package com.example.fluffydisco.data.storage

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.os.Environment
import android.net.Uri
import java.io.File

class AudioStorage(private val context: Context) {

    fun saveAudio(
        source: File,
        fileName: String
    ): Uri {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/FluffyDisco"
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("Cannot create audio file")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Cannot open output stream")

            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                },
                null,
                null
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        return uri
    }
}