package com.example.fluffydisco.data.transcription.models

enum class ModelType {
    BUILT_IN,
    DOWNLOADABLE
}

enum class ModelStatus {
    NOT_BUNDLED,
    READY_TO_INSTALL,
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    INSTALLING,
    INSTALLED,
    CORRUPTED,
    INCOMPATIBLE,
    ERROR
}

enum class ModelInstallStage {
    COPYING,
    VERIFYING,
    EXTRACTING,
    VALIDATING,
    FINALIZING
}

data class ModelRequirements(
    val minRamMb: Long = 0,
    val minAndroidSdk: Int = 0,
    val requiresArm64: Boolean = false
)

data class ModelDownloadInfo(
    val url: String,
    val fileName: String,
    val sizeBytes: Long = 0
)

data class BundledModelArchive(
    val version: String,
    val assetPath: String,
    val sha256: String,
    val requiredFiles: Set<String>,
    val maxUnpackedBytes: Long,
    val archiveSizeBytes: Long,
    val maxEntries: Int = 100_000,
    val maxDecoderMemoryKiB: Int = 128 * 1024
)

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val type: ModelType,
    val requirements: ModelRequirements = ModelRequirements(),
    val downloadInfo: ModelDownloadInfo? = null,
    val bundledArchive: BundledModelArchive? = null,
    val bundled: Boolean = false
)
