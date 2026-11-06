package com.example.fluffydisco.data.transcription

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class TranscriptionScheduler(context: Context) {

    private val workManager =
        WorkManager.getInstance(context.applicationContext)

    fun enqueue(recordingId: String) {
        val input = Data.Builder()
            .putString(
                TranscriptionWorker.KEY_RECORDING_ID,
                recordingId
            )
            .build()

        val request =
            OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(input)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .addTag("transcription")
                .addTag("recording:$recordingId")
                .build()

        workManager.enqueueUniqueWork(
            "transcription:$recordingId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}