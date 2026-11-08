package com.example.fluffydisco.data.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class GigaAmCtcDecoderTest {

    @Test
    fun collapsesRepeatsButAllowsTheSameTokenAfterBlank() {
        val decoder = GigaAmCtcDecoder.fromTokens(
            listOf("unknown", "a", "b", "<blk>")
        )

        assertEquals(
            "aabb",
            decoder.decode(
                listOf(1, 1, 3, 1, 2, 2, 3, 2).map(::frame).toTypedArray()
            )
        )
    }

    @Test
    fun replacesSentencePieceBoundariesWithReadableSpaces() {
        val decoder = GigaAmCtcDecoder.fromTokens(
            listOf("<unk>", "\u2581привет", "\u2581мир", "<blk>")
        )

        assertEquals(
            "привет мир",
            decoder.decode(listOf(1, 2).map(::frame).toTypedArray())
        )
    }

    private fun frame(tokenId: Int): FloatArray =
        FloatArray(4) { index -> if (index == tokenId) 0f else -10f }
}
