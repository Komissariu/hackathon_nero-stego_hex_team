package com.example.fluffydisco.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.fluffydisco.R
import com.example.fluffydisco.data.transcription.models.ModelDownloadState
import com.example.fluffydisco.data.transcription.models.ModelInfo
import com.example.fluffydisco.data.transcription.models.ModelInstallError
import com.example.fluffydisco.data.transcription.models.ModelInstallException
import com.example.fluffydisco.data.transcription.models.ModelInstallStage
import com.example.fluffydisco.data.transcription.models.ModelRepository
import com.example.fluffydisco.data.transcription.models.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) {
        ModelRepository(context.applicationContext)
    }
    val models = remember { repository.getModels() }

    var selectedModelId by remember {
        mutableStateOf(repository.getSelectedModelId())
    }
    var installStates by remember {
        mutableStateOf<Map<String, ModelDownloadState>>(emptyMap())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_model_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                R.string.settings_back_content_description
                            )
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_model_selection_description),
                style = MaterialTheme.typography.bodyMedium
            )

            models.forEach { model ->
                val state = installStates[model.id]
                    ?: ModelDownloadState(
                        modelId = model.id,
                        status = repository.getModelStatus(model.id)
                    )

                ModelSelectionCard(
                    model = model,
                    selected = selectedModelId == model.id,
                    state = state,
                    onSelect = {
                        repository.selectModel(model.id)
                        selectedModelId = model.id
                    },
                    onInstall = {
                        installStates = installStates + (
                                model.id to ModelDownloadState(
                                    modelId = model.id,
                                    status = ModelStatus.INSTALLING,
                                    stage = ModelInstallStage.COPYING
                                )
                                )
                        scope.launch {
                            try {
                                repository.installBundledModel(model.id) { update ->
                                    scope.launch(Dispatchers.Main.immediate) {
                                        installStates = installStates + (model.id to update)
                                    }
                                }
                                installStates = installStates + (
                                        model.id to ModelDownloadState(
                                            modelId = model.id,
                                            status = ModelStatus.INSTALLED,
                                            progress = 1f
                                        )
                                        )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: ModelInstallException) {
                                installStates = installStates + (
                                        model.id to ModelDownloadState(
                                            modelId = model.id,
                                            status = ModelStatus.ERROR,
                                            error = e.reason.name
                                        )
                                        )
                            } catch (_: Exception) {
                                installStates = installStates + (
                                        model.id to ModelDownloadState(
                                            modelId = model.id,
                                            status = ModelStatus.ERROR,
                                            error = ModelInstallError.STORAGE_ERROR.name
                                        )
                                        )
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelSelectionCard(
    model: ModelInfo,
    selected: Boolean,
    state: ModelDownloadState,
    onSelect: () -> Unit,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected && state.status == ModelStatus.INSTALLED) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (selected && state.status == ModelStatus.INSTALLED) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.settings_model_selected)
                    )
                }
            }

            when (state.status) {
                ModelStatus.INSTALLED -> {
                    Text(
                        text = stringResource(R.string.settings_model_installed),
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (!selected) {
                        Button(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.settings_model_choose))
                        }
                    } else {
                        Text(stringResource(R.string.settings_model_selected))
                    }
                }

                ModelStatus.INSTALLING -> {
                    Text(
                        text = installStageLabel(state.stage),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (state.progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                ModelStatus.READY_TO_INSTALL -> {
                    Text(stringResource(R.string.settings_model_ready_to_install))
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_model_install))
                    }
                }

                ModelStatus.CORRUPTED -> {
                    Text(
                        text = stringResource(R.string.settings_model_corrupted),
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_model_retry))
                    }
                }

                ModelStatus.ERROR -> {
                    Text(
                        text = installErrorLabel(state.error),
                        color = MaterialTheme.colorScheme.error
                    )
                    if (model.bundledArchive != null) {
                        Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.settings_model_retry))
                        }
                    }
                }

                ModelStatus.INCOMPATIBLE -> {
                    Text(
                        text = stringResource(R.string.settings_model_incompatible),
                        color = MaterialTheme.colorScheme.error
                    )
                }

                else -> Text(stringResource(R.string.settings_model_not_bundled))
            }
        }
    }
}

@Composable
private fun installStageLabel(stage: ModelInstallStage?): String {
    return when (stage) {
        ModelInstallStage.COPYING -> stringResource(R.string.settings_model_stage_copying)
        ModelInstallStage.VERIFYING -> stringResource(R.string.settings_model_stage_verifying)
        ModelInstallStage.EXTRACTING -> stringResource(R.string.settings_model_stage_extracting)
        ModelInstallStage.VALIDATING -> stringResource(R.string.settings_model_stage_validating)
        ModelInstallStage.FINALIZING -> stringResource(R.string.settings_model_stage_finalizing)
        null -> stringResource(R.string.settings_model_installing)
    }
}

@Composable
private fun installErrorLabel(error: String?): String {
    return when (error) {
        ModelInstallError.ARCHIVE_MISSING.name,
        ModelInstallError.ARCHIVE_NOT_BUNDLED.name ->
            stringResource(R.string.settings_model_install_error_missing)

        ModelInstallError.INSUFFICIENT_STORAGE.name ->
            stringResource(R.string.settings_model_install_error_space)

        ModelInstallError.CHECKSUM_MISMATCH.name ->
            stringResource(R.string.settings_model_install_error_checksum)

        ModelInstallError.INVALID_ARCHIVE.name ->
            stringResource(R.string.settings_model_install_error_archive)

        ModelInstallError.REQUIRED_FILES_MISSING.name ->
            stringResource(R.string.settings_model_install_error_files)

        ModelInstallError.INVALID_METADATA.name ->
            stringResource(R.string.settings_model_install_error_metadata)

        else -> stringResource(R.string.settings_model_install_error_storage)
    }
}
