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
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.example.fluffydisco.service.RecordingState

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

        RecordingWaveform(
            isRecording = isRecording
        )

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
private fun RecordingWaveform(
    isRecording: Boolean
) {
    val amplitudes by RecordingState.amplitudes.collectAsState()

    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = activeColor.copy(alpha = 0.2f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
    ) {
        val centerY = size.height / 2f
        val slotCount = RecordingState.MAX_SAMPLES
        val slotWidth = size.width / slotCount
        val barWidth = slotWidth * 0.45f
        val minBarHeight = 3.dp.toPx()

        // Сдвиг вправо: сколько слотов ещё "не заполнено"
        val offset = slotCount - amplitudes.size

        repeat(slotCount) { i ->
            val sampleIndex = i - offset
            val value = if (sampleIndex >= 0) {
                amplitudes[sampleIndex].coerceIn(0f, 1f)
            } else {
                0f
            }
            val barHeight = (size.height * value)
                .coerceAtLeast(minBarHeight)

            val color = when {
                sampleIndex < 0 -> idleColor
                isRecording -> activeColor
                else -> activeColor.copy(alpha = 0.5f)
            }

            val x = slotWidth * i + slotWidth / 2f

            drawLine(
                color = color,
                start = Offset(x, centerY - barHeight / 2f),
                end = Offset(x, centerY + barHeight / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}