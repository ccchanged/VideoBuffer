package com.example.instantreplay.recording

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.instantreplay.bridge.NativeBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Controls logging for both Kotlin and Rust layers.
 *
 * Two switches:
 *   - Regular logging: saves info, warning and error logs to a text file.
 *   - Debug logging: also saves debug logs to the same file.
 *     Only available while regular logging is on; turning regular logging
 *     off also turns debug logging off.
 *
 * The text file is created in Downloads/InstantReplay each time regular
 * logging starts (app launch with it on, or when it is switched on):
 *   Downloads/InstantReplay/instantreplay_<date>_<time>.txt
 *
 * Logs are still printed to logcat under tags:
 *   - "ReplayCore"      (Rust native library)
 *   - "ScreenRecorder"  (Kotlin encoder pipeline)
 *   - "RecordingService" (Kotlin foreground service)
 *   - "ReplayExporter"  (Kotlin MP4 muxer)
 *   - "MainActivity"    (Kotlin UI layer)
 */
object DebugLogger {
    private const val TAG = "InstantReplay"
    private const val PREFS_NAME = "instant_replay_prefs"
    private const val KEY_REGULAR_LOGGING = "regular_logging"
    private const val KEY_DEBUG_LOGGING = "debug_logging"

    private val WHITESPACE = Regex("\\s+")

    /** Regular logging: info, warning and error go to the text file. */
    @Volatile
    var isRegularEnabled: Boolean = false
        private set

    /** Debug logging: debug logs also go to the text file. Needs regular logging on. */
    @Volatile
    var isEnabled: Boolean = false
        private set

    private var logcatProcess: Process? = null
    private var logThread: Thread? = null

    /**
     * Load the logging preferences and apply them.
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isRegularEnabled = prefs.getBoolean(KEY_REGULAR_LOGGING, false)
        isEnabled = isRegularEnabled && prefs.getBoolean(KEY_DEBUG_LOGGING, false)
        applyLevel()
        applyFileLogging(context)
        Log.i(TAG, "Regular logging: ${if (isRegularEnabled) "ENABLED" else "DISABLED"}, " +
            "debug logging: ${if (isEnabled) "ENABLED" else "DISABLED"}")
    }

    /**
     * Toggle regular logging on or off and persist the choice.
     * Turning it off also turns debug logging off.
     */
    fun setRegularEnabled(context: Context, enabled: Boolean) {
        isRegularEnabled = enabled
        if (!enabled) isEnabled = false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REGULAR_LOGGING, enabled)
            .putBoolean(KEY_DEBUG_LOGGING, isEnabled)
            .apply()
        applyLevel()
        Log.i(TAG, "Regular logging ${if (enabled) "ENABLED" else "DISABLED"}")
        applyFileLogging(context)
    }

    /**
     * Toggle debug logging on or off and persist the choice.
     * Ignored while regular logging is off.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        if (!isRegularEnabled) return
        isEnabled = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEBUG_LOGGING, enabled)
            .apply()
        applyLevel()
        Log.i(TAG, "Debug logging ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    private fun applyLevel() {
        // Rust-side log level: 4=Debug (debug logging), 3=Info (regular logging),
        // 2=Warn (all logging off).
        val level = when {
            isEnabled -> 4
            isRegularEnabled -> 3
            else -> 2
        }
        try {
            NativeBridge.nativeSetLogLevel(level)
        } catch (_: UnsatisfiedLinkError) {
            // Native lib not loaded yet — will be set on next init.
        }
    }

    private fun applyFileLogging(context: Context) {
        if (isRegularEnabled) {
            startFileLogging(context.applicationContext)
        } else {
            stopFileLogging()
        }
    }

    /** Debug and verbose lines are only written while debug logging is on. */
    private fun shouldWrite(line: String): Boolean {
        if (isEnabled) return true
        // logcat "threadtime" format: date time pid tid priority tag: message
        val priority = line.trim().split(WHITESPACE, limit = 6).getOrNull(4)
        return priority != "D" && priority != "V"
    }

    /**
     * Pipe this app's own logcat output (Kotlin + Rust) into a text file in
     * Downloads/InstantReplay. An app can always read its own process's logs,
     * so no extra permission is needed.
     */
    @Synchronized
    private fun startFileLogging(context: Context) {
        if (logThread?.isAlive == true) return

        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "instantreplay_$stamp.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/InstantReplay"
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Log.e(TAG, "Failed to create log file")
                return
            }
            val output = resolver.openOutputStream(uri, "w")
            if (output == null) {
                Log.e(TAG, "Failed to open log file")
                return
            }

            // Read debug level and up; shouldWrite() drops debug lines when
            // debug logging is off, so the switch works without restarting logcat.
            val process = ProcessBuilder(
                "logcat", "-v", "threadtime", "--pid=${android.os.Process.myPid()}", "*:D"
            ).redirectErrorStream(true).start()
            logcatProcess = process

            val thread = Thread({
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        output.bufferedWriter().use { writer ->
                            writer.write(
                                "=== Instant Replay log started $stamp | " +
                                    "${Build.MANUFACTURER} ${Build.MODEL} | " +
                                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ===\n"
                            )
                            writer.flush()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (!shouldWrite(line)) continue
                                writer.write(line)
                                writer.newLine()
                                // Flush every line so nothing is lost if the app crashes.
                                writer.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Process was stopped or the file was closed.
                }
            }, "DebugLogger-file")
            thread.isDaemon = true
            thread.start()
            logThread = thread

            Log.i(TAG, "Writing log file: Downloads/InstantReplay/instantreplay_$stamp.txt")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start file logging", e)
        }
    }

    @Synchronized
    private fun stopFileLogging() {
        logcatProcess?.destroy()
        logcatProcess = null
        logThread = null
    }
}
