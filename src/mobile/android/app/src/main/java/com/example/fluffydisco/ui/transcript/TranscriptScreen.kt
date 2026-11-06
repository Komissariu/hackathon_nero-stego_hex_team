package com.example.fluffydisco.ui.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.fluffydisco.R
import com.example.fluffydisco.data.local.FluffyDiscoDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

@Composable
fun TranscriptScreen(
    recordingId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val dao = remember(context) {
        FluffyDiscoDatabase
            .getInstance(context.applicationContext)
            .recordingDao()
    }

    val recording by remember(dao, recordingId) {
        dao.observeById(recordingId)
    }.collectAsState(initial = null)

    var transcriptText by remember(recordingId) {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(recording?.transcriptUri) {
        transcriptText = null

        val uriString = recording?.transcriptUri
            ?: return@LaunchedEffect

        transcriptText = withContext(Dispatchers.IO) {
            try {
                context.contentResolver
                    .openInputStream(uriString.toUri())
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            } catch (_: Exception) {
                null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.navigate_back)
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = recording?.title
                ?: stringResource(R.string.transcript_title),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(16.dp))

        if (recording?.status == "TRANSCRIBED") {
            Text(
                text = stringResource(R.string.transcript_demo_warning),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelMedium
            )
        }

        Spacer(Modifier.height(16.dp))

        val displayText = when {
            recording == null ->
                stringResource(R.string.transcript_missing)

            transcriptText != null ->
                transcriptText!!

            else ->
                stringResource(R.string.transcript_not_ready)
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = displayText,
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}