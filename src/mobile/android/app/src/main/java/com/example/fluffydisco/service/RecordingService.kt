
package com.example.fluffydisco.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.fluffydisco.MainActivity
import com.example.fluffydisco.R
import com.example.fluffydisco.data.audio.AudioRecorder
import com.example.fluffydisco.data.storage.RecordingRepository
import kotlinx.coroutines.*
import java.io.File
import com.example.fluffydisco.data.local.FluffyDiscoDatabase
import com.example.fluffydisco.data.local.RecordingEntity
import com.example.fluffydisco.data.transcription.TranscriptionScheduler
import java.util.UUID

class RecordingService : Service() {
    private var recordingStartedAt = 0L
    companion object {
        const val ACTION_START =
            "com.example.fluffydisco.START_RECORDING"
        const val ACTION_STOP =
            "com.example.fluffydisco.STOP_RECORDING"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate
    )

    private lateinit var recorder: AudioRecorder
    private lateinit var repository: RecordingRepository

    private var active = false
    private var saving = false

    override fun onCreate() {
        super.onCreate()

        recorder = AudioRecorder(applicationContext)
        repository = RecordingRepository(applicationContext)

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }

        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (active || saving) return

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )

            recorder.start()
            recordingStartedAt = SystemClock.elapsedRealtime()
            active = true
            RecordingState.update(
                RecordingStatus.RECORDING,
                SystemClock.elapsedRealtime()
            )
        } catch (e: Exception) {
            android.util.Log.e(
                "RecordingService",
                "Cannot start recording",
                e
            )
            recorder.cancel()
            RecordingState.update(RecordingStatus.IDLE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!active || saving) return

        active = false
        saving = true

        val durationMs =
            SystemClock.elapsedRealtime() - recordingStartedAt

        RecordingState.update(RecordingStatus.SAVING)

        serviceScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    recorder.stop()
                }

                val audioUri = repository.save(file)

                val recordingId = UUID.randomUUID().toString()

                val recording = RecordingEntity(
                    id = recordingId,
                    title = "Recording ${System.currentTimeMillis()}",
                    createdAt = System.currentTimeMillis(),
                    durationMs = durationMs,
                    audioUri = audioUri.toString(),
                    status = "RECORDED"
                )

                val dao = FluffyDiscoDatabase
                    .getInstance(applicationContext)
                    .recordingDao()

                dao.insert(recording)

                // TODO: Show transcription queue state in the recording details UI.
                TranscriptionScheduler(applicationContext)
                    .enqueue(recordingId)

            } catch (e: Exception) {
                android.util.Log.e(
                    "RecordingService",
                    "Cannot save recording",
                    e
                )
            } finally {
                saving = false
                RecordingState.update(RecordingStatus.IDLE)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(
            NotificationManager::class.java
        )

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )

        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(
            this,
            MainActivity::class.java
        )

        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(
            this,
            RecordingService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(
                getString(R.string.notification_recording_title)
            )
            .setContentText(
                getString(R.string.notification_recording_text)
            )
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_stat_recording,
                getString(R.string.notification_stop),
                stopPendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        if (active) {
            recorder.cancel()
        }

        serviceScope.cancel()
        RecordingState.update(RecordingStatus.IDLE)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
