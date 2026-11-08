package com.example.fluffydisco.data.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.storageDataStore by preferencesDataStore(
    name = "storage_settings"
)

class StoragePreferences(
    private val context: Context
) {
    private val audioFolderKey = stringPreferencesKey("recordings_folder_uri")
    private val transcriptFolderKey = stringPreferencesKey("transcripts_folder_uri")

    val folderUri: Flow<Uri?> =
        context.storageDataStore.data.map { preferences ->
            preferences[audioFolderKey]?.let(Uri::parse)
        }

    val transcriptFolderUri: Flow<Uri?> =
        context.storageDataStore.data.map { preferences ->
            preferences[transcriptFolderKey]?.let(Uri::parse)
        }

    suspend fun setFolder(uri: Uri?) {
        context.storageDataStore.edit { preferences ->
            if (uri == null) {
                preferences.remove(audioFolderKey)
            } else {
                preferences[audioFolderKey] = uri.toString()
            }
        }
    }

    suspend fun setTranscriptFolder(uri: Uri?) {
        context.storageDataStore.edit { preferences ->
            if (uri == null) {
                preferences.remove(transcriptFolderKey)
            } else {
                preferences[transcriptFolderKey] = uri.toString()
            }
        }
    }
}