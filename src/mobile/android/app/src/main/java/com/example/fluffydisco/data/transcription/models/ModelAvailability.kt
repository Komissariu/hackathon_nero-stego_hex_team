package com.example.fluffydisco.data.transcription.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build

class ModelAvailability(
    context: Context
) {

    private val appContext = context.applicationContext

    fun isCompatible(model: ModelInfo): Boolean {
        return checkRam(model) &&
                checkAndroidVersion(model) &&
                checkArchitecture(model)
    }

    fun getStatus(
        model: ModelInfo,
        isInstalled: Boolean,
        hasBundledArchive: Boolean,
        isInstalling: Boolean,
        hasInstallationData: Boolean
    ): ModelStatus {
        if (!isCompatible(model)) return ModelStatus.INCOMPATIBLE
        if (isInstalled) return ModelStatus.INSTALLED
        if (!hasBundledArchive) return ModelStatus.NOT_BUNDLED
        if (isInstalling) return ModelStatus.INSTALLING
        if (hasInstallationData) return ModelStatus.CORRUPTED
        return ModelStatus.READY_TO_INSTALL
    }

    private fun checkRam(model: ModelInfo): Boolean {
        val requiredRam =
            model.requirements.minRamMb

        if (requiredRam <= 0) {
            return true
        }

        val activityManager =
            appContext.getSystemService(
                Context.ACTIVITY_SERVICE
            ) as? ActivityManager
                ?: return false

        val memoryInfo = ActivityManager.MemoryInfo()

        activityManager.getMemoryInfo(memoryInfo)

        val availableRamMb =
            memoryInfo.totalMem / (1024L * 1024L)

        return availableRamMb >= requiredRam
    }

    private fun checkAndroidVersion(
        model: ModelInfo
    ): Boolean {
        return Build.VERSION.SDK_INT >=
                model.requirements.minAndroidSdk
    }

    private fun checkArchitecture(
        model: ModelInfo
    ): Boolean {
        if (!model.requirements.requiresArm64) {
            return true
        }

        return Build.SUPPORTED_ABIS.any {
            it == "arm64-v8a"
        }
    }
}