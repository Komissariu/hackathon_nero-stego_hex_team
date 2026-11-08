package com.example.fluffydisco.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity)

    @Query(
        """
        UPDATE recordings
        SET status = :status,
            transcriptUri = :transcriptUri
        WHERE id = :id
    """
    )
    suspend fun updateTranscription(
        id: String,
        status: String,
        transcriptUri: String?
    )

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)
}