package com.example.fluffydisco.data.transcription.models

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException
import java.util.Locale

class ModelArchiveExtractor {

    fun extract(
        archiveFile: File,
        targetDirectory: File,
        maxUnpackedBytes: Long,
        maxEntries: Int,
        maxDecoderMemoryKiB: Int,
        ensureActive: () -> Unit = {},
        onProgress: (extractedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        require(maxUnpackedBytes > 0)
        require(maxEntries > 0)
        require(maxDecoderMemoryKiB in MIN_DECODER_MEMORY_KIB..MAX_DECODER_MEMORY_KIB)

        try {
            check(archiveFile.isFile) { "Archive file is missing" }
            check(targetDirectory.isDirectory) { "Staging directory is missing" }
            check(targetDirectory.list()?.isEmpty() == true) {
                "Staging directory is not empty"
            }

            val root = targetDirectory.canonicalFile

            SevenZFile.builder()
                .setFile(archiveFile)
                .setMaxMemoryLimitKiB(maxDecoderMemoryKiB)
                .get()
                .use { archive ->
                    val plannedEntries = validateEntries(
                        entries = collectEntriesWithinLimit(archive.entries, maxEntries),
                        root = root,
                        maxUnpackedBytes = maxUnpackedBytes
                    )
                    val totalBytes = plannedEntries.sumOf { it.size }
                    var extractedBytes = 0L
                    val buffer = ByteArray(COPY_BUFFER_SIZE)

                    plannedEntries.forEach { planned ->
                        ensureActive()
                        val entry = archive.nextEntry
                            ?: invalidArchive("Archive ended before all entries were read")

                        if (entry.name != planned.entry.name ||
                            entry.size != planned.size ||
                            entry.isDirectory != planned.isDirectory
                        ) {
                            invalidArchive("Archive entry metadata changed while reading")
                        }

                        val destination = resolveInside(root, planned.path)
                        ensureNoSymlinkParents(root, destination)

                        if (planned.isDirectory) {
                            if (!destination.exists() && !destination.mkdirs()) {
                                storageError("Could not create a model directory")
                            }
                            if (!destination.isDirectory) {
                                invalidArchive("Archive file and directory names conflict")
                            }
                            return@forEach
                        }

                        val parent = destination.parentFile
                            ?: invalidArchive("Archive entry has no parent directory")
                        if (!parent.exists() && !parent.mkdirs()) {
                            storageError("Could not create a model directory")
                        }
                        ensureNoSymlinkParents(root, destination)

                        if (!destination.createNewFile()) {
                            invalidArchive("Archive contains duplicate file paths")
                        }

                        var entryBytes = 0L
                        try {
                            destination.outputStream().use { output ->
                                while (true) {
                                    ensureActive()
                                    val read = archive.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue

                                    if (read.toLong() > planned.size - entryBytes ||
                                        read.toLong() > maxUnpackedBytes - extractedBytes
                                    ) {
                                        invalidArchive("Archive expands beyond its declared limits")
                                    }

                                    output.write(buffer, 0, read)
                                    entryBytes += read
                                    extractedBytes += read
                                    onProgress(extractedBytes, totalBytes)
                                }
                            }
                        } catch (e: ModelInstallException) {
                            throw e
                        } catch (e: IOException) {
                            storageError("Could not write an extracted model file", e)
                        }

                        if (entryBytes != planned.size) {
                            invalidArchive("Extracted file size does not match the archive header")
                        }
                    }

                    if (archive.nextEntry != null) {
                        invalidArchive("Archive contains entries not present in its metadata")
                    }
                    if (extractedBytes != totalBytes) {
                        invalidArchive("Extracted size does not match the archive header")
                    }
                    onProgress(extractedBytes, totalBytes)
                }
        } catch (e: ModelInstallException) {
            throw e
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            invalidArchive("Could not read the 7z archive", e)
        }
    }

    private fun validateEntries(
        entries: List<SevenZArchiveEntry>,
        root: File,
        maxUnpackedBytes: Long
    ): List<PlannedEntry> {
        val seen = mutableSetOf<String>()
        val directories = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        val planned = ArrayList<PlannedEntry>(entries.size)
        var totalBytes = 0L

        entries.forEach { entry ->
            val rawName = entry.name
                ?: invalidArchive("Archive contains an unnamed entry")
            val path = normalizeArchivePath(rawName, entry.isDirectory)
            val key = path.lowercase(Locale.ROOT)

            if (!seen.add(key)) {
                invalidArchive("Archive contains duplicate entry names")
            }
            if (path.equals(INSTALLED_MARKER, ignoreCase = true) ||
                path.startsWith("$INSTALLED_MARKER/", ignoreCase = true)
            ) {
                invalidArchive("Archive attempts to provide the installation marker")
            }
            if (entry.isAntiItem) {
                invalidArchive("Archive contains an unsupported anti-item")
            }
            if (entry.hasWindowsAttributes &&
                entry.windowsAttributes and WINDOWS_REPARSE_POINT != 0
            ) {
                invalidArchive("Archive contains a symbolic link or reparse point")
            }

            val size = entry.size
            if (size < 0 || (entry.isDirectory && size != 0L)) {
                invalidArchive("Archive contains an invalid entry size")
            }
            if (entry.isDirectory && entry.hasStream()) {
                invalidArchive("Archive directory entry contains file data")
            }
            if (!entry.isDirectory && !entry.hasStream() && size != 0L) {
                invalidArchive("Archive contains an invalid empty file entry")
            }

            if (entry.isDirectory) {
                if (key in files) {
                    invalidArchive("Archive file and directory names conflict")
                }
                directories += key
            } else {
                if (key in directories) {
                    invalidArchive("Archive file and directory names conflict")
                }
                val segments = key.split('/')
                for (index in 1 until segments.size) {
                    val parentKey = segments.take(index).joinToString("/")
                    if (parentKey in files) {
                        invalidArchive("Archive file and directory names conflict")
                    }
                    directories += parentKey
                }
                files += key
                if (size > maxUnpackedBytes - totalBytes) {
                    invalidArchive("Archive exceeds the maximum unpacked size")
                }
                totalBytes += size
            }

            resolveInside(root, path)
            planned += PlannedEntry(entry, path, size, entry.isDirectory)
        }

        return planned
    }

    private fun normalizeArchivePath(
        rawPath: String,
        isDirectory: Boolean
    ): String {
        if (rawPath.isEmpty() || rawPath.startsWith('/') ||
            rawPath.contains('\\') || rawPath.contains(':') ||
            rawPath.any { it.code < 0x20 || it.code == 0x7f }
        ) {
            invalidArchive("Archive contains an unsafe path")
        }

        val normalized = if (isDirectory && rawPath.endsWith('/')) {
            rawPath.dropLast(1)
        } else {
            rawPath
        }
        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            invalidArchive("Archive contains an unsafe path")
        }
        return normalized
    }

