package com.example.fluffydisco.domain.model

data class Recording(
    val id: String,
    val title: String,
    val date: String,
    val duration: String,
    val isTranscribed: Boolean
)