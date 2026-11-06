package com.example.fluffydisco.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordingStatus {
    IDLE,
    RECORDING,
    SAVING
}

object RecordingState {
    const val MAX_SAMPLES = 48

    private val _status = MutableStateFlow(RecordingStatus.IDLE)
    val status = _status.asStateFlow()

    private val _startedAt = MutableStateFlow(0L)
    val startedAt = _startedAt.asStateFlow()

    private val _amplitudes = MutableStateFlow<List<Float>>(emptyList())
    val amplitudes = _amplitudes.asStateFlow()

    internal fun update(
        status: RecordingStatus,
        startedAt: Long = 0L
    ) {
        _startedAt.value = startedAt
        _status.value = status
    }

    internal fun resetAmplitudes() {
        _amplitudes.value = emptyList()
    }

    internal fun addAmplitude(amplitude: Int) {
        val normalized = (amplitude / 32767f).coerceIn(0f, 1f)

        val adjusted = kotlin.math.sqrt(normalized)

        val current = _amplitudes.value
        val previous = current.lastOrNull() ?: 0f
        val alpha = if (adjusted > previous) 0.6f else 0.15f
        val smoothed = alpha * adjusted + (1f - alpha) * previous

        _amplitudes.value = (current + smoothed).takeLast(MAX_SAMPLES)
    }
}