package com.example.instantreplay.recording

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log

/**
 * Smart Low-Storage Guard.
 *
 * Ensures the device has a minimum free storage space before starting a replay recording
 * to avoid out-of-memory or partial disk write errors during export.
 */
object StorageGuard {
    private const val TAG = "StorageGuard"
    const val MIN_STORAGE_BYTES = 1024L * 1024 * 1024 // 1 GB minimum required

    /**
     * Get available storage in bytes.
     */
    fun getAvailableBytes(context: Context): Long {
        return try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val stat = StatFs(dir.path)
            stat.availableBytes
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query storage stats", e)
            Long.MAX_VALUE
        }
    }

    /**
     * Check if available storage is at least 1 GB.
     */
    fun hasSufficientStorage(context: Context): Boolean {
        val available = getAvailableBytes(context)
        val hasSpace = available >= MIN_STORAGE_BYTES
        if (!hasSpace) {
            Log.w(
                TAG,
                "Insufficient storage: ${available / (1024 * 1024)} MB available, required 1024 MB"
            )
        }
        return hasSpace
    }
}
