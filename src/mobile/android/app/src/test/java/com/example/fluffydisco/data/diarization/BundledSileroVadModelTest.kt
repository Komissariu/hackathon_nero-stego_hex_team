package com.example.fluffydisco.data.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class BundledSileroVadModelTest {

    @Test
    fun bundledModelAndLicenseMatchPinnedUpstreamArtifact() {
        val model = locate(
            "app/src/main/assets/models/diarization/silero_vad_16k_op15.onnx"
        )
        val license = locate(
            "app/src/main/assets/models/diarization/SILERO-VAD-LICENSE.txt"
        )
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }

        assertEquals(1_289_603L, model.length())
        assertEquals(
            "7ed98ddbad84ccac4cd0aeb3099049280713df825c610a8ed34543318f1b2c49",
            hash
        )
        assertTrue(license.readText().contains("MIT License"))
        assertTrue(license.readText().contains("Silero Team"))
    }

    private fun locate(relativePath: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("Could not locate repository file: $relativePath")
    }
}