    private fun resolveInside(root: File, relativePath: String): File {
        val destination = File(root, relativePath).canonicalFile
        val rootPath = root.path
        if (!destination.path.startsWith("$rootPath${File.separator}")) {
            invalidArchive("Archive entry escapes the staging directory")
        }
        return destination
    }

    private fun ensureNoSymlinkParents(root: File, destination: File) {
        var current: File? = destination
        while (current != null && current != root) {
            if (java.nio.file.Files.isSymbolicLink(current.toPath())) {
                invalidArchive("Archive path resolves through a symbolic link")
            }
            current = current.parentFile
        }
        if (current != root) {
            invalidArchive("Archive entry escapes the staging directory")
        }
    }

    private fun invalidArchive(
        message: String,
        cause: Throwable? = null
    ): Nothing = throw ModelInstallException(
        ModelInstallError.INVALID_ARCHIVE,
        IOException(message, cause)
    )

    private fun storageError(
        message: String,
        cause: Throwable? = null
    ): Nothing = throw ModelInstallException(
        ModelInstallError.STORAGE_ERROR,
        IOException(message, cause)
    )

    private data class PlannedEntry(
        val entry: SevenZArchiveEntry,
        val path: String,
        val size: Long,
        val isDirectory: Boolean
    )

    companion object {
        private const val COPY_BUFFER_SIZE = 16 * 1024
        private const val WINDOWS_REPARSE_POINT = 0x400
        private const val INSTALLED_MARKER = ".installed"
        private const val MIN_DECODER_MEMORY_KIB = 64 * 1024
        private const val MAX_DECODER_MEMORY_KIB = 256 * 1024
    }
}

internal fun collectEntriesWithinLimit(
    entries: Iterable<SevenZArchiveEntry>,
    maxEntries: Int
): List<SevenZArchiveEntry> {
    val collected = ArrayList<SevenZArchiveEntry>(minOf(maxEntries, 1024))
    for (entry in entries) {
        if (collected.size >= maxEntries) {
            throw ModelInstallException(
                ModelInstallError.INVALID_ARCHIVE,
                IOException("Archive contains too many entries")
            )
        }
        collected += entry
    }
    return collected
}

internal fun validateModelRelativePath(path: String) {
    require(path.isNotEmpty())
    require(!path.startsWith('/') && !path.contains('\\') && !path.contains(':'))
    require(!path.startsWith(".installed/", ignoreCase = true))
    require(!path.equals(".installed", ignoreCase = true))
    require(path.split('/').all {
        it.isNotEmpty() && it != "." && it != ".." &&
                it.none { character -> character.code < 0x20 || character.code == 0x7f }
    })
}
