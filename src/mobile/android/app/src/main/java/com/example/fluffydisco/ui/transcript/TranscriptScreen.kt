package com.example.fluffydisco.ui.transcript

import android.net.Uri
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
import androidx.core.net.toUri
import com.example.fluffydisco.R
import com.example.fluffydisco.data.local.FluffyDiscoDatabase
import com.example.fluffydisco.domain.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

    var segments by remember(recordingId) {
        mutableStateOf<List<TranscriptSegment>?>(null)
    }
    var loadError by remember(recordingId) {
        mutableStateOf(false)
    }

    LaunchedEffect(recording?.transcriptUri) {
        segments = null
        loadError = false

        val uriString = recording?.transcriptUri
            ?: return@LaunchedEffect

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val rawJson = context.contentResolver
                    .openInputStream(Uri.parse(uriString))
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: error("Transcript file is unavailable")

                val json = JSONObject(rawJson)
                val jsonSegments = json.getJSONArray("segments")

                List(jsonSegments.length()) { index ->
                    val item = jsonSegments.getJSONObject(index)

                    TranscriptSegment(
                        startMs = item.getLong("startMs"),
                        endMs = item.getLong("endMs"),
                        speakerId = item.optString(
                            "speakerId",
                            "Unknown"
                        ),
                        text = item.optString("text", "")
                    )
                }
            }
        }

        result.onSuccess {
            segments = it
        }.onFailure {
            loadError = true
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

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when {
                recording == null -> {
                    Text(
                        text = stringResource(R.string.transcript_missing),
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                loadError -> {
                    Text(
                        text = stringResource(R.string.transcript_file_missing),
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                segments == null -> {
                    Text(
                        text = stringResource(R.string.transcript_not_ready),
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                segments!!.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.transcript_empty),
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        segments!!.forEachIndexed { index, segment ->
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${formatTime(segment.startMs)} – " +
                                            "${formatTime(segment.endMs)} · " +
                                            segment.speakerId,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Text(
                                    text = segment.text,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }

                            if (index != segments!!.lastIndex) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val minutes = safeMs / 60_000
    val seconds = (safeMs / 1_000) % 60
    val milliseconds = safeMs % 1_000

    return "%02d:%02d.%03d".format(
        minutes,
        seconds,
        milliseconds
    )
}
