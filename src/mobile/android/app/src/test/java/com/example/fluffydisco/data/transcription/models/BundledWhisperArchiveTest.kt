package com.example.fluffydisco.data.transcription.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class BundledWhisperArchiveTest {

    @Test
    fun bundledWhisperArchiveMatchesCatalogAndExtractsRuntimeFiles() {
        val model = requireNotNull(ModelCatalog.getById(ModelCatalog.BASIC_ID))
        val archiveInfo = requireNotNull(model.bundledArchive)
        val archive = locateArchive(archiveInfo.assetPath)
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(archive).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }

        assertEquals(archiveInfo.archiveSizeBytes, archive.length())
        assertEquals(archiveInfo.sha256, actualHash)

        val target = kotlin.io.path.createTempDirectory("bundled-whisper-test").toFile()
        try {
            ModelArchiveExtractor().extract(
                archiveFile = archive,
                targetDirectory = target,
                maxUnpackedBytes = archiveInfo.maxUnpackedBytes,
                maxEntries = archiveInfo.maxEntries,
                maxDecoderMemoryKiB = archiveInfo.maxDecoderMemoryKiB
            )
            archiveInfo.requiredFiles.forEach { path ->
                assertTrue("Missing extracted model file: $path", File(target, path).isFile)
            }
        } finally {
            target.deleteRecursively()
        }
    }

    private fun locateArchive(assetPath: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            directory?.let { current ->
                val moduleRelative = File(current, "src/main/assets/$assetPath")
                if (moduleRelative.isFile) return moduleRelative
                val repositoryRelative = File(current, "app/src/main/assets/$assetPath")
                if (repositoryRelative.isFile) return repositoryRelative
                directory = current.parentFile
            }
        }
        error("Could not locate bundled asset: $assetPath")
    }
}
