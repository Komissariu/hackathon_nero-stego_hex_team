package com.example.fluffydisco.data.diarization

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Pcm16WavFile private constructor(
    private val file: RandomAccessFile,
    val dataOffset: Long,
    val sampleCount: Long,
    val sampleRate: Int
) : Closeable {
    private val readBuffer = ByteArray(READ_BUFFER_SAMPLES * BYTES_PER_SAMPLE)

    fun readNormalized(
        startSample: Long,
        sampleCount: Int,
        destination: FloatArray,
        destinationOffset: Int = 0
    ) {
        require(startSample >= 0L && sampleCount >= 0)
        require(
            destinationOffset >= 0 &&
                    destinationOffset <= destination.size - sampleCount
        )
        require(
            startSample <= this.sampleCount &&
                    sampleCount.toLong() <= this.sampleCount - startSample
        ) { "Requested PCM range is outside the WAV data" }

        file.seek(dataOffset + startSample * BYTES_PER_SAMPLE)
        var samplesRead = 0
        while (samplesRead < sampleCount) {
            val batchSamples = minOf(sampleCount - samplesRead, READ_BUFFER_SAMPLES)
            val byteCount = batchSamples * BYTES_PER_SAMPLE
            file.readFully(readBuffer, 0, byteCount)
            val buffer = ByteBuffer.wrap(readBuffer, 0, byteCount)
                .order(ByteOrder.LITTLE_ENDIAN)
            repeat(batchSamples) { index ->
                destination[destinationOffset + samplesRead + index] =
                    buffer.short.toInt() / 32768f
            }
            samplesRead += batchSamples
        }
    }

    override fun close() {
        file.close()
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2
        private const val READ_BUFFER_SAMPLES = 8192
        private const val MAX_HEADER_CHUNK_BYTES = 16L * 1024 * 1024

        fun open(file: File): Pcm16WavFile {
            val input = RandomAccessFile(file, "r")
            try {
                require(input.length() >= WAV_HEADER_BYTES) { "WAV file is too small" }
                require(input.readAscii(4) == "RIFF") { "Expected a RIFF WAV file" }
                input.readIntLE()
                require(input.readAscii(4) == "WAVE") { "Expected a WAV file" }

                var encoding = -1
                var channels = -1
                var sampleRate = -1
                var bitsPerSample = -1
                var dataOffset = -1L
                var dataSize = -1L

                while (input.filePointer + CHUNK_HEADER_BYTES <= input.length()) {
                    val chunkId = input.readAscii(4)
                    val chunkSize = input.readUInt32LE()
                    require(chunkSize <= input.length() - input.filePointer) {
                        "WAV chunk exceeds file bounds"
                    }
                    when (chunkId) {
                        "fmt " -> {
                            require(chunkSize in 16..MAX_HEADER_CHUNK_BYTES) {
                                "Invalid WAV format chunk"
                            }
                            encoding = input.readUnsignedShortLE()
                            channels = input.readUnsignedShortLE()
                            sampleRate = input.readIntLE()
                            input.readIntLE()
                            input.readUnsignedShortLE()
                            bitsPerSample = input.readUnsignedShortLE()
                            input.seek(input.filePointer + chunkSize - 16)
                        }

                        "data" -> {
                            dataOffset = input.filePointer
                            dataSize = chunkSize
                            break
                        }

                        else -> input.seek(input.filePointer + chunkSize)
                    }
                    if (chunkSize and 1L != 0L) input.seek(input.filePointer + 1)
                }

                require(
                    encoding == PCM_ENCODING &&
                            channels == MONO_CHANNELS &&
                            sampleRate == TARGET_SAMPLE_RATE &&
                            bitsPerSample == PCM_BITS_PER_SAMPLE
                ) { "Diarization requires mono 16 kHz 16-bit PCM WAV" }
                require(dataOffset >= 0L && dataSize >= 2L && dataSize % 2L == 0L) {
                    "WAV audio data chunk is missing or invalid"
                }
                return Pcm16WavFile(
                    input,
                    dataOffset,
                    dataSize / BYTES_PER_SAMPLE,
                    sampleRate
                )
            } catch (error: Exception) {
                input.close()
                throw error
            }
        }

        private const val WAV_HEADER_BYTES = 12L
        private const val CHUNK_HEADER_BYTES = 8L
        private const val PCM_ENCODING = 1
        private const val MONO_CHANNELS = 1
        private const val TARGET_SAMPLE_RATE = 16_000
        private const val PCM_BITS_PER_SAMPLE = 16
    }
}

private fun RandomAccessFile.readAscii(length: Int): String {
    val bytes = ByteArray(length)
    readFully(bytes)
    return bytes.toString(Charsets.US_ASCII)
}

private fun RandomAccessFile.readUnsignedShortLE(): Int {
    val low = readUnsignedByte()
    val high = readUnsignedByte()
    return low or (high shl 8)
}

private fun RandomAccessFile.readIntLE(): Int {
    val bytes = ByteArray(Int.SIZE_BYTES)
    readFully(bytes)
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
}

private fun RandomAccessFile.readUInt32LE(): Long =
    readIntLE().toLong() and 0xffff_ffffL
