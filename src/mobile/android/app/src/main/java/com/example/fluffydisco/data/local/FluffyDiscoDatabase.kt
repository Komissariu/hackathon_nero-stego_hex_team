package com.example.fluffydisco.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RecordingEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FluffyDiscoDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: FluffyDiscoDatabase? = null

        fun getInstance(context: Context): FluffyDiscoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FluffyDiscoDatabase::class.java,
                    "fluffydisco.db"
                ).build().also {
                    INSTANCE = it
                }
            }
        }
    }
}