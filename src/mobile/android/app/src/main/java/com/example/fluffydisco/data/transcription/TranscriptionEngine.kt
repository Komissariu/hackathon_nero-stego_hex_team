package com.example.fluffydisco.data.transcription

import android.net.Uri
import com.example.fluffydisco.domain.model.TranscriptSegment

interface TranscriptionEngine {
    suspend fun transcribe(
        audioUri: Uri
    ): List<TranscriptSegment>
}