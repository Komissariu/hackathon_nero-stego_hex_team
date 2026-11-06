package com.example.fluffydisco.data.transcription

import android.net.Uri
import com.example.fluffydisco.domain.model.TranscriptSegment
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class DemoTranscriptionEngine : TranscriptionEngine {

    override suspend fun transcribe(
        audioUri: Uri
    ): List<TranscriptSegment> {
        delay(8000.milliseconds)

        return listOf(
            TranscriptSegment(
                startMs = 0,
                endMs = 3000,
                speakerId = "speaker_1",
                text = "Это демонстрационная расшифровка FluffyDisco."
            ),
            TranscriptSegment(
                startMs = 3000,
                endMs = 6500,
                speakerId = "speaker_2",
                text = "Она пока не связана с содержимым аудиозаписи."
            ),
            TranscriptSegment(
                startMs = 6500,
                endMs = 10000,
                speakerId = "speaker_1",
                text = "Позже здесь будет результат локальной модели."
            )
        )
    }
}