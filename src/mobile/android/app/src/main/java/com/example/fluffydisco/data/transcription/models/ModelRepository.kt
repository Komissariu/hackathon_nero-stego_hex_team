package com.example.fluffydisco.data.transcription.models

import android.content.Context

class ModelRepository(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val preferences =
        ModelPreferences(appContext)

    private val availability =
        ModelAvailability(appContext)

    fun getSelectedModelId(): String {
        return preferences.getSelectedModelId()
    }

    fun selectModel(modelId: String) {
        val model = ModelCatalog.getById(modelId)
            ?: error("Unknown model: $modelId")

        require(availability.isCompatible(model)) {
            "Model is not compatible with this device"
        }

        require(downloadManager.isInstalled(model)) {
            "Model is not installed: $modelId"
        }

        preferences.setSelectedModelId(modelId)
    }

    fun getAvailableModels(): List<ModelInfo> {
        return ModelCatalog.models.filter {
            availability.isCompatible(it)
        }
    }

    fun getModels(): List<ModelInfo> {
        return ModelCatalog.models
    }

    fun getDownloadableModels(): List<ModelInfo> {
        return ModelCatalog.getDownloadableModels()
            .filter {
                availability.isCompatible(it)
            }
    }

    fun getBundledModels(): List<ModelInfo> {
        return ModelCatalog.models.filter {
            downloadManager.hasBundledArchive(it) &&
                    availability.isCompatible(it)
        }
    }

    fun getModel(modelId: String): ModelInfo? {
        return ModelCatalog.getById(modelId)
    }

    fun isCompatible(modelId: String): Boolean {
        val model = getModel(modelId)
            ?: return false

        return availability.isCompatible(model)
    }

    private val downloadManager =
        ModelDownloadManager(appContext)

    fun isDownloaded(modelId: String): Boolean {
        val model = getModel(modelId)
            ?: return false

        return downloadManager.isInstalled(model)
    }

    fun getModelStatus(modelId: String): ModelStatus {
        val model = getModel(modelId)
            ?: return ModelStatus.NOT_BUNDLED
        return availability.getStatus(
            model = model,
            isInstalled = downloadManager.isInstalled(model),
            hasBundledArchive = downloadManager.hasBundledArchive(model),
            isInstalling = downloadManager.isInstalling(model),
            hasInstallationData = downloadManager.hasInstallationData(model)
        )
    }

    suspend fun installBundledModel(
        modelId: String,
        onStateChange: (ModelDownloadState) -> Unit = {}
    ): java.io.File {
        val model = getModel(modelId)
            ?: error("Unknown model: $modelId")

        require(model.bundledArchive != null) {
            "This model is not bundled in the application"
        }

        require(availability.isCompatible(model)) {
            "Model is not compatible with this device"
        }

        return downloadManager.installBundledModel(
            model = model,
            onStateChange = onStateChange
        )
    }

    fun getInstalledModelDirectory(modelId: String): java.io.File {
        val model = getModel(modelId)
            ?: error("Unknown model: $modelId")
        return downloadManager.getInstalledModelDirectory(model)
    }
}