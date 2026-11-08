package com.example.fluffydisco.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.fluffydisco.R
import com.example.fluffydisco.data.storage.StoragePreferences
import kotlinx.coroutines.launch

@Composable
fun StorageSettingsScreen(
    onOpenModelSelection: () -> Unit,
    onOpenModelDownloads: () -> Unit
) {
    val context = LocalContext.current

    val preferences = remember(context) {
        StoragePreferences(context.applicationContext)
    }

    val folderUri by preferences.folderUri.collectAsState(initial = null)
    val transcriptFolderUri by preferences.transcriptFolderUri.collectAsState(initial = null)

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorText = stringResource(R.string.settings_folder_error)

    // Audio folder picker
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                scope.launch {
                    preferences.setFolder(uri)
                }
            } catch (e: SecurityException) {
                scope.launch {
                    snackbarHostState.showSnackbar(errorText)
                }
            }
        }
    }

    // Transcript folder picker
    val transcriptFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                scope.launch {
                    preferences.setTranscriptFolder(uri)
                }
            } catch (e: SecurityException) {
                scope.launch {
                    snackbarHostState.showSnackbar(errorText)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState)
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {

            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.settings_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))

            // AUDIO RECORDINGS

            Text(
                text = stringResource(R.string.settings_audio_folder_title),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.settings_audio_folder_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(20.dp)
                ) {
                    Text(
                        text = if (folderUri == null) {
                            stringResource(R.string.settings_audio_default_folder)
                        } else {
                            stringResource(R.string.settings_custom_folder)
                        }
                    )

                    folderUri?.let {
                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = it.lastPathSegment ?: it.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_choose_folder))
            }

            if (folderUri != null) {
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            preferences.setFolder(null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_reset_folder))
                }
            }

            Spacer(Modifier.height(32.dp))

            // TRANSCRIPTS

            Text(
                text = stringResource(R.string.settings_transcript_folder_title),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.settings_transcript_folder_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(20.dp)
                ) {
                    Text(
                        text = if (transcriptFolderUri == null) {
                            stringResource(R.string.settings_transcript_default_folder)
                        } else {
                            stringResource(R.string.settings_custom_folder)
                        }
                    )

                    transcriptFolderUri?.let {
                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = it.lastPathSegment ?: it.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    transcriptFolderPicker.launch(null)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_choose_folder))
            }

            if (transcriptFolderUri != null) {
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            preferences.setTranscriptFolder(null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_reset_folder))
                }
            }

            // MODELS

            Spacer(Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.settings_models_title),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onOpenModelSelection,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null
                )

                Spacer(Modifier.width(8.dp))

                Text(stringResource(R.string.settings_model_selection_button))
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onOpenModelDownloads,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null
                )

                Spacer(Modifier.width(8.dp))

                Text(stringResource(R.string.settings_model_downloads_button))
            }
        }
    }
}
