package com.example.fluffydisco.navigation

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.navigation.compose.*
import com.example.fluffydisco.R
import com.example.fluffydisco.ui.history.HistoryScreen
import com.example.fluffydisco.ui.recorder.RecorderScreen
import com.example.fluffydisco.ui.settings.StorageSettingsScreen
import com.example.fluffydisco.ui.transcript.TranscriptScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.fluffydisco.service.RecordingService
import com.example.fluffydisco.service.RecordingState
import com.example.fluffydisco.service.RecordingStatus
import com.example.fluffydisco.data.audio.AudioImportProcessor
import com.example.fluffydisco.ui.settings.ModelDownloadsScreen
import com.example.fluffydisco.ui.settings.ModelSelectionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private val SUPPORTED_AUDIO_TYPES = setOf(
    "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/mpeg"
)

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val importProcessor = remember(context) {
        AudioImportProcessor(context.applicationContext)
    }

    var isImporting by remember {
        mutableStateOf(false)
    }

    val importSuccessMessage =
        stringResource(R.string.import_success)

    val importErrorMessage =
        stringResource(R.string.import_error)

    val importUnsupportedMessage =
        stringResource(R.string.import_unsupported)

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val snackbarHostState = remember { SnackbarHostState() }
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && !isImporting &&
            RecordingState.status.value == RecordingStatus.IDLE
        ) {
            isImporting = true
            scope.launch {
                try {
                    val supported = withContext(Dispatchers.IO) {
                        val mimeType = context.contentResolver.getType(uri)
                        val fileName = context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null, null, null
                        )?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) {
                                cursor.getString(index)
                            } else null
                        }
                        mimeType in SUPPORTED_AUDIO_TYPES ||
                                fileName?.let {
                                    it.endsWith(".m4a", ignoreCase = true) ||
                                            it.endsWith(".mp3", ignoreCase = true)
                                } == true
                    }

                    if (!supported) {
                        snackbarHostState.showSnackbar(importUnsupportedMessage)
                    } else {
                        withContext(Dispatchers.IO) {
                            importProcessor.importAudio(uri)
                        }
                        snackbarHostState.showSnackbar(importSuccessMessage)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("FluffyDisco", "Audio import failed", e)
                    snackbarHostState.showSnackbar(importErrorMessage)
                } finally {
                    isImporting = false
                }
            }
        }
    }

    val permissionMessage =
        stringResource(R.string.recorder_permission_required)

    val status by RecordingState.status.collectAsState()
    val startedAt by RecordingState.startedAt.collectAsState()

    val isRecording = status == RecordingStatus.RECORDING
    val isSaving = status == RecordingStatus.SAVING

    var elapsedSeconds by remember {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(status, startedAt) {
        if (isRecording) {
            while (true) {
                elapsedSeconds =
                    (SystemClock.elapsedRealtime() - startedAt) / 1000
                delay(250.milliseconds)
            }
        } else if (status == RecordingStatus.IDLE) {
            elapsedSeconds = 0L
        }
    }

    fun startServiceRecording() {
        val intent = Intent(
            context,
            RecordingService::class.java
        ).apply {
            action = RecordingService.ACTION_START
        }

        ContextCompat.startForegroundService(context, intent)
    }

    fun stopServiceRecording() {
        val intent = Intent(
            context,
            RecordingService::class.java
        ).apply {
            action = RecordingService.ACTION_STOP
        }

        context.startService(intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startServiceRecording()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(permissionMessage)
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState)
        },
        bottomBar = {
            if (
                currentRoute == "recorder" ||
                currentRoute == "history" ||
                currentRoute == "settings"
            ) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == "recorder",
                        onClick = {
                            navController.navigate("recorder") {
                                popUpTo("recorder")
                                launchSingleTop = true
                            }
                        },
                        icon = {
                            Icon(Icons.Default.Mic, null)
                        },
                        label = {
                            Text(stringResource(R.string.nav_record))
                        }
                    )

                    NavigationBarItem(
                        selected = currentRoute == "history",
                        onClick = {
                            navController.navigate("history") {
                                popUpTo("recorder")
                                launchSingleTop = true
                            }
                        },
                        icon = {
                            Icon(Icons.AutoMirrored.Filled.List, null)
                        },
                        label = {
                            Text(stringResource(R.string.nav_history))
                        }
                    )

                    NavigationBarItem(
                        selected = currentRoute == "settings",
                        onClick = {
                            navController.navigate("settings") {
                                popUpTo("recorder")
                                launchSingleTop = true
                            }
                        },
                        icon = {
                            Icon(Icons.Default.Settings, null)
                        },
                        label = {
                            Text(stringResource(R.string.nav_settings))
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "recorder",
            modifier = Modifier.padding(padding)
        ) {
            composable("recorder") {
                RecorderScreen(
                    isRecording = isRecording,
                    elapsedSeconds = elapsedSeconds,
                    isSaving = isSaving || isImporting,
                    onRecordClick = {
                        if (isRecording) {
                            stopServiceRecording()
                        } else if (!isSaving && !isImporting) {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            if (granted) {
                                startServiceRecording()
                            } else {
                                permissionLauncher.launch(
                                    Manifest.permission.RECORD_AUDIO
                                )
                            }
                        }
                    },
                    onImportClick = {
                        if (!isRecording && !isSaving && !isImporting) {
                            audioPicker.launch(arrayOf("*/*"))
                        }
                    }
                )
            }

            composable("history") {
                HistoryScreen { id ->
                    navController.navigate("transcript/$id")
                }
            }

            composable("settings") {
                StorageSettingsScreen(
                    onOpenModelSelection = {
                        navController.navigate("model_selection")
                    },
                    onOpenModelDownloads = {
                        navController.navigate("model_downloads")
                    }
                )
            }
            composable("model_selection") {
                ModelSelectionScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable("model_downloads") {
                ModelDownloadsScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable("transcript/{id}") { entry ->
                val id = entry.arguments
                    ?.getString("id")
                    .orEmpty()

                TranscriptScreen(
                    recordingId = id,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}