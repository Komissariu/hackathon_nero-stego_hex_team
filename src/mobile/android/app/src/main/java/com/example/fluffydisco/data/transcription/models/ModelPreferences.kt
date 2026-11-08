package com.example.fluffydisco.data.transcription.models

import android.content.Context
import androidx.core.content.edit

class ModelPreferences(
    context: Context
) {

    private val preferences = context.applicationContext
        .getSharedPreferences(
            "model_preferences",
            Context.MODE_PRIVATE
        )

    fun getSelectedModelId(): String {
        val selectedModelId = preferences.getString(
            KEY_SELECTED_MODEL,
            ModelCatalog.BASIC_ID
        ) ?: ModelCatalog.BASIC_ID
        return if (ModelCatalog.getById(selectedModelId) != null) {
            selectedModelId
        } else {
            ModelCatalog.BASIC_ID
        }
    }

    fun setSelectedModelId(modelId: String) {
        require(
            ModelCatalog.getById(modelId) != null
        ) {
            "Unknown model: $modelId"
        }

        preferences.edit {
            putString(KEY_SELECTED_MODEL, modelId)
        }
    }

    fun clearSelectedModel() {
        preferences.edit {
            remove(KEY_SELECTED_MODEL)
        }
    }

    companion object {
        private const val KEY_SELECTED_MODEL =
            "selected_model_id"
    }
}