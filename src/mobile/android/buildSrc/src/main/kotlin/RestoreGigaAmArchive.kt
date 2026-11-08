import groovy.json.JsonSlurper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.math.BigDecimal
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class RestoreGigaAmArchive : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val partsDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun restore() {
        val manifestPath = manifestFile.get().asFile.toPath()
        val partsPath = partsDirectory.get().asFile.toPath().toRealPath()
        val outputRoot = outputDirectory.get().asFile.toPath()
        val manifestText = Files.readString(manifestPath)
        rejectDuplicateJsonKeys(manifestText)
        val manifest = JsonSlurper().parseText(manifestText) as? Map<*, *>
            ?: throw GradleException("GigaAM manifest must be a JSON object")
        val parts = validateManifest(manifest)
        validateNoExtraParts(partsPath, parts)
        val partPaths = parts.map { part ->
            val path = partsPath.resolve(part.name)
            if (Files.isSymbolicLink(path) ||
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                path.toRealPath().parent != partsPath
            ) {
                throw GradleException("GigaAM part is missing or unsafe: ${part.name}")
            }
            val (size, digest) = hashFile(path)
            if (size != part.size || digest != part.sha256) {
                throw GradleException("GigaAM part failed size or SHA-256 verification: ${part.name}")
            }
            path
        }

        val target = outputRoot.resolve("models").resolve(ARCHIVE_NAME)
        Files.createDirectories(target.parent)
        if (Files.isRegularFile(target)) {
            val (size, digest) = hashFile(target)
            if (size == ARCHIVE_SIZE && digest == ARCHIVE_SHA256) {
                logger.lifecycle("GigaAM archive is already restored and verified")
                return
            }
        }

        val temporary = Files.createTempFile(target.parent, ".$ARCHIVE_NAME.", ".tmp")
        try {
            val archiveDigest = MessageDigest.getInstance("SHA-256")
            var archiveSize = 0L
            BufferedOutputStream(
                Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)
            ).use { output ->
                parts.zip(partPaths).forEach { (part, path) ->
                    val partDigest = MessageDigest.getInstance("SHA-256")
                    var partSize = 0L
                    BufferedInputStream(Files.newInputStream(path)).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            partDigest.update(buffer, 0, count)
                            archiveDigest.update(buffer, 0, count)
                            partSize += count
                            archiveSize += count
                        }
                    }
                    if (partSize != part.size || partDigest.hex() != part.sha256) {
                        throw GradleException("GigaAM part changed while restoring: ${part.name}")
                    }
                }
            }
            java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            if (archiveSize != ARCHIVE_SIZE || archiveDigest.hex() != ARCHIVE_SHA256) {
                throw GradleException("Restored GigaAM archive failed trusted size or SHA-256 verification")
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            logger.lifecycle("Restored and verified GigaAM archive ($ARCHIVE_SIZE bytes)")
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun validateManifest(manifest: Map<*, *>): List<Part> {
        val expectedKeys = setOf(
            "modelId",
            "archiveName",
            "archiveSizeBytes",
            "archiveSha256",
            "partSizeBytes",
            "partCount",
            "revision",
            "parts",
        )
        if (manifest.keys != expectedKeys) {
            throw GradleException("GigaAM manifest fields do not match the required schema")
        }
        if (manifest["modelId"] != MODEL_ID ||
            manifest["archiveName"] != ARCHIVE_NAME ||
            manifest["archiveSizeBytes"].longValue("archiveSizeBytes") != ARCHIVE_SIZE ||
            manifest["archiveSha256"] != ARCHIVE_SHA256 ||
            manifest["partSizeBytes"].longValue("partSizeBytes") != PART_SIZE ||
            manifest["revision"] != MODEL_REVISION
        ) {
            throw GradleException("GigaAM manifest does not match the trusted archive metadata")
        }
        val listedParts = manifest["parts"] as? List<*>
            ?: throw GradleException("GigaAM manifest parts must be an array")
        val expectedCount = ((ARCHIVE_SIZE + PART_SIZE - 1) / PART_SIZE).toInt()
        if (manifest["partCount"].longValue("partCount") != expectedCount.toLong() ||
            listedParts.size != expectedCount
        ) {
            throw GradleException("GigaAM manifest has an incorrect part count")
        }
        var remaining = ARCHIVE_SIZE
        return listedParts.mapIndexed { index, value ->
            val item = value as? Map<*, *>
                ?: throw GradleException("GigaAM part entry must be an object")
            if (item.keys != setOf("name", "sizeBytes", "sha256")) {
                throw GradleException("GigaAM part entry has unexpected fields")
            }
            val expectedName = "$ARCHIVE_NAME.part${(index + 1).toString().padStart(3, '0')}"
            val expectedSize = minOf(PART_SIZE, remaining)
            if (item["name"] != expectedName ||
                item["sizeBytes"].longValue("part size") != expectedSize ||
                item["sha256"] !is String ||
                !(item["sha256"] as String).matches(Regex("[0-9a-f]{64}"))
            ) {
                throw GradleException("GigaAM part order, name, size, or SHA-256 is invalid")
            }
            remaining -= expectedSize
            Part(expectedName, expectedSize, item["sha256"] as String)
        }.also {
            if (remaining != 0L) throw GradleException("GigaAM manifest does not cover the archive")
        }
    }

    private fun validateNoExtraParts(directory: Path, parts: List<Part>) {
        val expectedNames = parts.map { it.name }.toSet()
        Files.newDirectoryStream(directory, "$ARCHIVE_NAME.part*").use { stream: DirectoryStream<Path> ->
            stream.forEach { path ->
                if (path.fileName.toString() !in expectedNames) {
                    throw GradleException("Unexpected GigaAM archive part: ${path.fileName}")
                }
            }
        }
    }

    private fun hashFile(path: Path): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        BufferedInputStream(Files.newInputStream(path)).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                size += count
            }
        }
        return size to digest.hex()
    }

    private fun rejectDuplicateJsonKeys(json: String) {
        val keySets = mutableListOf<MutableSet<String>?>()
        var index = 0
        while (index < json.length) {
            when (json[index]) {
                '{' -> {
                    keySets.add(mutableSetOf())
                    index++
                }
                '[' -> {
                    keySets.add(null)
                    index++
                }
                '}', ']' -> {
                    if (keySets.isNotEmpty()) keySets.removeAt(keySets.lastIndex)
                    index++
                }
                '"' -> {
                    val start = index + 1
                    index = start
                    while (index < json.length) {
                        if (json[index] == '\\') {
                            index += 2
                        } else if (json[index++] == '"') {
                            break
                        }
                    }
                    val end = (index - 1).coerceAtLeast(start)
                    var following = index
                    while (following < json.length && json[following].isWhitespace()) following++
                    val currentKeys = keySets.lastOrNull()
                    if (following < json.length && json[following] == ':' && currentKeys != null) {
                        val key = json.substring(start, end)
                        if ('\\' in key || !currentKeys.add(key)) {
                            throw GradleException("GigaAM manifest has an escaped or duplicate JSON key")
                        }
                    }
                }
                else -> index++
            }
        }
    }

    private fun Any?.longValue(label: String): Long = when (this) {
        is BigDecimal -> try {
            longValueExact()
        } catch (_: ArithmeticException) {
            throw GradleException("GigaAM manifest $label must be an integer")
        }
        is Int -> toLong()
        is Long -> this
        else -> throw GradleException("GigaAM manifest $label must be an integer")
    }

    private data class Part(val name: String, val size: Long, val sha256: String)

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    private companion object {
        const val MODEL_ID = "gigaam-v3-e2e-ctc-int8"
        const val ARCHIVE_NAME = "gigaam-v3-e2e-ctc-int8.7z"
        const val ARCHIVE_SIZE = 155_314_569L
        const val ARCHIVE_SHA256 = "a803fc4f257df7b602b5b28325cf550577abf768893941cdf7c6c918c4915125"
        const val PART_SIZE = 40_000_000L
        const val MODEL_REVISION = "0928919f7df949871b507e16497b9e8cff7d0c6b"
        const val BUFFER_SIZE = 1024 * 1024
    }
}
