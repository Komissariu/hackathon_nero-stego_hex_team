package com.example.fluffydisco.data.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToLong

object AudioDecoder {

    private const val TARGET_SAMPLE_RATE = 16_000
    private const val TARGET_CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16

    /**
     * Декодирует аудиофайл в WAV PCM 16 kHz Mono 16 bit.
     *
     * Поддерживаемые форматы зависят от декодеров,
     * доступных на Android-устройстве.
     */
    fun decodeToWav(
        context: Context,
        inputUri: Uri,
        outputFile: File
    ) {
        val parent = outputFile.parentFile
        if (parent != null && !parent.exists()) {
            check(parent.mkdirs()) {
                "Cannot create output directory"
            }
        }

        val rawFile = File.createTempFile(
            "audio_decoder_",
            ".pcm",
            parent ?: context.cacheDir
        )

        try {
            val audioInfo = decodeToMonoPcm(
                context = context,
                inputUri = inputUri,
                rawFile = rawFile
            )

            resampleToWav(
                rawFile = rawFile,
                outputFile = outputFile,
                sourceSampleRate = audioInfo.sampleRate
            )
        } finally {
            rawFile.delete()
        }
    }

    private data class AudioInfo(
        val sampleRate: Int
    )

    /**
     * Декодирует исходное аудио в сырой PCM Mono 16 bit.
     * Частота дискретизации пока остаётся исходной.
     */
    private fun decodeToMonoPcm(
        context: Context,
        inputUri: Uri,
        rawFile: File
    ): AudioInfo {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var decoderStarted = false

        try {
            context.contentResolver
                .openFileDescriptor(inputUri, "r")
                ?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                }
                ?: error("Cannot open input audio file")

            var trackIndex = -1
            var inputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)

