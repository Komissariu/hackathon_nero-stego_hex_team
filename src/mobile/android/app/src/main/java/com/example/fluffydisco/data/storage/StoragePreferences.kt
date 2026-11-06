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
    private val folderKey = stringPreferencesKey("recordings_folder_uri")

    val folderUri: Flow<Uri?> =
        context.storageDataStore.data.map { preferences ->
            preferences[folderKey]?.let(Uri::parse)
        }

    suspend fun setFolder(uri: Uri?) {
        context.storageDataStore.edit { preferences ->
            if (uri == null) {
                preferences.remove(folderKey)
            } else {
                preferences[folderKey] = uri.toString()
            }
        }
    }
}