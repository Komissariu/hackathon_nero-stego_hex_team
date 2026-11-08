package com.example.fluffydisco.data.transcription.models

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

class ModelArchiveInstallerTest {

    private val temporaryRoot = kotlin.io.path.createTempDirectory("model-installer-test")
        .toFile()

    @After
    fun cleanUp() {
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun installsOnceAndVerifiesRequiredFiles() = runBlocking {
        val payload = "tiny onnx fixture".toByteArray()
        val archive = createArchive(listOf(ArchiveItem("model/model.onnx", payload)))
        var archiveOpens = 0
        val installer = manager("successful") {
            archiveOpens++
            FileInputStream(archive)
        }
        val model = model(archive, setOf("model/model.onnx"))

        val installed = installer.installBundledModel(model)
        val installedFile = File(installed, "model/model.onnx")
        assertArrayEquals(payload, installedFile.readBytes())
        assertTrue(installer.isInstalled(model))
        assertEquals(1, archiveOpens)

        val secondInstall = installer.installBundledModel(model)
        assertEquals(installed, secondInstall)
        assertEquals("Idempotent install must not reopen the asset", 1, archiveOpens)
    }

    @Test
    fun rejectsIncorrectArchiveChecksumWithoutPromotingFiles() = runBlocking {
        val archive = createArchive(listOf(ArchiveItem("model.onnx", byteArrayOf(1, 2, 3))))
        val model = model(
            archive = archive,
            requiredFiles = setOf("model.onnx"),
            sha256 = "0".repeat(64)
        )
        val installer = manager("bad-checksum") { FileInputStream(archive) }

        assertInstallFails(ModelInstallError.CHECKSUM_MISMATCH) {
            installer.installBundledModel(model)
        }
        assertFalse(installer.isInstalled(model))
        assertNoTemporaryInstallFiles(File(temporaryRoot, "bad-checksum"))
    }

    @Test
    fun rejectsMissingRequiredFiles() = runBlocking {
        val archive = createArchive(listOf(ArchiveItem("present.onnx", byteArrayOf(1))))
        val model = model(archive, setOf("missing.onnx"))
        val installer = manager("missing-required") { FileInputStream(archive) }

        assertInstallFails(ModelInstallError.REQUIRED_FILES_MISSING) {
            installer.installBundledModel(model)
        }
        assertFalse(installer.isInstalled(model))
    }

    @Test
    fun reportsMissingBundledAsset() = runBlocking {
        val archive = createArchive(listOf(ArchiveItem("model.onnx", byteArrayOf(1))))
        val model = model(archive, setOf("model.onnx"))
        val installer = manager("missing-asset") {
            throw FileNotFoundException("not in this APK")
        }

        assertInstallFails(ModelInstallError.ARCHIVE_MISSING) {
            installer.installBundledModel(model)
        }
    }

    @Test
    fun checksFreeSpaceBeforeCopyingArchive() = runBlocking {
        val archive = createArchive(listOf(ArchiveItem("model.onnx", byteArrayOf(1))))
        val model = model(
            archive = archive,
            requiredFiles = setOf("model.onnx"),
            maxUnpackedBytes = 1L shl 60
        )
        var archiveOpens = 0
        val installer = manager("insufficient-space") {
            archiveOpens++
            FileInputStream(archive)
        }

        assertInstallFails(ModelInstallError.INSUFFICIENT_STORAGE) {
            installer.installBundledModel(model)
        }
        assertEquals(0, archiveOpens)
    }

    @Test
    fun rejectsCorruptArchiveEvenWhenItsHashMatchesMetadata() = runBlocking {
        val corrupt = File(temporaryRoot, "corrupt.7z").apply {
            writeBytes("not a 7z archive".toByteArray())
        }
        val model = model(corrupt, setOf("model.onnx"))
        val installer = manager("corrupt") { FileInputStream(corrupt) }

        assertInstallFails(ModelInstallError.INVALID_ARCHIVE) {
            installer.installBundledModel(model)
        }
        assertFalse(installer.isInstalled(model))
    }

    @Test
    fun rejectsTraversalDuplicateEntriesAndResourceLimitViolations() {
        val traversalArchive = createArchive(
            listOf(ArchiveItem("../outside.txt", byteArrayOf(1)))
        )
        val duplicateArchive = createArchive(
            listOf(
                ArchiveItem("same.onnx", byteArrayOf(1)),
                ArchiveItem("same.onnx", byteArrayOf(2))
            )
        )
        val twoFilesArchive = createArchive(
            listOf(
                ArchiveItem("one.onnx", byteArrayOf(1)),
                ArchiveItem("two.onnx", byteArrayOf(2))
            )
        )
        val fileDirectoryConflictArchive = createArchive(
            listOf(
                ArchiveItem("entry", byteArrayOf(1)),
                ArchiveItem("entry/child", byteArrayOf(2))
            )
        )
        val markerArchive = createArchive(
            listOf(ArchiveItem(".installed", "fake-complete-marker".toByteArray()))
        )

        assertExtractorRejects(traversalArchive, maxUnpackedBytes = 10, maxEntries = 10)
        assertExtractorRejects(duplicateArchive, maxUnpackedBytes = 10, maxEntries = 10)
        assertExtractorRejects(twoFilesArchive, maxUnpackedBytes = 1, maxEntries = 10)
        assertExtractorRejects(twoFilesArchive, maxUnpackedBytes = 10, maxEntries = 1)
        assertExtractorRejects(
            fileDirectoryConflictArchive,
            maxUnpackedBytes = 10,
            maxEntries = 10
        )
        assertExtractorRejects(markerArchive, maxUnpackedBytes = 100, maxEntries = 10)
    }

    @Test
    fun stopsCollectingEntriesAsSoonAsConfiguredLimitIsExceeded() {
        var iterated = 0
        val entries = Iterable {
            object : Iterator<SevenZArchiveEntry> {
                override fun hasNext() = true

                override fun next(): SevenZArchiveEntry {
                    iterated++
                    return SevenZArchiveEntry().apply {
                        setName("entry-$iterated")
                        setDirectory(true)
                    }
                }
            }
        }

        try {
            collectEntriesWithinLimit(entries, maxEntries = 3)
            throw AssertionError("Expected archive entry limit rejection")
        } catch (e: ModelInstallException) {
            assertEquals(ModelInstallError.INVALID_ARCHIVE, e.reason)
        }
        assertEquals(4, iterated)
    }

    @Test
    fun simultaneousInstallsShareOneExtraction() = runBlocking {
        val archive = createArchive(listOf(ArchiveItem("model.onnx", byteArrayOf(3, 4, 5))))
        var archiveOpens = 0
        val installer = manager("simultaneous") {
            synchronized(this) {
                archiveOpens++
            }
            FileInputStream(archive)
        }
        val model = model(archive, setOf("model.onnx"))

        val first = async(Dispatchers.IO) { installer.installBundledModel(model) }
        val second = async(Dispatchers.IO) { installer.installBundledModel(model) }

        assertEquals(first.await(), second.await())
        assertEquals(1, archiveOpens)
    }

    @Test
    fun failedNewVersionDoesNotRemovePreviouslyInstalledVersion() = runBlocking {
        val archive = createArchive(listOf(ArchiveItem("model.onnx", byteArrayOf(7, 8))))
        val oldModel = model(archive, setOf("model.onnx"), version = "v1")
        val installer = manager("upgrade") { FileInputStream(archive) }

        val oldDirectory = installer.installBundledModel(oldModel)
        val newModel = model(
            archive = archive,
            requiredFiles = setOf("model.onnx"),
            version = "v2",
            sha256 = "f".repeat(64)
        )

        assertInstallFails(ModelInstallError.CHECKSUM_MISMATCH) {
            installer.installBundledModel(newModel)
        }
        assertTrue(installer.isInstalled(oldModel))
        assertArrayEquals(byteArrayOf(7, 8), File(oldDirectory, "model.onnx").readBytes())
    }

    @Test
    fun cancellationCleansStagingAndTemporaryArchive() = runBlocking {
        val largePayload = ByteArray(512 * 1024) { (it % 127).toByte() }
        val archive = createArchive(listOf(ArchiveItem("model.onnx", largePayload)))
        val jobReference = AtomicReference<Job?>()
        val installer = manager("cancel") {
            object : FilterInputStream(FileInputStream(archive)) {
                private var cancelled = false

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    if (count > 0 && !cancelled) {
                        cancelled = true
                        jobReference.get()?.cancel()
                    }
                    return count
                }
            }
        }
        val model = model(archive, setOf("model.onnx"), maxUnpackedBytes = 1024 * 1024)
        val job = launch(start = CoroutineStart.LAZY) {
            installer.installBundledModel(model)
        }
        jobReference.set(job)
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertNoTemporaryInstallFiles(File(temporaryRoot, "cancel"))
        assertFalse(installer.isInstalled(model))
    }

