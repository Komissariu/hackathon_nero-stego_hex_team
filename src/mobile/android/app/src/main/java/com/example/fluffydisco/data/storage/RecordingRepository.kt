package com.example.fluffydisco.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingRepository(
    private val context: Context
) {
    private val preferences = StoragePreferences(context)
    private val audioStorage = AudioStorage(context)

    suspend fun save(file: File): Uri = withContext(Dispatchers.IO) {
        val name = "recording_${
            SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.ROOT
            ).format(Date())
        }.m4a"

        val customFolder = preferences.folderUri.first()

        val savedUri = if (customFolder == null) {
            audioStorage.saveAudio(file, name)
        } else {
            saveToCustomFolder(file, name, customFolder)
        }

        file.delete()
        savedUri
    }

    private fun saveToCustomFolder(
        source: File,
        name: String,
        folderUri: Uri
    ): Uri {
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: error("Folder is unavailable")

        check(folder.isDirectory && folder.canWrite()) {
            "Folder is not writable"
        }

        val target = folder.createFile("audio/mp4", name)
            ?: error("Cannot create audio file")

        try {
            val output = context.contentResolver.openOutputStream(target.uri)
                ?: error("Cannot open output stream")

            output.use { stream ->
                source.inputStream().use { input ->
                    input.copyTo(stream)
                }
            }

            return target.uri
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }
}