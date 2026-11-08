package com.example.fluffydisco.data.diarization

import com.example.fluffydisco.domain.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptSpeakerAlignerTest {

    @Test
    fun assignsSpeakerOnlyWhenOneTurnDominatesWithoutSplittingText() {
        val source = listOf(
            TranscriptSegment(1000, 2000, "speaker_1", "short utterance"),
            TranscriptSegment(1000, 4000, "speaker_1", "crosses a turn"),
            TranscriptSegment(5000, 6000, "speaker_1", "no diarized coverage")
        )
        val turns = listOf(
            SpeakerTurn(SpeechInterval(16_000, 32_000), "speaker_2", 1f),
            SpeakerTurn(SpeechInterval(32_000, 48_000), "speaker_1", 1f)
        )

        val aligned = TranscriptSpeakerAligner(0.8).align(source, turns, 16_000)

        assertEquals("speaker_2", aligned[0].speakerId)
        assertEquals(UNKNOWN_SPEAKER_ID, aligned[1].speakerId)
        assertEquals(UNKNOWN_SPEAKER_ID, aligned[2].speakerId)
        assertEquals(source.map { it.text }, aligned.map { it.text })
        assertEquals(source.map { it.startMs to it.endMs }, aligned.map { it.startMs to it.endMs })
    }

    @Test
    fun equalCoverageByDifferentSpeakersRemainsUnknown() {
        val aligned = TranscriptSpeakerAligner(0.5).align(
            listOf(TranscriptSegment(0, 2000, "speaker_1", "ambiguous")),
            listOf(
                SpeakerTurn(SpeechInterval(0, 16_000), "speaker_1", 1f),
                SpeakerTurn(SpeechInterval(16_000, 32_000), "speaker_2", 1f)
            ),
            sampleRate = 16_000
        )

        assertEquals(UNKNOWN_SPEAKER_ID, aligned.single().speakerId)
        assertEquals("ambiguous", aligned.single().text)
    }

    @Test
    fun thirtySecondAsrWindowWithMultipleSpeakersIsNotAssignedToDominantTurn() {
        val source = listOf(
            TranscriptSegment(0, 30_000, "speaker_1", "whole-window text")
        )
        val turns = listOf(
            SpeakerTurn(SpeechInterval(0, 440_000), "speaker_1", 1f),
            SpeakerTurn(SpeechInterval(440_000, 480_000), "speaker_2", 1f)
        )

        val result = TranscriptSpeakerAligner(0.8)
            .alignWithDiagnostics(source, turns, sampleRate = 16_000)

        assertEquals(1, result.segments.size)
        assertEquals(UNKNOWN_SPEAKER_ID, result.segments.single().speakerId)
        assertEquals(source.single().text, result.segments.single().text)
        assertEquals(source.single().startMs, result.segments.single().startMs)
        assertEquals(source.single().endMs, result.segments.single().endMs)
        assertEquals(1, result.diagnostics.asrSegmentCount)
        assertEquals(2, result.diagnostics.speakerTurnCount)
        assertEquals(1, result.diagnostics.segmentsOverlappingMultipleSpeakers)
        assertEquals(0, result.diagnostics.segmentsOverlappingUnknownTurns)
        assertEquals(1, result.diagnostics.unknownSegmentCount)
        assertEquals(emptyMap<String, Int>(), result.diagnostics.assignedSegmentsBySpeaker)
    }

    @Test
    fun adjacentTurnsForSameSpeakerCanStillAlignOneLongAsrWindow() {
        val result = TranscriptSpeakerAligner(0.8).alignWithDiagnostics(
            segments = listOf(TranscriptSegment(0, 30_000, "speaker_1", "same speaker")),
            turns = listOf(
                SpeakerTurn(SpeechInterval(0, 240_000), "speaker_1", 1f),
                SpeakerTurn(SpeechInterval(240_000, 480_000), "speaker_1", 1f)
            ),
            sampleRate = 16_000
        )

        assertEquals("speaker_1", result.segments.single().speakerId)
        assertEquals(0, result.diagnostics.segmentsOverlappingMultipleSpeakers)
        assertEquals(0, result.diagnostics.segmentsOverlappingUnknownTurns)
        assertEquals(mapOf("speaker_1" to 1), result.diagnostics.assignedSegmentsBySpeaker)
    }

    @Test
    fun asrSegmentOverlappingAnUnknownTurnRemainsUnknown() {
        val result = TranscriptSpeakerAligner(0.8).alignWithDiagnostics(
            segments = listOf(TranscriptSegment(0, 10_000, "speaker_1", "uncertain speaker")),
            turns = listOf(
                SpeakerTurn(SpeechInterval(0, 144_000), "speaker_1", 1f),
                SpeakerTurn(SpeechInterval(144_000, 160_000), UNKNOWN_SPEAKER_ID, 0.1f)
            ),
            sampleRate = 16_000
        )

        assertEquals(UNKNOWN_SPEAKER_ID, result.segments.single().speakerId)
        assertEquals(1, result.diagnostics.segmentsOverlappingUnknownTurns)
    }
}