    private fun manager(
        name: String,
        asset: (String) -> java.io.InputStream
    ) = ModelDownloadManager(File(temporaryRoot, name), asset)

    private fun model(
        archive: File,
        requiredFiles: Set<String>,
        version: String = "v1",
        sha256: String = sha256(archive),
        maxUnpackedBytes: Long = 1024
    ) = ModelInfo(
        id = "test-model",
        name = "Test model",
        description = "A model fixture used by tests",
        type = ModelType.BUILT_IN,
        bundledArchive = BundledModelArchive(
            version = version,
            assetPath = "models/test-model.7z",
            sha256 = sha256,
            requiredFiles = requiredFiles,
            archiveSizeBytes = archive.length(),
            maxUnpackedBytes = maxUnpackedBytes
        )
    )

    private fun createArchive(items: List<ArchiveItem>): File {
        val file = File(temporaryRoot, "fixture-${System.nanoTime()}.7z")
        SevenZOutputFile(file).use { output ->
            items.forEach { item ->
                val entry = SevenZArchiveEntry().apply {
                    setName(item.path)
                    setSize(item.contents.size.toLong())
                    setHasStream(item.contents.isNotEmpty())
                    setDirectory(item.directory)
                }
                output.putArchiveEntry(entry)
                if (item.contents.isNotEmpty()) {
                    output.write(item.contents)
                }
                output.closeArchiveEntry()
            }
        }
        return file
    }

