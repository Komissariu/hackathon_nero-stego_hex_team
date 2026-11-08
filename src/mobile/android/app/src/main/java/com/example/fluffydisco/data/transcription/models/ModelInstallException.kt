package com.example.fluffydisco.data.transcription.models

enum class ModelInstallError {
    INVALID_METADATA,
    ARCHIVE_NOT_BUNDLED,
    ARCHIVE_MISSING,
    INSUFFICIENT_STORAGE,
    CHECKSUM_MISMATCH,
    INVALID_ARCHIVE,
    REQUIRED_FILES_MISSING,
    STORAGE_ERROR
}

class ModelInstallException(
    val reason: ModelInstallError,
    cause: Throwable? = null
) : Exception(reason.name, cause)
