package com.example.fluffydisco.domain.model

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val speakerId: String,
    val text: String
)