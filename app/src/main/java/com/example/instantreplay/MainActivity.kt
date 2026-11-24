package com.example.instantreplay

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.example.instantreplay.bridge.NativeBridge
import com.example.instantreplay.recording.DebugLogger
import com.example.instantreplay.recording.ReplayExporter
import com.example.instantreplay.service.RecordingService
import com.example.instantreplay.ui.MainScreen
import com.example.instantreplay.ui.RecordingSettings
import com.example.instantreplay.ui.SettingsScreen
import com.example.instantreplay.ui.theme.InstantReplayTheme
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "instant_replay_prefs"
        private const val KEY_WIDTH_IDX = "width_index"
        private const val KEY_FPS_IDX = "fps_index"
        private const val KEY_BITRATE_IDX = "bitrate_index"
        private const val KEY_DURATION_IDX = "duration_index"
    }

    // State.
    private var isRecording = mutableStateOf(false)
    private var isExporting = mutableStateOf(false)
    private var bufferDurationMs = mutableLongStateOf(0L)
    private var exportMessage = mutableStateOf<String?>(null)
    private var showSettings = mutableStateOf(false)
    private var settings = mutableStateOf(RecordingSettings())
    private var debugLogging = mutableStateOf(false)

    // Service binding.
    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as RecordingService.LocalBinder).service
            recordingService = service
            serviceBound = true

            service.onStateChanged = { recording ->
                isRecording.value = recording
                if (recording) startBufferPolling()
            }
            service.onRecordingError = { msg ->
                isRecording.value = false
                exportMessage.value = "Error: $msg"
            }

            // Sync state if service was already running.
            isRecording.value = service.isRecordingActive()
            if (isRecording.value) startBufferPolling()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            serviceBound = false
        }
    }

    // MediaProjection permission launcher.
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startRecordingService(result.resultCode, result.data!!)
        } else {
            Log.w(TAG, "MediaProjection permission denied")
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadSettings()
        debugLogging.value = DebugLogger.isEnabled

        setContent {
            InstantReplayTheme {
                val recording by isRecording
                val exporting by isExporting
                val duration by bufferDurationMs
                val message by exportMessage
                val settingsVisible by showSettings
                val currentSettings by settings
                val debugEnabled by debugLogging

                if (settingsVisible) {
                    SettingsScreen(
                        settings = currentSettings,
                        isRecording = recording,
                        debugLogging = debugEnabled,
                        onSettingsChanged = { newSettings ->
                            settings.value = newSettings
                            saveSettings(newSettings)
                        },
                        onDebugLoggingChanged = { enabled ->
                            debugLogging.value = enabled
                            DebugLogger.setEnabled(this@MainActivity, enabled)
                        },
                        onBack = { showSettings.value = false },
                    )
                } else {
                    MainScreen(
                        isRecording = recording,
                        isExporting = exporting,
                        bufferDurationMs = duration,
                        onStartRecording = { requestScreenCapture() },
                        onStopRecording = { stopRecording() },
                        onSaveReplay = { saveReplay() },
                        onOpenSettings = { showSettings.value = true },
                        exportMessage = message,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, RecordingService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            recordingService = null
        }
    }

    private fun requestScreenCapture() {
        val projManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projManager.createScreenCaptureIntent())
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
        val s = settings.value
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecordingService.EXTRA_RESULT_DATA, data)
            putExtra(RecordingService.EXTRA_WIDTH, s.width)
            putExtra(RecordingService.EXTRA_HEIGHT, s.height)
            putExtra(RecordingService.EXTRA_FPS, s.fps)
            putExtra(RecordingService.EXTRA_BITRATE, s.bitrate)
            putExtra(RecordingService.EXTRA_MAX_DURATION, s.durationSec)
        }
        ContextCompat.startForegroundService(this, intent)

        // Re-bind to get the service reference.
        if (!serviceBound) {
            bindService(
                Intent(this, RecordingService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    private fun stopRecording() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
        isRecording.value = false
        bufferDurationMs.longValue = 0L
    }

    private fun saveReplay() {
        if (isExporting.value) return
        isExporting.value = true
        exportMessage.value = "Exporting…"

        val s = settings.value
        val ctx = applicationContext

        thread {
            val path = ReplayExporter.export(
                context = ctx,
                durationSec = s.durationSec,
                width = s.width,
                height = s.height,
            )
            runOnUiThread {
                isExporting.value = false
                exportMessage.value = if (path != null) {
                    "Saved: $path"
                } else {
                    "Export failed"
                }
            }
        }
    }

    private fun startBufferPolling() {
        thread {
            while (isRecording.value) {
                try {
                    val duration = NativeBridge.nativeGetBufferDurationMs()
                    runOnUiThread {
                        bufferDurationMs.longValue = duration
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Buffer poll error", e)
                }
                Thread.sleep(500)
            }
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        settings.value = RecordingSettings(
            widthIndex = prefs.getInt(KEY_WIDTH_IDX, 1),
            fpsIndex = prefs.getInt(KEY_FPS_IDX, 1),
            bitrateIndex = prefs.getInt(KEY_BITRATE_IDX, 2),
            durationIndex = prefs.getInt(KEY_DURATION_IDX, 2),
        )
    }

    private fun saveSettings(s: RecordingSettings) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putInt(KEY_WIDTH_IDX, s.widthIndex)
            putInt(KEY_FPS_IDX, s.fpsIndex)
            putInt(KEY_BITRATE_IDX, s.bitrateIndex)
            putInt(KEY_DURATION_IDX, s.durationIndex)
            apply()
        }
    }
}
