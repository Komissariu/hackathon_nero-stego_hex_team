package com.example.fluffydisco.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey
    val id: String,

    val title: String,
    val createdAt: Long,
    val durationMs: Long,

    val audioUri: String,
    val transcriptUri: String? = null,

    val status: String = "RECORDED"
)