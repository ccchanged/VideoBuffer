package com.example.instantreplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class RecordingSettings(
    val widthIndex: Int = 1,      // Index into resolution options
    val fpsIndex: Int = 1,        // Index into FPS options
    val bitrateIndex: Int = 2,    // Index into bitrate options
    val durationIndex: Int = 2,   // Index into duration options
) {
    companion object {
        // Sensible presets — only commonly supported configurations.
        val RESOLUTIONS = listOf(
            "640x360" to (640 to 360),
            "1280x720" to (1280 to 720),
            "1920x1080" to (1920 to 1080),
        )

        val FPS_OPTIONS = listOf(15, 24, 30, 60)

        val BITRATE_OPTIONS = listOf(
            "2 Mbps" to 2_000_000,
            "4 Mbps" to 4_000_000,
            "8 Mbps" to 8_000_000,
            "12 Mbps" to 12_000_000,
            "16 Mbps" to 16_000_000,
        )

        val DURATION_OPTIONS = listOf(15, 30, 60, 120, 300)
    }

    val width: Int get() = RESOLUTIONS[widthIndex].second.first
    val height: Int get() = RESOLUTIONS[widthIndex].second.second
    val fps: Int get() = FPS_OPTIONS[fpsIndex]
    val bitrate: Int get() = BITRATE_OPTIONS[bitrateIndex].second
    val durationSec: Int get() = DURATION_OPTIONS[durationIndex]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: RecordingSettings,
    isRecording: Boolean,
    internalAudio: Boolean,
    regularLogging: Boolean,
    debugLogging: Boolean,
    onSettingsChanged: (RecordingSettings) -> Unit,
    onInternalAudioChanged: (Boolean) -> Unit,
    onRegularLoggingChanged: (Boolean) -> Unit,
    onDebugLoggingChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var current by remember { mutableStateOf(settings) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (isRecording) {
                Text(
                    text = "Stop recording to change settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            // Resolution.
            SettingDropdown(
                label = "Resolution",
                options = RecordingSettings.RESOLUTIONS.map { it.first },
                selectedIndex = current.widthIndex,
                enabled = !isRecording,
                onSelected = { idx ->
                    current = current.copy(widthIndex = idx)
                    onSettingsChanged(current)
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // FPS.
            SettingDropdown(
                label = "Frame Rate",
                options = RecordingSettings.FPS_OPTIONS.map { "${it} fps" },
                selectedIndex = current.fpsIndex,
                enabled = !isRecording,
                onSelected = { idx ->
                    current = current.copy(fpsIndex = idx)
                    onSettingsChanged(current)
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Bitrate.
            SettingDropdown(
                label = "Video Bitrate",
                options = RecordingSettings.BITRATE_OPTIONS.map { it.first },
                selectedIndex = current.bitrateIndex,
                enabled = !isRecording,
                onSelected = { idx ->
                    current = current.copy(bitrateIndex = idx)
                    onSettingsChanged(current)
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Replay duration.
            SettingDropdown(
                label = "Replay Duration",
                options = RecordingSettings.DURATION_OPTIONS.map { "${it}s" },
                selectedIndex = current.durationIndex,
                enabled = !isRecording,
                onSelected = { idx ->
                    current = current.copy(durationIndex = idx)
                    onSettingsChanged(current)
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Audio settings.
            Text(
                text = "Audio",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Internal Audio switch.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Internal Audio",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Records the Internal Audio while Buffering.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = internalAudio,
                    onCheckedChange = onInternalAudioChanged,
                    enabled = !isRecording,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Logging toggles.
            Text(
                text = "Logging",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Regular logging.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Regular Logging",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Save info, warning and error logs to a text file in Downloads/InstantReplay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = regularLogging,
                    onCheckedChange = onRegularLoggingChanged,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Debug logging (only available while regular logging is on).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Debug Logging",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Also save debug logs to the same file. Turn on Regular Logging first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = debugLogging && regularLogging,
                    onCheckedChange = onDebugLoggingChanged,
                    enabled = regularLogging,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingDropdown(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = { if (enabled) expanded = it },
        ) {
            OutlinedTextField(
                value = options.getOrElse(selectedIndex) { "" },
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )

            ExposedDropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { expanded = false },
            ) {
                options.forEachIndexed { index, text ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelected(index)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
