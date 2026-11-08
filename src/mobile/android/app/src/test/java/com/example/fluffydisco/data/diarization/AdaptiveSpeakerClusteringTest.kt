package com.example.fluffydisco.data.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSpeakerClusteringTest {
    private fun observation(start: Long, vector: FloatArray) = SpeakerObservation(
        SpeechInterval(start, start + 16_000L),
        SpeakerEmbedding.from(vector), 1f
    )

    private val config = SpeakerClusteringConfig(
        maxAverageCosineDistance = 0.5f,
        minReliableDurationSamples = 12_800L,
        minEmbeddingConfidence = 0.15f
    )

    @Test
    fun reunitesReturningVoiceWithoutKnowingSpeakerCount() {
        // A's returning voice has cosine 0.38 to A's first turn: the old
        // hard threshold (distance 0.62) would create speaker_3.
        val input = listOf(
            observation(0, floatArrayOf(1f, 0f, 0f)),
            observation(32_000, floatArrayOf(0f, 0f, 1f)),
            observation(64_000, floatArrayOf(0.38f, 0.925f, 0f))
        )
        val old = SpeakerClustering(config.copy(enableAdaptiveCut = false))
            .cluster(input).map { it.speakerId }
        val improved = SpeakerClustering(config).clusterWithDiagnostics(input)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_3"), old)
        assertEquals(
            listOf("speaker_1", "speaker_2", "speaker_1"),
            improved.turns.map { it.speakerId }
        )
        assertEquals(2, improved.diagnostics.detectedSpeakers)
        assertTrue(improved.diagnostics.adaptiveCutUsed)
        assertTrue(improved.diagnostics.selectedMaxDistance > 0.5f)
    }

    @Test
    fun doesNotForceTwoSpeakersWhenThreeVoicesAreClearlySeparated() {
        val input = listOf(
            observation(0, floatArrayOf(1f, 0f, 0f)),
            observation(32_000, floatArrayOf(0f, 1f, 0f)),
            observation(64_000, floatArrayOf(0f, 0f, 1f))
        )
        val result = SpeakerClustering(config).clusterWithDiagnostics(input)
        assertEquals(
            listOf("speaker_1", "speaker_2", "speaker_3"),
            result.turns.map { it.speakerId })
        assertEquals(3, result.diagnostics.detectedSpeakers)
        assertFalse(result.diagnostics.adaptiveCutUsed)
    }

    @Test
    fun supportsFourSpeakersWithoutSpeakerCountHint() {
        val input = listOf(
            observation(0, floatArrayOf(1f, 0f, 0f, 0f)),
            observation(32_000, floatArrayOf(0f, 1f, 0f, 0f)),
            observation(64_000, floatArrayOf(0f, 0f, 1f, 0f)),
            observation(96_000, floatArrayOf(0f, 0f, 0f, 1f))
        )
        val result = SpeakerClustering(config).clusterWithDiagnostics(input)
        assertEquals(4, result.diagnostics.detectedSpeakers)
        assertEquals(4, result.turns.map { it.speakerId }.distinct().size)
    }

    @Test
    fun aSingleSpeakerDoesNotGetSplit() {
        val input = listOf(
            observation(0, floatArrayOf(1f, 0f, 0f)),
            observation(32_000, floatArrayOf(0.99f, 0.05f, 0f)),
            observation(64_000, floatArrayOf(0.97f, 0.10f, 0f))
        )
        val result = SpeakerClustering(config).clusterWithDiagnostics(input)
        assertEquals(1, result.diagnostics.detectedSpeakers)
        assertTrue(result.turns.all { it.speakerId == "speaker_1" })
    }

    @Test
    fun noAcousticEvidenceStillProducesUnknown() {
        val result = SpeakerClustering(config).clusterWithDiagnostics(
            listOf(SpeakerObservation(SpeechInterval(0, 16000), null, 0f))
        )
        assertEquals(0, result.diagnostics.detectedSpeakers)
        assertEquals(UNKNOWN_SPEAKER_ID, result.turns.single().speakerId)
    }
}
