package com.example.fluffydisco.data.transcription.models

data class ModelDownloadState(
    val modelId: String,
    val status: ModelStatus,
    val progress: Float? = null,
    val error: String? = null,
    val stage: ModelInstallStage? = null
)