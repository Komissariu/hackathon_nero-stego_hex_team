package com.example.fluffydisco.data.transcription

import android.content.Context
import com.example.fluffydisco.data.transcription.models.ModelCatalog
import com.example.fluffydisco.data.transcription.models.ModelPreferences
import com.example.fluffydisco.data.transcription.models.ModelRepository

class TranscriptionEngineFactory(
    context: Context
) {
    private val appContext = context.applicationContext
    private val preferences = ModelPreferences(
        appContext
    )
    private val modelRepository = ModelRepository(
        appContext
    )

    fun create(
        modelId: String = preferences.getSelectedModelId()
    ): TranscriptionEngine {

        val model = ModelCatalog.getById(modelId)
            ?: error("Selected model not found: $modelId")

        return createLocalEngine(model.id)
    }

    private fun createLocalEngine(
        modelId: String
    ): TranscriptionEngine {
        val modelDirectory = modelRepository.getInstalledModelDirectory(modelId)

        return when (modelId) {
            ModelCatalog.BASIC_ID -> {
                WhisperOnnxTranscriptionEngine(
                    context = appContext,
                    modelDirectory = modelDirectory
                )
            }

            ModelCatalog.GIGAAM_ID -> {
                GigaAmOnnxTranscriptionEngine(
                    context = appContext,
                    modelDirectory = modelDirectory
                )
            }

            ModelCatalog.MEDIUM_ID -> {
                error(
                    "Medium transcription engine is not connected yet " +
                            "(model files are installed at ${modelDirectory.absolutePath})"
                )
            }

            ModelCatalog.ULTRA_ID -> {
                error(
                    "Ultra Pro Max transcription engine is not connected yet " +
                            "(model files are installed at ${modelDirectory.absolutePath})"
                )
            }

            else -> {
                error("Unsupported local model: $modelId")
            }
        }
    }
}
