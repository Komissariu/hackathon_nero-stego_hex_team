package com.example.fluffydisco.data.transcription.models

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.coroutineContext

class ModelDownloadManager private constructor(
    private val modelsDirectory: File,
    private val openBundledAsset: (String) -> InputStream,
    private val extractor: ModelArchiveExtractor
) {

    constructor(context: Context) : this(
        context.applicationContext,
        ModelArchiveExtractor()
    )

    private constructor(
        appContext: Context,
        extractor: ModelArchiveExtractor
    ) : this(
        File(appContext.filesDir, MODELS_DIRECTORY),
        { assetPath -> appContext.assets.open(assetPath) },
        extractor
    )

    internal constructor(
        modelsDirectory: File,
        openBundledAsset: (String) -> InputStream
    ) : this(modelsDirectory, openBundledAsset, ModelArchiveExtractor())

    suspend fun installBundledModel(
        model: ModelInfo,
        onStateChange: (ModelDownloadState) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val archiveInfo = model.bundledArchive
            ?: throw ModelInstallException(ModelInstallError.ARCHIVE_NOT_BUNDLED)
        validateMetadata(model, archiveInfo)

        val lockKey = "${modelsDirectory.canonicalPath}:${model.id}"
        val mutex = installLocks.getOrPut(lockKey) { Mutex() }
        val installContext = coroutineContext

        mutex.withLock {
            installContext.ensureActive()
            val destination = getVersionDirectory(model, archiveInfo)
            if (isInstalledAt(model, archiveInfo, destination)) {
                onStateChange(
                    ModelDownloadState(
                        modelId = model.id,
                        status = ModelStatus.INSTALLED,
                        progress = 1f
                    )
                )
                return@withLock destination
            }

            val versionDirectory = destination.parentFile
                ?: throw ModelInstallException(ModelInstallError.INVALID_METADATA)
            if (!versionDirectory.exists() && !versionDirectory.mkdirs()) {
                throw ModelInstallException(ModelInstallError.STORAGE_ERROR)
            }
            cleanupStaleFiles(versionDirectory)
            checkAvailableSpace(versionDirectory, archiveInfo)

            val token = UUID.randomUUID().toString()
            val archiveFile = File(versionDirectory, ".archive-$token.7z")
            val stagingDirectory = File(versionDirectory, ".staging-$token")
            var lastCopyProgressPercent = -1

            try {
                onStateChange(
                    ModelDownloadState(
                        modelId = model.id,
                        status = ModelStatus.INSTALLING,
                        stage = ModelInstallStage.COPYING
                    )
                )
                val copied = copyBundledArchive(
                    archiveInfo = archiveInfo,
                    destination = archiveFile,
                    ensureActive = { installContext.ensureActive() },
                    onProgress = { copiedBytes ->
                        val progress = (copiedBytes.toFloat() /
                                archiveInfo.archiveSizeBytes).coerceIn(0f, 1f)
                        val percent = (progress * 100).toInt()
                        if (percent != lastCopyProgressPercent) {
                            lastCopyProgressPercent = percent
                            onStateChange(
                                ModelDownloadState(
                                    modelId = model.id,
                                    status = ModelStatus.INSTALLING,
                                    progress = progress,
                                    stage = ModelInstallStage.COPYING
                                )
                            )
                        }
                    }
                )

                onStateChange(
                    ModelDownloadState(
                        modelId = model.id,
                        status = ModelStatus.INSTALLING,
                        stage = ModelInstallStage.VERIFYING
                    )
                )
                if (!copied.equals(archiveInfo.sha256, ignoreCase = true)) {
                    throw ModelInstallException(ModelInstallError.CHECKSUM_MISMATCH)
                }

                if (!stagingDirectory.mkdir()) {
                    throw ModelInstallException(ModelInstallError.STORAGE_ERROR)
                }
                onStateChange(
                    ModelDownloadState(
                        modelId = model.id,
                        status = ModelStatus.INSTALLING,
                        progress = 0f,
                        stage = ModelInstallStage.EXTRACTING
                    )
                )

                var lastProgressPercent = -1
                extractor.extract(
                    archiveFile = archiveFile,
                    targetDirectory = stagingDirectory,
                    maxUnpackedBytes = archiveInfo.maxUnpackedBytes,
                    maxEntries = archiveInfo.maxEntries,
                    maxDecoderMemoryKiB = archiveInfo.maxDecoderMemoryKiB,
                    ensureActive = { installContext.ensureActive() },
                    onProgress = { extractedBytes, totalBytes ->
                        val progress = if (totalBytes > 0) {
                            (extractedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                        } else {
                            null
                        }
                        val progressPercent = progress?.times(100)?.toInt()
                        if (progressPercent != lastProgressPercent) {
                            lastProgressPercent = progressPercent ?: -1
                            onStateChange(
                                ModelDownloadState(
                                    modelId = model.id,
                                    status = ModelStatus.INSTALLING,
                                    progress = progress,
                                    stage = ModelInstallStage.EXTRACTING
                                )
                            )
                        }
                    }
                )

                onStateChange(
                    ModelDownloadState(
                        modelId = model.id,
                        status = ModelStatus.INSTALLING,
                        stage = ModelInstallStage.VALIDATING
                    )
                )
                validateRequiredFiles(
                    archiveInfo = archiveInfo,
                    stagingDirectory = stagingDirectory
                )
                writeInstallMarker(
                    marker = File(stagingDirectory, INSTALL_MARKER),
                    model = model,
                    archiveInfo = archiveInfo
                )

                onStateChange(
                    ModelDownloadState(
                        modelId = model.id,
                        status = ModelStatus.INSTALLING,
                        progress = 1f,
                        stage = ModelInstallStage.FINALIZING
                    )
                )
                promoteStagingDirectory(stagingDirectory, destination)
                if (!isInstalledAt(model, archiveInfo, destination)) {
                    throw ModelInstallException(ModelInstallError.STORAGE_ERROR)
                }

                onStateChange(
                    ModelDownloadState(
                        modelId = model.id,
                        status = ModelStatus.INSTALLED,
                        progress = 1f
                    )
                )
                destination
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelInstallException) {
                logger.log(
                    Level.SEVERE,
                    "Model installation failed for ${model.id}: ${e.reason}",
                    e
                )
                onStateChange(
                    ModelDownloadState(
                        modelId = model.id,
                        status = ModelStatus.ERROR,
                        error = e.reason.name
                    )
                )
                throw e
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Model installation failed for ${model.id}", e)
                val failure = ModelInstallException(
                    ModelInstallError.STORAGE_ERROR,
                    e
                )
                onStateChange(
                    ModelDownloadState(
                        modelId = model.id,
                        status = ModelStatus.ERROR,
                        error = failure.reason.name
                    )
                )
                throw failure
            } finally {
                if (archiveFile.exists() && !archiveFile.delete()) {
                    logger.warning("Could not remove temporary model archive for ${model.id}")
                }
                if (stagingDirectory.exists() && !stagingDirectory.deleteRecursively()) {
                    logger.warning("Could not remove staging directory for ${model.id}")
                }
            }
        }
    }

    fun isInstalled(model: ModelInfo): Boolean {
        val archiveInfo = model.bundledArchive ?: return false
        return runCatching {
            isInstalledAt(
                model,
                archiveInfo,
                getVersionDirectory(model, archiveInfo)
            )
        }.getOrElse { error ->
            logger.log(
                Level.WARNING,
                "Could not verify model installation for ${model.id}",
                error
            )
            false
        }
    }

    fun hasBundledArchive(model: ModelInfo): Boolean {
        val archiveInfo = model.bundledArchive ?: return false
        return try {
            validateAssetPath(archiveInfo.assetPath)
            openBundledAsset(archiveInfo.assetPath).use { true }
        } catch (_: FileNotFoundException) {
            false
        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "Could not inspect bundled archive for ${model.id}",
                e
            )
            false
        }
    }

    fun isInstalling(model: ModelInfo): Boolean {
        if (model.bundledArchive == null) return false
        val lockKey = runCatching {
            "${modelsDirectory.canonicalPath}:${validateIdentifier(model.id)}"
        }.getOrNull() ?: return false
        return installLocks[lockKey]?.isLocked == true
    }

    fun hasInstallationData(model: ModelInfo): Boolean {
        val archiveInfo = model.bundledArchive ?: return false
        return runCatching {
            getVersionDirectory(model, archiveInfo).exists()
        }.getOrDefault(false)
    }

    fun getInstalledModelDirectory(model: ModelInfo): File {
        val archiveInfo = model.bundledArchive
            ?: throw ModelInstallException(ModelInstallError.ARCHIVE_NOT_BUNDLED)
        val directory = getVersionDirectory(model, archiveInfo)
        if (!isInstalledAt(model, archiveInfo, directory)) {
            throw ModelInstallException(ModelInstallError.REQUIRED_FILES_MISSING)
        }
        return directory
    }

    private fun copyBundledArchive(
        archiveInfo: BundledModelArchive,
        destination: File,
        ensureActive: () -> Unit,
        onProgress: (Long) -> Unit
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var copiedBytes = 0L

        try {
            openBundledAsset(archiveInfo.assetPath).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        if (copiedBytes > Long.MAX_VALUE - read ||
                            copiedBytes + read > archiveInfo.archiveSizeBytes
                        ) {
                            throw ModelInstallException(ModelInstallError.INVALID_ARCHIVE)
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copiedBytes += read
                        onProgress(copiedBytes)
                    }
                    output.fd.sync()
                }
            }
        } catch (e: FileNotFoundException) {
            throw ModelInstallException(ModelInstallError.ARCHIVE_MISSING, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelInstallException) {
            throw e
        } catch (e: IOException) {
            throw ModelInstallException(ModelInstallError.STORAGE_ERROR, e)
        }

        if (copiedBytes == 0L || copiedBytes != archiveInfo.archiveSizeBytes) {
            throw ModelInstallException(ModelInstallError.INVALID_ARCHIVE)
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkAvailableSpace(
        directory: File,
        archiveInfo: BundledModelArchive
    ) {
        val archiveBytes = archiveInfo.archiveSizeBytes
        val archiveAndModelBytes = safeAdd(
            archiveBytes,
            archiveInfo.maxUnpackedBytes
        ) ?: throw ModelInstallException(ModelInstallError.INVALID_METADATA)
        val requiredBytes = safeAdd(
            archiveAndModelBytes,
            STORAGE_RESERVE_BYTES
        ) ?: throw ModelInstallException(ModelInstallError.INVALID_METADATA)
        if (directory.usableSpace < requiredBytes) {
            throw ModelInstallException(ModelInstallError.INSUFFICIENT_STORAGE)
        }
    }

    private fun validateRequiredFiles(
        archiveInfo: BundledModelArchive,
        stagingDirectory: File
    ) {
        val missing = archiveInfo.requiredFiles.filter { relativePath ->
            val file = File(stagingDirectory, relativePath).canonicalFile
            !file.path.startsWith("${stagingDirectory.canonicalPath}${File.separator}") ||
                    !file.isFile
        }
        if (missing.isNotEmpty()) {
            throw ModelInstallException(ModelInstallError.REQUIRED_FILES_MISSING)
        }
    }

    private fun writeInstallMarker(
        marker: File,
        model: ModelInfo,
        archiveInfo: BundledModelArchive
    ) {
        val contents = markerContents(model, archiveInfo)
        try {
            if (!marker.createNewFile()) {
                throw ModelInstallException(ModelInstallError.STORAGE_ERROR)
            }
            FileOutputStream(marker).use { output ->
                output.write(contents.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        } catch (e: ModelInstallException) {
            throw e
        } catch (e: IOException) {
            throw ModelInstallException(ModelInstallError.STORAGE_ERROR, e)
        }
    }

    private fun promoteStagingDirectory(stagingDirectory: File, destination: File) {
        var quarantine: File? = null
        if (destination.exists()) {
            quarantine = File(
                destination.parentFile,
                ".quarantined-${UUID.randomUUID()}"
            )
            if (!destination.renameTo(quarantine)) {
                throw ModelInstallException(ModelInstallError.STORAGE_ERROR)
            }
        }

        if (!stagingDirectory.renameTo(destination)) {
            if (quarantine != null && !quarantine.renameTo(destination)) {
                logger.severe("Could not restore previous model directory after failed promotion")
            }
            throw ModelInstallException(ModelInstallError.STORAGE_ERROR)
        }
    }

    private fun isInstalledAt(
        model: ModelInfo,
        archiveInfo: BundledModelArchive,
        directory: File
    ): Boolean {
        val marker = File(directory, INSTALL_MARKER)
        if (!directory.isDirectory || !marker.isFile) return false
        if (marker.readText(Charsets.UTF_8) != markerContents(model, archiveInfo)) {
            return false
        }
        return archiveInfo.requiredFiles.all { relativePath ->
            val requiredFile = File(directory, relativePath).canonicalFile
            requiredFile.path.startsWith("${directory.canonicalPath}${File.separator}") &&
                    requiredFile.isFile
        }
    }

    private fun markerContents(
        model: ModelInfo,
        archiveInfo: BundledModelArchive
    ): String = buildString {
        append(MARKER_MAGIC).append('\n')
        append(model.id).append('\n')
        append(archiveInfo.version).append('\n')
        append(archiveInfo.sha256.lowercase(Locale.ROOT)).append('\n')
        append("complete\n")
        archiveInfo.requiredFiles.sorted().forEach {
            append(it).append('\n')
        }
    }

    private fun getVersionDirectory(
        model: ModelInfo,
        archiveInfo: BundledModelArchive
    ): File {
        return File(
            File(
                File(modelsDirectory, validateIdentifier(model.id)),
                validateIdentifier(archiveInfo.version)
            ),
            validateSha256(archiveInfo.sha256)
        )
    }

    private fun cleanupStaleFiles(versionDirectory: File) {
        val canonicalRoot = versionDirectory.canonicalFile
        versionDirectory.listFiles()?.forEach { child ->
            if (child.name.startsWith(".staging-") ||
                child.name.startsWith(".archive-")
            ) {
                if (child.canonicalFile.parentFile == canonicalRoot &&
                    !child.deleteRecursively()
                ) {
                    logger.warning("Could not remove stale model install file ${child.name}")
                }
            }
        }
    }

    private fun validateMetadata(
        model: ModelInfo,
        archiveInfo: BundledModelArchive
    ) {
        try {
            validateIdentifier(model.id)
            validateIdentifier(archiveInfo.version)
            validateAssetPath(archiveInfo.assetPath)
            validateSha256(archiveInfo.sha256)
            require(archiveInfo.requiredFiles.isNotEmpty())
            archiveInfo.requiredFiles.forEach(::validateModelRelativePath)
            require(archiveInfo.maxUnpackedBytes > 0)
            require(archiveInfo.maxEntries in 1..MAX_ARCHIVE_ENTRIES)
            require(
                archiveInfo.maxDecoderMemoryKiB in
                        MIN_DECODER_MEMORY_KIB..MAX_DECODER_MEMORY_KIB
            )
            require(archiveInfo.archiveSizeBytes > 0)
        } catch (e: IllegalArgumentException) {
            throw ModelInstallException(ModelInstallError.INVALID_METADATA, e)
        }
    }

    private fun validateAssetPath(path: String) {
        require(path.startsWith("models/"))
        require(path.endsWith(".7z"))
        require(!path.contains('\\') && !path.contains(':'))
        require(path.none { it.code < 0x20 || it.code == 0x7f })
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
    }

    private fun validateIdentifier(value: String): String {
        require(value.matches(IDENTIFIER_PATTERN))
        require(value != "." && value != "..")
        return value
    }

    private fun validateSha256(value: String): String {
        require(value.matches(SHA256_PATTERN))
        return value.lowercase(Locale.ROOT)
    }

    private fun safeAdd(first: Long, second: Long): Long? {
        if (second > Long.MAX_VALUE - first) return null
        return first + second
    }

    companion object {
        private const val MODELS_DIRECTORY = "models"
        private const val INSTALL_MARKER = ".installed"
        private const val MARKER_MAGIC = "FluffyDiscoModelInstallV1"
        private const val COPY_BUFFER_SIZE = 16 * 1024
        private const val STORAGE_RESERVE_BYTES = 8L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 100_000
        private const val MIN_DECODER_MEMORY_KIB = 64 * 1024
        private const val MAX_DECODER_MEMORY_KIB = 256 * 1024
        private val logger = Logger.getLogger("ModelInstaller")
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
        private val SHA256_PATTERN = Regex("[A-Fa-f0-9]{64}")
        private val installLocks = ConcurrentHashMap<String, Mutex>()
    }
}
