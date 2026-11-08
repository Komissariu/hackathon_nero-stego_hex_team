package com.example.fluffydisco.data.diarization

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class DiarizationModelFiles(
    val sileroVad: File,
    val ecapaGraph: File,
    val ecapaExternalData: File
) {
    val isReady: Boolean
        get() = validationProblem() == null

    fun missingFiles(): List<File> =
        listOf(sileroVad, ecapaGraph, ecapaExternalData).filterNot(File::isFile)

    fun validationProblem(): String? {
        val missing = missingFiles()
        if (missing.isNotEmpty()) return "Missing model files: ${missing.joinToString()}"
        if (ecapaGraph.length() != ECAPA_GRAPH_SIZE ||
            sha256(ecapaGraph) != ECAPA_GRAPH_SHA256
        ) {
            return "ECAPA graph size or SHA-256 does not match the inspected model"
        }
        if (ecapaExternalData.length() != ECAPA_EXTERNAL_DATA_SIZE ||
            sha256(ecapaExternalData) != ECAPA_EXTERNAL_DATA_SHA256
        ) {
            return "ECAPA external-data size or SHA-256 does not match the inspected model"
        }
        return null
    }

    companion object {
        const val DIRECTORY_NAME = "diarization"
        const val ECAPA_GRAPH_NAME = "ecapa_ru.onnx"
        const val ECAPA_EXTERNAL_DATA_NAME = "ecapa_ru.onnx.data"
        private const val ASSET_DIRECTORY = "models/diarization"
        private const val VAD_ASSET =
            "models/diarization/silero_vad_16k_op15.onnx"
        private const val VAD_FILE_NAME = "silero_vad_16k_op15.onnx"
        private const val VAD_SHA256 =
            "7ed98ddbad84ccac4cd0aeb3099049280713df825c610a8ed34543318f1b2c49"
        private const val VAD_SIZE = 1_289_603L
        private const val ECAPA_GRAPH_SIZE = 652_140L
        private const val ECAPA_GRAPH_SHA256 =
            "b6408df4ff25fccbb24a93020bf11e3d2b92978269f2439160a7fce1ea602e52"
        private const val ECAPA_EXTERNAL_DATA_SIZE = 83_419_892L
        private const val ECAPA_EXTERNAL_DATA_SHA256 =
            "6cd82747bf973507b57f8df79158b36719515386acc77f62ecf1fe8c0dc0a0a4"
        private const val ECAPA_GRAPH_ASSET = "$ASSET_DIRECTORY/$ECAPA_GRAPH_NAME"
        private const val ECAPA_EXTERNAL_DATA_ASSET =
            "$ASSET_DIRECTORY/$ECAPA_EXTERNAL_DATA_NAME"

        fun prepare(context: Context): DiarizationModelFiles {
            val directory = File(File(context.filesDir, "models"), DIRECTORY_NAME)
            check(directory.isDirectory || directory.mkdirs()) {
                "Cannot create diarization model directory: $directory"
            }
            val vadFile = File(directory, VAD_FILE_NAME)
            copyAssetIfNeeded(
                context = context,
                assetName = VAD_ASSET,
                target = vadFile,
                expectedSize = VAD_SIZE,
                expectedSha256 = VAD_SHA256,
                description = "Silero VAD model"
            )

            val bundledAssets = context.assets.list(ASSET_DIRECTORY).orEmpty().toSet()
            val hasEcapaGraph = ECAPA_GRAPH_NAME in bundledAssets
            val hasEcapaExternalData = ECAPA_EXTERNAL_DATA_NAME in bundledAssets
            check(hasEcapaGraph == hasEcapaExternalData) {
                "Bundled ECAPA graph and external data must be provided together"
            }
            if (hasEcapaGraph) {
                copyAssetIfNeeded(
                    context = context,
                    assetName = ECAPA_GRAPH_ASSET,
                    target = File(directory, ECAPA_GRAPH_NAME),
                    expectedSize = ECAPA_GRAPH_SIZE,
                    expectedSha256 = ECAPA_GRAPH_SHA256,
                    description = "ECAPA graph"
                )
                copyAssetIfNeeded(
                    context = context,
                    assetName = ECAPA_EXTERNAL_DATA_ASSET,
                    target = File(directory, ECAPA_EXTERNAL_DATA_NAME),
                    expectedSize = ECAPA_EXTERNAL_DATA_SIZE,
                    expectedSha256 = ECAPA_EXTERNAL_DATA_SHA256,
                    description = "ECAPA external data"
                )
            }
            return DiarizationModelFiles(
                sileroVad = vadFile,
                ecapaGraph = File(directory, ECAPA_GRAPH_NAME),
                ecapaExternalData = File(directory, ECAPA_EXTERNAL_DATA_NAME)
            )
        }

        private fun copyAssetIfNeeded(
            context: Context,
            assetName: String,
            target: File,
            expectedSize: Long,
            expectedSha256: String,
            description: String
        ) {
            if (hasExpectedHash(target, expectedSize, expectedSha256)) return
            val parent = requireNotNull(target.parentFile)
            check(parent.isDirectory || parent.mkdirs()) {
                "Cannot create model directory: $parent"
            }
            val temporary = File.createTempFile("diarization-model-", ".tmp", parent)
            try {
                context.assets.open(assetName).use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(hasExpectedHash(temporary, expectedSize, expectedSha256)) {
                    "Bundled $description failed size or SHA-256 verification"
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } finally {
                temporary.delete()
            }
        }

        private fun hasExpectedHash(
            file: File,
            expectedSize: Long,
            expectedSha256: String
        ): Boolean =
            file.isFile &&
                    file.length() == expectedSize &&
                    sha256(file) == expectedSha256

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(file.inputStream()).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

fun initialDiarizationConfig(): OfflineDiarizationConfig =
    OfflineDiarizationConfig(
        clustering = SpeakerClusteringConfig(
            maxAverageCosineDistance = 0.5f,
            minReliableDurationSamples = 12_800L,
            minEmbeddingConfidence = 0.15f
        ),
        speakerChanges = SpeakerChangeConfig(
            minMeanCosineDistance = 0.5f,
            contextWindows = 2,
            minTurnSamples = 12_800L
        )
    )
