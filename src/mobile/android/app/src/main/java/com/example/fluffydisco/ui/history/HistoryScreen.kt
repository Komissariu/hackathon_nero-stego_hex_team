package com.example.fluffydisco.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.fluffydisco.R
import com.example.fluffydisco.data.local.FluffyDiscoDatabase
import com.example.fluffydisco.data.local.RecordingEntity
import java.text.DateFormat
import java.util.Date
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import com.example.fluffydisco.data.audio.AudioPlaybackController
import com.example.fluffydisco.data.audio.PlaybackStatus
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun HistoryScreen(
    onRecordingClick: (String) -> Unit
) {
    val context = LocalContext.current

    val playbackController = remember(context) {
        AudioPlaybackController(context.applicationContext)
    }

    DisposableEffect(playbackController) {
        onDispose {
            playbackController.release()
        }
    }

    val playbackErrorText =
        stringResource(R.string.recording_playback_error)

    val dao = remember(context) {
        FluffyDiscoDatabase
            .getInstance(context.applicationContext)
            .recordingDao()
    }
    val scope = rememberCoroutineScope()

    var recordingToRename by remember {
        mutableStateOf<RecordingEntity?>(null)
    }
    val recordings by remember(dao) {
        dao.observeAll()
    }.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = stringResource(R.string.history_title),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.history_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        if (recordings.isEmpty()) {
            Text(
                text = stringResource(R.string.history_empty_title),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(
                    R.string.history_empty_description
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = recordings,
                    key = { it.id }
                ) { recording ->
                    RecordingCard(
                        recording = recording,
                        onClick = {
                            onRecordingClick(recording.id)
                        },
                        onRename = {
                            recordingToRename = recording
                        },
                        isPlaying =
                            playbackController.activeRecordingId == recording.id &&
                                    playbackController.status == PlaybackStatus.PLAYING,
                        isLoading =
                            playbackController.activeRecordingId == recording.id &&
                                    playbackController.status == PlaybackStatus.LOADING,
                        onPlayClick = {
                            playbackController.toggle(
                                recordingId = recording.id,
                                audioUri = recording.audioUri,
                                onError = {
                                    Toast.makeText(
                                        context,
                                        playbackErrorText,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    )
                }
            }
            recordingToRename?.let { recording ->
                RenameRecordingDialog(
                    initialName = recording.title,
                    onDismiss = {
                        recordingToRename = null
                    },
                    onConfirm = { newName ->
                        scope.launch {
                            dao.rename(recording.id, newName)
                        }
                        recordingToRename = null
                    }
                )
            }
        }
    }
}

@Composable
private fun RecordingCard(
    recording: RecordingEntity,
    onClick: () -> Unit,
    onRename: () -> Unit,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = recording.title,
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(6.dp))

                val date = remember(recording.createdAt) {
                    DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT
                    ).format(Date(recording.createdAt))
                }

                val seconds = recording.durationMs / 1000

                Text(
                    text = "$date · %02d:%02d".format(
                        seconds / 60,
                        seconds % 60
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))

                val statusText = when (recording.status) {
                    "RECORDED" -> stringResource(R.string.status_recorded)
                    "PROCESSING" -> stringResource(R.string.status_processing)
                    "TRANSCRIBED" -> stringResource(R.string.status_transcribed)
                    "FAILED" -> stringResource(R.string.status_failed)
                    "QUEUED" -> stringResource(R.string.status_queued)
                    else -> recording.status
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // Кнопка воспроизведения аудио
            IconButton(
                onClick = onPlayClick,
                enabled = !isLoading
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    }

                    isPlaying -> {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = stringResource(
                                R.string.recording_pause
                            ),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    else -> {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(
                                R.string.recording_play
                            ),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            var menuExpanded by remember {
                mutableStateOf(false)
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true }
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(
                            R.string.recording_more_options
                        )
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.recording_rename))
                        },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameRecordingDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) {
        mutableStateOf(initialName)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.recording_rename_title))
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = {
                    Text(stringResource(R.string.recording_name))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(name.trim())
                },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}