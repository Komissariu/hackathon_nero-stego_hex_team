package com.example.fluffydisco.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordingStatus {
    IDLE,
    RECORDING,
    SAVING
}

object RecordingState {
    private val _status = MutableStateFlow(RecordingStatus.IDLE)
    val status = _status.asStateFlow()

    private val _startedAt = MutableStateFlow(0L)
    val startedAt = _startedAt.asStateFlow()

    internal fun update(
        status: RecordingStatus,
        startedAt: Long = 0L
    ) {
        _startedAt.value = startedAt
        _status.value = status
    }
}