package com.example.fluffydisco.data.transcription

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.fluffydisco.data.local.FluffyDiscoDatabase
import kotlinx.coroutines.CancellationException
import java.io.IOException

class TranscriptionWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {

    companion object {
        const val KEY_RECORDING_ID = "recording_id"
        private const val TAG = "TranscriptionWorker"
        private const val MAX_ATTEMPTS = 3
    }

    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_RECORDING_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return Result.failure()

        return try {
            TranscriptionProcessor(applicationContext)
                .process(recordingId)

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Log.w(TAG, "Retrying $recordingId", e)
                Result.retry()
            } else {
                markFailed(recordingId)
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed: $recordingId", e)
            markFailed(recordingId)
            Result.failure()
        }
    }

    private suspend fun markFailed(recordingId: String) {
        FluffyDiscoDatabase
            .getInstance(applicationContext)
            .recordingDao()
            .updateTranscription(
                id = recordingId,
                status = "FAILED",
                transcriptUri = null
            )
    }
}