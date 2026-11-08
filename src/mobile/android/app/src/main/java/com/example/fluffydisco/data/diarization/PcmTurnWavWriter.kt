package com.example.fluffydisco.data.diarization

import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Exports an exact PCM16 mono WAV sample range without loading all audio into RAM. */
class PcmTurnWavWriter(source: File) : Closeable {
    private val wav = Pcm16WavFile.open(source)
    private val sourceFile = RandomAccessFile(source, "r")

    fun write(interval: SpeechInterval, target: File) {
        require(interval.endSample <= wav.sampleCount)
        val bytesToCopy = interval.durationSamples * 2L
        require(bytesToCopy in 2..MAX_PCM_BYTES) { "WAV turn exceeds RIFF limit" }
        val pcmBytes = bytesToCopy.toInt()
        val buffer = ByteArray(64 * 1024)
        try {
            DataOutputStream(FileOutputStream(target)).use { out ->
                out.writeBytes("RIFF")
                out.writeIntLE(36 + pcmBytes)
                out.writeBytes("WAVE")
                out.writeBytes("fmt ")
                out.writeIntLE(16)
                out.writeShortLE(1)
                out.writeShortLE(1)
                out.writeIntLE(wav.sampleRate)
                out.writeIntLE(wav.sampleRate * 2)
                out.writeShortLE(2)
                out.writeShortLE(16)
                out.writeBytes("data")
                out.writeIntLE(pcmBytes)
                sourceFile.seek(wav.dataOffset + interval.startSample * 2)
                var remaining = bytesToCopy
                while (remaining > 0L) {
                    val count = minOf(buffer.size.toLong(), remaining).toInt()
                    sourceFile.readFully(buffer, 0, count)
                    out.write(buffer, 0, count)
                    remaining -= count
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    override fun close() {
        try {
            sourceFile.close()
        } finally {
            wav.close()
        }
    }

    private fun DataOutputStream.writeIntLE(v: Int) {
        writeByte(v and 0xff)
        writeByte((v ushr 8) and 0xff)
        writeByte((v ushr 16) and 0xff)
        writeByte((v ushr 24) and 0xff)
    }

    private fun DataOutputStream.writeShortLE(v: Int) {
        writeByte(v and 0xff)
        writeByte((v ushr 8) and 0xff)
    }

    private companion object {
        const val MAX_PCM_BYTES = Int.MAX_VALUE.toLong() - 36L
    }
}
