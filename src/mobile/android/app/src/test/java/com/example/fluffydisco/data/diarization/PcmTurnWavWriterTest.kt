package com.example.fluffydisco.data.diarization

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataOutputStream
import java.io.File

class PcmTurnWavWriterTest {
    @Test
    fun slicedWavPreservesPcmSamplesExactly() {
        val source = File.createTempFile("source-", ".wav")
        val target = File.createTempFile("slice-", ".wav")
        try {
            DataOutputStream(source.outputStream()).use { out ->
                fun intLe(value: Int) {
                    repeat(4) { out.writeByte(value ushr (it * 8)) }
                }

                fun shortLe(value: Int) {
                    repeat(2) { out.writeByte(value ushr (it * 8)) }
                }
                out.writeBytes("RIFF"); intLe(36 + 12)
                out.writeBytes("WAVEfmt "); intLe(16)
                shortLe(1); shortLe(1); intLe(16_000); intLe(32_000)
                shortLe(2); shortLe(16)
                out.writeBytes("data"); intLe(12)
                listOf(-32768, -100, 0, 100, 1000, 32767).forEach(::shortLe)
            }
            PcmTurnWavWriter(source).use { it.write(SpeechInterval(2, 5), target) }
            Pcm16WavFile.open(target).use { slice ->
                assertEquals(3L, slice.sampleCount)
                val samples = FloatArray(3)
                slice.readNormalized(0, 3, samples)
                assertEquals(0f, samples[0], 0f)
                assertEquals(100f / 32768f, samples[1], 0f)
                assertEquals(1000f / 32768f, samples[2], 0f)
            }
        } finally {
            source.delete()
            target.delete()
        }
    }
}
