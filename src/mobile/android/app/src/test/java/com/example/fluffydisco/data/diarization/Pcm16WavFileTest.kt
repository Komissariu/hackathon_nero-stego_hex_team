package com.example.fluffydisco.data.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Pcm16WavFileTest {

    @Test
    fun readsPcmSamplesFromBoundedRanges() {
        val wav = writeWav(intArrayOf(Short.MIN_VALUE.toInt(), 0, Short.MAX_VALUE.toInt()))
        wav.deleteOnExit()

        Pcm16WavFile.open(wav).use { audio ->
            assertEquals(16_000, audio.sampleRate)
            assertEquals(3L, audio.sampleCount)
            val result = floatArrayOf(99f, 99f, 99f, 99f)
            audio.readNormalized(1, 2, result, destinationOffset = 1)

            assertEquals(99f, result[0], 0f)
            assertEquals(0f, result[1], 0f)
            assertEquals(Short.MAX_VALUE.toInt() / 32768f, result[2], 0f)
            assertEquals(99f, result[3], 0f)
            assertThrows(IllegalArgumentException::class.java) {
                audio.readNormalized(2, 2, result)
            }
        }
    }

    @Test
    fun rejectsUnsupportedPcmFormat() {
        val wav = writeWav(intArrayOf(1), channels = 2)
        wav.deleteOnExit()

        assertThrows(IllegalArgumentException::class.java) {
            Pcm16WavFile.open(wav)
        }
    }

    private fun writeWav(samples: IntArray, channels: Int = 1): File {
        val dataSize = samples.size * 2
        val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII))
        bytes.putInt(36 + dataSize)
        bytes.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        bytes.putInt(16)
        bytes.putShort(1)
        bytes.putShort(channels.toShort())
        bytes.putInt(16_000)
        bytes.putInt(16_000 * channels * 2)
        bytes.putShort((channels * 2).toShort())
        bytes.putShort(16)
        bytes.put("data".toByteArray(Charsets.US_ASCII))
        bytes.putInt(dataSize)
        samples.forEach { bytes.putShort(it.toShort()) }
        return File.createTempFile("pcm-test-", ".wav").apply {
            writeBytes(bytes.array())
        }
    }
}
