package com.example.fluffydisco.data.audio

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioDecoderTest {

    @Test
    fun missingPcmEncodingDefaultsToPcm16Bit() {
        assertEquals(
            AudioFormat.ENCODING_PCM_16BIT,
            resolveOutputPcmEncoding(null)
        )
    }

    @Test
    fun acceptsExplicitPcm16BitEncoding() {
        assertEquals(
            AudioFormat.ENCODING_PCM_16BIT,
            resolveOutputPcmEncoding(AudioFormat.ENCODING_PCM_16BIT)
        )
    }

    @Test
    fun rejectsExplicitlyUnsupportedPcmEncoding() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveOutputPcmEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        }
    }
}
