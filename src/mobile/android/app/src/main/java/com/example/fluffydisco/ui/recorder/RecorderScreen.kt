package com.example.fluffydisco.ui.recorder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fluffydisco.R
import com.example.fluffydisco.ui.theme.AppRecording

private fun formatDuration(seconds: Long): String {
    return "%02d:%02d".format(
        seconds / 60,
        seconds % 60
    )
}

@Composable
fun RecorderScreen(
    isRecording: Boolean,
    elapsedSeconds: Long,
    isSaving: Boolean,
    onRecordClick: () -> Unit,
    onImportClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.recorder_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.recorder_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(1f))

        WaveformPlaceholder()

        Spacer(Modifier.height(28.dp))

        Text(
            text = formatDuration(elapsedSeconds),
            fontSize = 58.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(
                if (isRecording)
                    R.string.recorder_status_recording
                else
                    R.string.recorder_status_idle
            ),
            style = MaterialTheme.typography.labelLarge,
            color = if (isRecording)
                AppRecording
            else
                MaterialTheme.colorScheme.secondary
        )

        Spacer(Modifier.height(36.dp))

        FilledIconButton(
            onClick = onRecordClick,
            enabled = !isSaving,
            modifier = Modifier.size(104.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isRecording)
                    AppRecording
                else
                    MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = if (isRecording)
                    Icons.Default.Stop
                else
                    Icons.Default.Mic,
                contentDescription = stringResource(
                    if (isRecording)
                        R.string.recorder_stop
                    else
                        R.string.recorder_start
                ),
                modifier = Modifier.size(42.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(
                if (isRecording)
                    R.string.recorder_recording
                else
                    R.string.recorder_hint
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = onImportClick,
            enabled = !isRecording && !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(
                Icons.Default.UploadFile,
                contentDescription = null
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.recorder_import))
        }
    }
}

@Composable
private fun WaveformPlaceholder() {
    val heights = listOf(
        12, 20, 32, 18, 42, 55, 28,
        46, 68, 35, 22, 54, 72, 40,
        25, 58, 36, 18, 48, 30, 14
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        heights.forEach { height ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(4.dp)
                    .height(height.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
