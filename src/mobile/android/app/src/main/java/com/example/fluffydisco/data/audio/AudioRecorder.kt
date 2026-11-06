package com.example.fluffydisco.data.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(
    private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start() {
        check(recorder == null) { "Already recording" }

        val directory = File(context.cacheDir, "recordings")
        directory.mkdirs()

        val file = File.createTempFile(
            "recording_",
            ".m4a",
            directory
        )

        val mediaRecorder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

        try {
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = mediaRecorder
            outputFile = file
        } catch (e: Exception) {
            mediaRecorder.release()
            file.delete()
            throw e
        }
    }

    fun stop(): File {
        val activeRecorder =
            recorder ?: error("No active recording")

        recorder = null

        val file = outputFile
            ?: error("Missing output file")

        outputFile = null

        try {
            activeRecorder.stop()
            return file
        } catch (e: RuntimeException) {
            file.delete()
            throw e
        } finally {
            activeRecorder.release()
        }
    }

    fun cancel() {
        val activeRecorder = recorder ?: return
        recorder = null

        try {
            activeRecorder.stop()
        } catch (_: RuntimeException) {
            // A very short recording may not contain valid audio.
        } finally {
            activeRecorder.release()
            outputFile?.delete()
            outputFile = null
        }
    }
}