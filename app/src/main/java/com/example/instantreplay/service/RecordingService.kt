package com.example.instantreplay.service

import android.app.Notification
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
import com.example.instantreplay.recording.ScreenRecorder

/**
 * Foreground service that maintains the screen recording session.
 *
 * Holds the MediaProjection, manages the ScreenRecorder, and keeps the process
 * alive while recording in the background.
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
    }

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val binder = LocalBinder()
    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var isRecording = false

    // Recording parameters (saved for restart on config changes).
    private var recordWidth = 0
    private var recordHeight = 0
    private var recordFps = 0
    private var recordBitrate = 0
    private var maxDurationSec = 60

    /** Callback to notify the activity of state changes. */
    var onStateChanged: ((Boolean) -> Unit)? = null
    var onRecordingError: ((String) -> Unit)? = null

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

                startForeground(App.NOTIFICATION_ID, createNotification())
                startRecording(resultCode, resultData)
            }
            ACTION_STOP -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, resultData: Intent?) {
        if (isRecording) return
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

        // Create and start the recorder.
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
        isRecording = true
        onStateChanged?.invoke(true)
        Log.i(TAG, "Recording started: ${recordWidth}x${recordHeight} @ ${recordFps}fps")
    }

    private fun handleRecordingStop() {
        isRecording = false
        onStateChanged?.invoke(false)
    }

    fun stopRecording() {
        recorder?.stop()
        recorder = null
        projection?.stop()
        projection = null
        isRecording = false
        NativeBridge.nativeDestroy()
        onStateChanged?.invoke(false)
        Log.i(TAG, "Recording stopped")
    }

    fun isRecordingActive(): Boolean = isRecording && recorder?.isActive() == true

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // On rotation/display change, we could restart the recorder.
        // For simplicity, we keep recording at the configured resolution.
        // The VirtualDisplay handles scaling automatically.
        Log.d(TAG, "Configuration changed, recording continues at fixed resolution")
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, App.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
