package com.example.instantreplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Main screen — recording status, buffer duration, save button, settings link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isRecording: Boolean,
    isExporting: Boolean,
    bufferDurationMs: Long,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSaveReplay: () -> Unit,
    onOpenSettings: () -> Unit,
    exportMessage: String?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instant Replay") },
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Text("Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Recording status indicator.
            val statusText: String
            val statusColor: Color
            if (isRecording) {
                statusText = "● Recording"
                statusColor = Color(0xFFF44336) // Red
            } else {
                statusText = "Not Recording"
                statusColor = MaterialTheme.colorScheme.onSurfaceVariant
            }

            Text(
                text = statusText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = statusColor,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Buffer duration.
            if (isRecording) {
                val seconds = bufferDurationMs / 1000
                Text(
                    text = "Buffer: ${seconds}s",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Save Replay button (large, prominent).
            Button(
                onClick = onSaveReplay,
                enabled = isRecording && !isExporting && bufferDurationMs > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    text = if (isExporting) "Exporting…" else "Save Replay",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Export status message.
            if (exportMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = exportMessage,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Start / Stop recording button.
            OutlinedButton(
                onClick = if (isRecording) onStopRecording else onStartRecording,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (isRecording) "Stop Recording" else "Start Recording",
                    fontSize = 16.sp,
                )
            }
        }
    }
}
