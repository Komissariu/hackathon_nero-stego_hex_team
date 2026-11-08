package com.example.fluffydisco.data.diarization

import androidx.core.net.toUri
import com.example.fluffydisco.data.transcription.TranscriptionEngine
import com.example.fluffydisco.domain.model.TranscriptSegment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Experimental speaker-aware ASR: use global diarization to decide which audio
 * ranges to recognize. Unlike aligning whole 30s ASR chunks after the fact, this
 * preserves separate text output for separate speaker turns.
 *
 * Note: current ASR engines recreate ONNX sessions per transcribe() call. This
 * version prioritizes correctness of attribution; reuse sessions before release.
 */
class SpeakerAwareAsrCoordinator(
    private val engine: TranscriptionEngine,
    private val cacheDirectory: File,
    private val planner: SpeakerTurnPlanner = SpeakerTurnPlanner()
) {
    suspend fun transcribe(
        sourceWav: File,
        diarization: DiarizationResult
    ): List<TranscriptSegment> {
        val totalSamples = Pcm16WavFile.open(sourceWav).use { it.sampleCount }
        val ranges = planner.plan(
            diarization.speechIntervals,
            diarization.turns,
            totalSamples
        )
        val output = ArrayList<TranscriptSegment>()
        PcmTurnWavWriter(sourceWav).use { writer ->
            for (range in ranges) {
                currentCoroutineContext().ensureActive()
                val temporary = File.createTempFile("speaker-turn-", ".wav", cacheDirectory)
                try {
                    writer.write(range.interval, temporary)
                    val chunkDurationMs = range.interval.durationSamples * 1000L / SAMPLE_RATE
                    val offsetMs = range.interval.startSample * 1000L / SAMPLE_RATE
                    engine.transcribe(temporary.toUri()).forEach { part ->
                        if (part.text.isBlank()) return@forEach
                        // ASR timestamps are local to the extracted WAV, not the recording.
                        val localStart = part.startMs.coerceIn(0L, chunkDurationMs)
                        val localEnd = part.endMs.coerceIn(0L, chunkDurationMs)
                        if (localEnd <= localStart) return@forEach
                        output += part.copy(
                            startMs = offsetMs + localStart,
                            endMs = offsetMs + localEnd,
                            speakerId = range.speakerId
                        )
                    }
                } finally {
                    temporary.delete()
                }
            }
        }
        return output.sortedWith(compareBy<TranscriptSegment> { it.startMs }.thenBy { it.endMs })
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