                if (mime?.startsWith("audio/") == true) {
                    trackIndex = i
                    inputFormat = format
                    break
                }
            }

            require(trackIndex >= 0 && inputFormat != null) {
                "No audio track found"
            }

            extractor.selectTrack(trackIndex)

            val mime = inputFormat.getString(
                MediaFormat.KEY_MIME
            ) ?: error("Audio MIME type is missing")

            var sampleRate = inputFormat.getInteger(
                MediaFormat.KEY_SAMPLE_RATE
            )

            var channelCount = inputFormat.getInteger(
                MediaFormat.KEY_CHANNEL_COUNT
            )

            decoder = MediaCodec.createDecoderByType(mime)

            decoder.configure(
                inputFormat,
                null,
                null,
                0
            )

            decoder.start()
            decoderStarted = true

            var inputDone = false
            var outputDone = false

            val bufferInfo = MediaCodec.BufferInfo()

            BufferedOutputStream(
                FileOutputStream(rawFile)
            ).use { rawOutput ->

                while (!outputDone) {

                    // Передаём данные декодеру
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)

                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(
                                inputIndex
                            ) ?: error("Cannot get decoder input buffer")

                            inputBuffer.clear()

                            val sampleSize = extractor.readSampleData(
                                inputBuffer,
                                0
                            )

                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )

                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )

                                extractor.advance()
                            }
                        }
                    }

                    // Получаем декодированные данные
                    val outputIndex = decoder.dequeueOutputBuffer(
                        bufferInfo,
                        10_000
                    )

                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat

                            sampleRate = outputFormat.getInteger(
                                MediaFormat.KEY_SAMPLE_RATE
                            )

                            channelCount = outputFormat.getInteger(
                                MediaFormat.KEY_CHANNEL_COUNT
                            )

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                val encoding =
                                    if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                    } else {
                                        null
                                    }
                                resolveOutputPcmEncoding(encoding)
                            }
                        }

                        outputIndex >= 0 -> {
                            val outputBuffer = decoder.getOutputBuffer(
                                outputIndex
                            ) ?: error("Cannot get decoder output buffer")

                            if (bufferInfo.size > 0) {
                                require(channelCount > 0) {
                                    "Invalid audio channel count"
                                }
                                require(
                                    bufferInfo.offset >= 0 &&
                                            bufferInfo.offset <= outputBuffer.capacity() &&
                                            bufferInfo.size <= outputBuffer.capacity() - bufferInfo.offset
                                ) {
                                    "Invalid PCM buffer bounds"
                                }

                                val pcm = outputBuffer
                                    .duplicate()
                                    .order(ByteOrder.LITTLE_ENDIAN)

                                pcm.position(bufferInfo.offset)
                                pcm.limit(
                                    bufferInfo.offset + bufferInfo.size
                                )

                                val bytesPerFrame = channelCount.toLong() * 2

                                require(
                                    bufferInfo.size.toLong() % bytesPerFrame == 0L
                                ) {
                                    "Invalid PCM buffer size"
                                }

                                val frameCount = (
                                        bufferInfo.size.toLong() / bytesPerFrame
                                        ).toInt()

                                val monoBuffer = ByteArray(frameCount * 2)

                                var outputPosition = 0

                                repeat(frameCount) {
                                    var sum = 0

                                    repeat(channelCount) {
                                        sum += pcm.short.toInt()
                                    }

                                    val monoSample = (
                                            sum / channelCount
                                            ).coerceIn(
                                            Short.MIN_VALUE.toInt(),
                                            Short.MAX_VALUE.toInt()
                                        )

                                    monoBuffer[outputPosition++] =
                                        (monoSample and 0xFF).toByte()

                                    monoBuffer[outputPosition++] =
                                        ((monoSample shr 8) and 0xFF).toByte()
                                }

                                rawOutput.write(monoBuffer)
                            }

                            decoder.releaseOutputBuffer(
                                outputIndex,
                                false
                            )

                            if (
                                bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            ) {
                                outputDone = true
                            }
                        }
                    }
                }
            }

            require(sampleRate > 0) {
                "Invalid source sample rate"
            }

            require(rawFile.length() >= 2) {
                "Decoded audio is empty"
            }

            return AudioInfo(sampleRate)

        } finally {
            if (decoderStarted) {
                try {
                    decoder?.stop()
                } catch (_: Exception) {
                }
            }

            decoder?.release()
            extractor.release()
        }
    }

    /**
     * Изменяет частоту дискретизации линейной интерполяцией
     * и записывает полноценный WAV PCM 16 kHz Mono 16 bit.
     */
    private fun resampleToWav(
        rawFile: File,
        outputFile: File,
        sourceSampleRate: Int
    ) {
        require(sourceSampleRate > 0) {
            "Invalid source sample rate"
        }

        val sourceFrames = rawFile.length() / 2

        require(sourceFrames > 0) {
            "No PCM samples to resample"
        }

        val targetFrames = (
                sourceFrames.toDouble() *
                        TARGET_SAMPLE_RATE /
                        sourceSampleRate
                ).roundToLong().coerceAtLeast(1)

        val dataSize = targetFrames * 2

        require(dataSize <= Int.MAX_VALUE - 36) {
            "Audio file is too large for standard WAV"
        }

        RandomAccessFile(rawFile, "r").use { input ->

            BufferedOutputStream(
                FileOutputStream(outputFile)
            ).use { output ->

                writeWavHeader(
                    output = output,
                    audioDataSize = dataSize
                )

                var leftIndex = 0L
                var left = readPcmSample(input)

                var right = if (sourceFrames > 1) {
                    readPcmSample(input)
                } else {
                    left
                }

                val ratio =
                    sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE

                repeat(targetFrames.toInt()) { outputIndex ->

                    val sourcePosition = (
                            outputIndex.toDouble() * ratio
                            ).coerceAtMost((sourceFrames - 1).toDouble())

                    val sourceIndex = floor(sourcePosition).toLong()

                    while (leftIndex < sourceIndex) {
                        left = right
                        leftIndex++

                        right = if (leftIndex < sourceFrames - 1) {
                            readPcmSample(input)
                        } else {
                            left
                        }
                    }

                    val fraction = sourcePosition - sourceIndex

                    val interpolated = (
                            left + (right - left) * fraction
                            ).toInt().coerceIn(
                            Short.MIN_VALUE.toInt(),
                            Short.MAX_VALUE.toInt()
                        )

                    output.write(interpolated and 0xFF)
                    output.write((interpolated shr 8) and 0xFF)
                }
            }
        }
    }

    private fun readPcmSample(
        input: RandomAccessFile
    ): Int {
        val low = input.read()
        val high = input.read()

        require(low >= 0 && high >= 0) {
            "Unexpected end of PCM file"
        }

        val value = low or (high shl 8)

        return if (value >= 32768) {
            value - 65536
        } else {
            value
        }
    }

    /**
     * Записывает стандартный WAV-заголовок PCM.
     */
    private fun writeWavHeader(
        output: BufferedOutputStream,
        audioDataSize: Long
    ) {
        val byteRate =
            TARGET_SAMPLE_RATE * TARGET_CHANNELS * BITS_PER_SAMPLE / 8

        val blockAlign =
            TARGET_CHANNELS * BITS_PER_SAMPLE / 8

        val header = ByteBuffer
            .allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((36 + audioDataSize).toInt())

                put("WAVE".toByteArray(Charsets.US_ASCII))

                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort()) // PCM
                putShort(TARGET_CHANNELS.toShort())
                putInt(TARGET_SAMPLE_RATE)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(BITS_PER_SAMPLE.toShort())

                put("data".toByteArray(Charsets.US_ASCII))
                putInt(audioDataSize.toInt())
            }

        output.write(header.array())
    }
}

internal fun resolveOutputPcmEncoding(encoding: Int?): Int {
    val resolvedEncoding = encoding ?: AudioFormat.ENCODING_PCM_16BIT
    require(resolvedEncoding == AudioFormat.ENCODING_PCM_16BIT) {
        "Unsupported decoder PCM encoding: $resolvedEncoding"
    }
    return resolvedEncoding
}