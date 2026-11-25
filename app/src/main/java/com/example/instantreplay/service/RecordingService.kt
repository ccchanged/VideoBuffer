package com.example.instantreplay.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.instantreplay.App
import com.example.instantreplay.MainActivity
import com.example.instantreplay.R
import com.example.instantreplay.bridge.NativeBridge
import com.example.instantreplay.recording.AudioRecorder
import com.example.instantreplay.recording.ReplayExporter
import com.example.instantreplay.recording.ScreenRecorder
import com.example.instantreplay.recording.StorageGuard
import kotlin.concurrent.thread

/**
 * Foreground service that maintains the screen recording and internal audio session.
 *
 * Holds the MediaProjection, manages ScreenRecorder and AudioRecorder, and keeps
 * the process alive while recording in the background.
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.example.instantreplay.START"
        const val ACTION_STOP = "com.example.instantreplay.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_MAX_DURATION = "max_duration"
        const val EXTRA_SAVE_REPLAY = "save_replay"
        const val EXTRA_INTERNAL_AUDIO = "internal_audio"
    }

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val binder = LocalBinder()
    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var audioRecorder: AudioRecorder? = null
    private var isRecording = false

    @Volatile
    var isExporting = false
        private set

    // Recording parameters (saved for restart on config changes).
    private var recordWidth = 0
    private var recordHeight = 0
    private var recordFps = 0
    private var recordBitrate = 0
    private var maxDurationSec = 60
    private var recordInternalAudio = false

    /** Callback to notify the activity of state changes. */
    var onStateChanged: ((Boolean) -> Unit)? = null
    var onRecordingError: ((String) -> Unit)? = null
    var onExportStateChanged: ((Boolean, String?) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                recordWidth = intent.getIntExtra(EXTRA_WIDTH, 1280)
                recordHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
                recordFps = intent.getIntExtra(EXTRA_FPS, 30)
                recordBitrate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000)
                maxDurationSec = intent.getIntExtra(EXTRA_MAX_DURATION, 60)
                recordInternalAudio = intent.getBooleanExtra(EXTRA_INTERNAL_AUDIO, false)

                startForeground(App.NOTIFICATION_ID, createNotification())
                startRecording(resultCode, resultData)
            }
            ACTION_STOP -> {
                val shouldSave = intent.getBooleanExtra(EXTRA_SAVE_REPLAY, true)
                val width = intent.getIntExtra(EXTRA_WIDTH, recordWidth)
                val height = intent.getIntExtra(EXTRA_HEIGHT, recordHeight)
                val duration = intent.getIntExtra(EXTRA_MAX_DURATION, maxDurationSec)
                val hasBuffer = NativeBridge.nativeGetBufferDurationMs() > 0
                if (shouldSave && hasBuffer) {
                    stopAndSave(duration, width, height, null)
                } else {
                    stopRecordingImmediately()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, resultData: Intent?) {
        if (isRecording) return

        // 1. Storage Guard check: must have at least 1 GB free space.
        if (!StorageGuard.hasSufficientStorage(this)) {
            val freeMb = StorageGuard.getAvailableBytes(this) / (1024 * 1024)
            val msg = "Insufficient storage: only ${freeMb} MB free (minimum 1 GB required)."
            Log.e(TAG, msg)
            onRecordingError?.invoke(msg)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (resultData == null) {
            Log.e(TAG, "No result data for MediaProjection")
            onRecordingError?.invoke("Permission denied")
            return
        }

        // Initialize the Rust buffer.
        NativeBridge.nativeInit(maxDurationSec)

        // Create MediaProjection.
        val projManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = projManager.getMediaProjection(resultCode, resultData)
        if (proj == null) {
            Log.e(TAG, "Failed to create MediaProjection")
            onRecordingError?.invoke("MediaProjection unavailable")
            return
        }

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
                handleRecordingStop()
            }
        }, null)

        projection = proj

        // Get display density for VirtualDisplay.
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)

        // Create and start video recorder.
        val rec = ScreenRecorder(
            projection = proj,
            width = recordWidth,
            height = recordHeight,
            densityDpi = metrics.densityDpi,
            fps = recordFps,
            bitrate = recordBitrate,
        )

        rec.onError = { e ->
            Log.e(TAG, "Recording error", e)
            handleRecordingStop()
            onRecordingError?.invoke(e.message ?: "Unknown error")
        }

        rec.onStopped = {
            handleRecordingStop()
        }

        rec.start()
        recorder = rec

        // Create and start audio recorder if enabled.
        if (recordInternalAudio) {
            val audioRec = AudioRecorder(proj)
            audioRec.onError = { e ->
                Log.w(TAG, "Audio recording error", e)
            }
            audioRec.start()
            audioRecorder = audioRec
        }

        isRecording = true
        onStateChanged?.invoke(true)
        Log.i(
            TAG,
            "Recording started: ${recordWidth}x${recordHeight} @ ${recordFps}fps (internal audio: $recordInternalAudio)"
        )
    }

    private fun handleRecordingStop() {
        isRecording = false
        onStateChanged?.invoke(false)
    }

    /**
     * Stop screen recording and export the buffered replay before terminating.
     * Keeps the foreground service active while saving to prevent Android from killing the process.
     */
    fun stopAndSave(
        durationSec: Int = maxDurationSec,
        width: Int = recordWidth,
        height: Int = recordHeight,
        onComplete: ((String?) -> Unit)? = null
    ) {
        if (!isRecording && recorder == null) {
            onComplete?.invoke(null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        Log.i(TAG, "Stopping screen capture and exporting final replay buffer...")
        // 1. Stop video and audio capture immediately
        recorder?.stop()
        recorder = null
        audioRecorder?.stop()
        audioRecorder = null
        projection?.stop()
        projection = null
        isRecording = false
        onStateChanged?.invoke(false)

        isExporting = true
        onExportStateChanged?.invoke(true, "Saving replay…")
        updateNotification("Saving replay…")

        thread(name = "StopAndSaveExport") {
            var savedPath: String? = null
            try {
                savedPath = ReplayExporter.export(
                    context = applicationContext,
                    durationSec = durationSec,
                    width = width,
                    height = height
                )
                Log.i(TAG, "Replay saved on stop: $savedPath")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save replay on stop", e)
            } finally {
                // 2. Destroy native buffer now that export is finished
                NativeBridge.nativeDestroy()
                isExporting = false
                val msg = if (savedPath != null) "Saved: $savedPath" else "Export failed"
                onExportStateChanged?.invoke(false, msg)
                onComplete?.invoke(savedPath)

                // 3. Stop foreground service
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Export the replay buffer while screen recording continues in the background.
     */
    fun saveReplay(
        durationSec: Int = maxDurationSec,
        width: Int = recordWidth,
        height: Int = recordHeight,
        onComplete: ((String?) -> Unit)? = null
    ) {
        if (isExporting) return
        isExporting = true
        onExportStateChanged?.invoke(true, "Exporting…")
        updateNotification("Saving replay…")

        thread(name = "SaveReplayExport") {
            var savedPath: String? = null
            try {
                savedPath = ReplayExporter.export(
                    context = applicationContext,
                    durationSec = durationSec,
                    width = width,
                    height = height
                )
                Log.i(TAG, "Replay exported: $savedPath")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export replay", e)
            } finally {
                isExporting = false
                val msg = if (savedPath != null) "Saved: $savedPath" else "Export failed"
                onExportStateChanged?.invoke(false, msg)
                onComplete?.invoke(savedPath)
                if (isRecording) {
                    updateNotification(getString(R.string.notification_text))
                }
            }
        }
    }

    fun stopRecordingImmediately() {
        recorder?.stop()
        recorder = null
        audioRecorder?.stop()
        audioRecorder = null
        projection?.stop()
        projection = null
        isRecording = false
        NativeBridge.nativeDestroy()
        onStateChanged?.invoke(false)
        Log.i(TAG, "Recording stopped immediately")
    }

    fun stopRecording() {
        stopRecordingImmediately()
    }

    fun isRecordingActive(): Boolean = isRecording && recorder?.isActive() == true

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed, recording continues at fixed resolution")
    }

    override fun onDestroy() {
        stopRecordingImmediately()
        super.onDestroy()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(App.NOTIFICATION_ID, createNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    private fun createNotification(contentText: String = getString(R.string.notification_text)): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, App.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
