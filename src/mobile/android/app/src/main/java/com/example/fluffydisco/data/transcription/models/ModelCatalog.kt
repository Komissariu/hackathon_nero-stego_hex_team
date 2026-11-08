package com.example.fluffydisco.data.transcription.models

object ModelCatalog {

    const val BASIC_ID = "basic"
    const val MEDIUM_ID = "medium"
    const val ULTRA_ID = "ultra_pro_max"
    const val GIGAAM_ID = "gigaam_v3_e2e_ctc_int8"

    val models = listOf(

        ModelInfo(
            id = BASIC_ID,
            name = "Whisper Tiny RU",
            description = "Локальная русскоязычная модель распознавания речи",
            type = ModelType.BUILT_IN,
            requirements = ModelRequirements(minRamMb = 4096),
            bundledArchive = BundledModelArchive(
                version = "whisper-tiny-ru-v1",
                assetPath = "models/whisper-tiny-ru.7z",
                sha256 = "3f8ff19e0223564f6ee54e22986f3112d65b5c136c71d029ea5f5461180a1ecb",
                requiredFiles = setOf(
                    "config.json",
                    "decoder_model.onnx",
                    "encoder_model.onnx",
                    "generation_config.json",
                    "preprocessor_config.json",
                    "tokenizer.json"
                ),
                maxUnpackedBytes = 128L * 1024 * 1024,
                archiveSizeBytes = 102_747_308L,
                maxDecoderMemoryKiB = 128 * 1024
            )
        ),

        ModelInfo(
            id = GIGAAM_ID,
            name = "GigaAM v3 E2E CTC INT8",
            description = "Русская офлайн-модель распознавания речи",
            type = ModelType.BUILT_IN,
            requirements = ModelRequirements(
                minRamMb = 4096,
                minAndroidSdk = 26,
                requiresArm64 = true
            ),
            bundledArchive = BundledModelArchive(
                version = "gigaam-v3-e2e-ctc-int8-0928919",
                assetPath = "models/gigaam-v3-e2e-ctc-int8.7z",
                sha256 = "a803fc4f257df7b602b5b28325cf550577abf768893941cdf7c6c918c4915125",
                requiredFiles = setOf(
                    "model.int8.onnx",
                    "tokens.txt"
                ),
                maxUnpackedBytes = 225L * 1024 * 1024,
                archiveSizeBytes = 155_314_569L
            )
        )
    )

    fun getById(id: String): ModelInfo? {
        return models.find { it.id == id }
    }

    fun getBuiltInModels(): List<ModelInfo> {
        return models.filter {
            it.type == ModelType.BUILT_IN
        }
    }

    fun getDownloadableModels(): List<ModelInfo> {
        return models.filter {
            it.type == ModelType.DOWNLOADABLE
        }
    }
}
