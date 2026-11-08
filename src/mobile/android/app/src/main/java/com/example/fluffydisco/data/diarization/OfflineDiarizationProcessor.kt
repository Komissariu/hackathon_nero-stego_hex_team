package com.example.fluffydisco.data.diarization

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class OfflineDiarizationConfig(
    val vad: SileroVadConfig = SileroVadConfig(),
    val clustering: SpeakerClusteringConfig,
    val speakerChanges: SpeakerChangeConfig
)

data class DiarizationResult(
    val speechIntervals: List<SpeechInterval>,
    val observations: List<SpeakerObservation>,
    val changePoints: List<SpeakerChangePoint>,
    val turns: List<SpeakerTurn>,
    val clusteringDiagnostics: SpeakerClusteringDiagnostics? = null
)

class OfflineDiarizationProcessor(
    private val vadModel: File,
    private val speakerEmbeddingModel: File,
    private val config: OfflineDiarizationConfig
) {
    suspend fun process(wavFile: File): DiarizationResult = withContext(Dispatchers.IO) {
        Pcm16WavFile.open(wavFile).use { wav ->
            val speechIntervals = SileroVadOnnxEngine(vadModel, config.vad).use { vad ->
                vad.detect(wav)
            }
            val observations = SpeakerEmbeddingOnnxEngine(speakerEmbeddingModel).use { embedding ->
                embedding.embed(wav, speechIntervals)
            }
            val changePoints = SpeakerChangeDetector(config.speakerChanges)
                .detect(observations)
            val clustering = SpeakerClustering(config.clustering)
                .clusterWithDiagnostics(observations)
            val turns = SpeakerTurnRefiner(
                maxBoundaryAdjustmentSamples = SAMPLE_RATE / 2L,
                minTurnSamples = config.clustering.minReliableDurationSamples
            ).refine(clustering.turns, changePoints)
            DiarizationResult(
                speechIntervals, observations, changePoints, turns, clustering.diagnostics
            )
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
