
package com.example.fluffydisco.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class PlaybackStatus {
    IDLE,
    LOADING,
    PLAYING,
    PAUSED
}

class AudioPlaybackController(
    private val context: Context
) {
    var activeRecordingId by mutableStateOf<String?>(null)
        private set

    var status by mutableStateOf(PlaybackStatus.IDLE)
        private set

    private var player: MediaPlayer? = null

    fun toggle(
        recordingId: String,
        audioUri: String,
        onError: () -> Unit
    ) {
        if (activeRecordingId == recordingId) {
            when (status) {
                PlaybackStatus.PLAYING -> {
                    try {
                        player?.pause()
                        status = PlaybackStatus.PAUSED
                    } catch (e: Exception) {
                        stop()
                        onError()
                    }
                    return
                }

                PlaybackStatus.PAUSED -> {
                    try {
                        player?.start()
                        status = PlaybackStatus.PLAYING
                    } catch (e: Exception) {
                        stop()
                        onError()
                    }
                    return
                }

                PlaybackStatus.LOADING -> return
                PlaybackStatus.IDLE -> Unit
            }
        }

        stop()

        val newPlayer = MediaPlayer()
        player = newPlayer
        activeRecordingId = recordingId
        status = PlaybackStatus.LOADING

        try {
            newPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    )
                    .build()
            )

            newPlayer.setOnPreparedListener { prepared ->
                if (player === prepared) {
                    try {
                        prepared.start()
                        status = PlaybackStatus.PLAYING
                    } catch (e: Exception) {
                        stop()
                        onError()
                    }
                }
            }

            newPlayer.setOnCompletionListener { completed ->
                if (player === completed) {
                    stop()
                }
            }

            newPlayer.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    stop()
                    onError()
                }
                true
            }

            newPlayer.setDataSource(
                context,
                Uri.parse(audioUri)
            )

            newPlayer.prepareAsync()
        } catch (e: Exception) {
            stop()
            onError()
        }
    }

    fun stop() {
        val oldPlayer = player
        player = null
        activeRecordingId = null
        status = PlaybackStatus.IDLE

        try {
            oldPlayer?.release()
        } catch (_: Exception) {
            // Player might already be in an invalid state.
        }
    }

    fun release() {
        stop()
    }

    // TODO: Add seek bar and playback position.
    // TODO: Handle audio focus and interruptions.
    // TODO: Migrate to Media3 for advanced playback features.
}
