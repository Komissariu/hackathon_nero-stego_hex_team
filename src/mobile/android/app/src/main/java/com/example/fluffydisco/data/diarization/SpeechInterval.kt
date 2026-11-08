package com.example.fluffydisco.data.diarization

data class SpeechInterval(
    val startSample: Long,
    val endSample: Long
) {
    init {
        require(startSample >= 0L)
        require(endSample > startSample)
    }

    val durationSamples: Long
        get() = endSample - startSample

    val centerSample: Long
        get() = startSample + durationSamples / 2
}

data class SpeakerObservation(
    val interval: SpeechInterval,
    val embedding: SpeakerEmbedding?,
    val confidence: Float
) {
    init {
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

data class SpeakerTurn(
    val interval: SpeechInterval,
    val speakerId: String,
    val confidence: Float
) {
    init {
        require(speakerId.isNotBlank())
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

const val UNKNOWN_SPEAKER_ID = "speaker_unknown"
