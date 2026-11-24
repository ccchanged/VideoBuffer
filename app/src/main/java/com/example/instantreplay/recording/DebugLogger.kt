package com.example.instantreplay.recording

import android.content.Context
import android.util.Log
import com.example.instantreplay.bridge.NativeBridge

/**
 * Controls debug logging for both Kotlin and Rust layers.
 *
 * When enabled, verbose logs are written to Android's logcat under tags:
 *   - "ReplayCore"      (Rust native library)
 *   - "ScreenRecorder"  (Kotlin encoder pipeline)
 *   - "RecordingService" (Kotlin foreground service)
 *   - "ReplayExporter"  (Kotlin MP4 muxer)
 *   - "MainActivity"    (Kotlin UI layer)
 *
 * To view logs on a connected device:
 *   adb logcat -s ReplayCore:V ScreenRecorder:V RecordingService:V ReplayExporter:V MainActivity:V
 *
 * Or simply:
 *   adb logcat | grep -E "ReplayCore|ScreenRecorder|RecordingService|ReplayExporter|InstantReplay"
 */
object DebugLogger {
    private const val TAG = "InstantReplay"
    private const val PREFS_NAME = "instant_replay_prefs"
    private const val KEY_DEBUG_LOGGING = "debug_logging"

    @Volatile
    var isEnabled: Boolean = false
        private set

    /**
     * Load the debug logging preference and apply it.
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isEnabled = prefs.getBoolean(KEY_DEBUG_LOGGING, false)
        applyLevel()
        Log.i(TAG, "Debug logging: ${if (isEnabled) "ENABLED" else "DISABLED"}")
    }

    /**
     * Toggle debug logging on or off and persist the choice.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        isEnabled = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEBUG_LOGGING, enabled)
            .apply()
        applyLevel()
        Log.i(TAG, "Debug logging ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    private fun applyLevel() {
        // Set Rust-side log level: 4=Debug when enabled, 2=Warn when disabled.
        try {
            NativeBridge.nativeSetLogLevel(if (isEnabled) 4 else 2)
        } catch (_: UnsatisfiedLinkError) {
            // Native lib not loaded yet — will be set on next init.
        }
    }
}