    private fun assertExtractorRejects(
        archive: File,
        maxUnpackedBytes: Long,
        maxEntries: Int
    ) {
        val destination = File(temporaryRoot, "extract-${System.nanoTime()}")
        assertTrue(destination.mkdir())
        try {
            ModelArchiveExtractor().extract(
                archiveFile = archive,
                targetDirectory = destination,
                maxUnpackedBytes = maxUnpackedBytes,
                maxEntries = maxEntries,
                maxDecoderMemoryKiB = 128 * 1024
            )
            throw AssertionError("Expected archive rejection")
        } catch (e: ModelInstallException) {
            assertEquals(ModelInstallError.INVALID_ARCHIVE, e.reason)
        } finally {
            destination.deleteRecursively()
        }
    }

    private suspend fun assertInstallFails(
        expected: ModelInstallError,
        block: suspend () -> Unit
    ) {
        try {
            block()
            throw AssertionError("Expected install failure: $expected")
        } catch (e: ModelInstallException) {
            assertEquals(expected, e.reason)
        }
    }

    private fun assertNoTemporaryInstallFiles(root: File) {
        if (!root.exists()) return
        assertTrue(
            root.walkTopDown().none {
                it.name.startsWith(".staging-") || it.name.startsWith(".archive-")
            }
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ArchiveItem(
        val path: String,
        val contents: ByteArray,
        val directory: Boolean = false
    )
}
