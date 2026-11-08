package com.example.fluffydisco.data.diarization

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerClusteringTest {

    @Test
    fun globallyGroupsReturningSpeakersAndNamesByFirstAppearance() {
        val turns = cluster(
            observation(0, 100, floatArrayOf(1f, 0f)),
            observation(200, 300, floatArrayOf(0f, 1f)),
            observation(400, 500, floatArrayOf(0.99f, 0.01f)),
            observation(600, 700, floatArrayOf(0.01f, 0.99f))
        )

        assertEquals(
            listOf("speaker_1", "speaker_2", "speaker_1", "speaker_2"),
            turns.map { it.speakerId }
        )
    }

    @Test
    fun globallyGroupsAtoBtoCtoAWithStableFirstAppearanceIds() {
        val turns = cluster(
            observation(0, 100, floatArrayOf(1f, 0f, 0f)),
            observation(200, 300, floatArrayOf(0f, 1f, 0f)),
            observation(400, 500, floatArrayOf(0f, 0f, 1f)),
            observation(600, 700, floatArrayOf(0.99f, 0.01f, 0f))
        )

        assertEquals(
            listOf("speaker_1", "speaker_2", "speaker_3", "speaker_1"),
            turns.map { it.speakerId }
        )
    }

    @Test
    fun findsCloseClustersAfterAnInitiallyDistantFirstPair() {
        val turns = cluster(
            observation(0, 100, floatArrayOf(-1f, 0f)),
            observation(200, 300, floatArrayOf(1f, 0f)),
            observation(400, 500, floatArrayOf(0f, 1f)),
            observation(600, 700, floatArrayOf(0.01f, 0.99f))
        )

        assertEquals(
            listOf("speaker_1", "speaker_2", "speaker_3", "speaker_3"),
            turns.map { it.speakerId }
        )
    }

    @Test
    fun shortUncertainTurnDoesNotCreateSpeakerAndCanAttachGlobally() {
        val turns = cluster(
            observation(0, 100, floatArrayOf(1f, 0f)),
            observation(200, 220, floatArrayOf(0.99f, 0.01f)),
            observation(300, 400, floatArrayOf(0f, 1f)),
            observation(500, 600, floatArrayOf(0.01f, 0.99f))
        )

        assertEquals("speaker_1", turns[0].speakerId)
        assertEquals("speaker_1", turns[1].speakerId)
        assertEquals("speaker_2", turns[2].speakerId)
        assertEquals("speaker_2", turns[3].speakerId)
    }

    @Test
    fun rejectsUnmatchedShortTurnInsteadOfInventingSpeaker() {
        val turns = cluster(
            observation(0, 100, floatArrayOf(1f, 0f)),
            observation(200, 220, floatArrayOf(-1f, 0f))
        )

        assertEquals(listOf("speaker_1", UNKNOWN_SPEAKER_ID), turns.map { it.speakerId })
    }

    @Test
    fun noReliableEmbeddingProducesUnknownRatherThanSpeakerOne() {
        val turns = cluster(
            SpeakerObservation(SpeechInterval(0, 50), null, 0f)
        )

        assertEquals(UNKNOWN_SPEAKER_ID, turns.single().speakerId)
    }

    @Test
    fun splitBoundaryBetweenOverlappingWindowsAndMergeSameSpeakerWindows() {
        val turns = cluster(
            observation(0, 100, floatArrayOf(1f, 0f)),
            observation(50, 150, floatArrayOf(1f, 0f)),
            observation(100, 200, floatArrayOf(0f, 1f))
        )

        assertEquals(2, turns.size)
        assertEquals(125L, turns[0].interval.endSample)
        assertEquals(125L, turns[1].interval.startSample)
    }

    private fun cluster(vararg observations: SpeakerObservation): List<SpeakerTurn> =
        SpeakerClustering(
            SpeakerClusteringConfig(
                maxAverageCosineDistance = 0.1f,
                minReliableDurationSamples = 80,
                minEmbeddingConfidence = 0.5f,
                maxMergeGapSamples = 0
            )
        ).cluster(observations.toList())

    private fun observation(
        start: Long,
        end: Long,
        embedding: FloatArray
    ) = SpeakerObservation(
        interval = SpeechInterval(start, end),
        embedding = SpeakerEmbedding.from(embedding),
        confidence = 1f
    )
}
