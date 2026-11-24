package com.example.instantreplay.bridge

/**
 * JNI bridge to the Rust replay_core native library.
 *
 * All methods are static and correspond 1:1 to functions exported by the Rust library.
 * The Rust side manages all state behind a global Mutex.
 */
object NativeBridge {

    init {
        System.loadLibrary("replay_core")
    }

    /** Initialize the native rolling buffer. Call once at startup. */
    @JvmStatic
    external fun nativeInit(maxDurationSec: Int)

    /** Push an encoded video frame (from MediaCodec output). */
    @JvmStatic
    external fun nativePushFrame(data: ByteArray, presentationTimeUs: Long, flags: Int)

    /** Get the current buffer duration in milliseconds. */
    @JvmStatic
    external fun nativeGetBufferDurationMs(): Long

    /**
     * Get buffer status as [state, durationMs, segmentCount, totalBytes].
     *
     * State values: 0=Idle, 1=Recording, 2=Exporting, 3=Error.
     */
    @JvmStatic
    external fun nativeGetBufferStatus(): IntArray

    /**
     * Begin an export: snapshot the buffer and return the frame count.
     * Returns -1 if the buffer is empty.
     */
    @JvmStatic
    external fun nativeBeginExport(durationSec: Int): Int

    /**
     * Get a single export frame by index.
     * [outInfo] must be a LongArray of size 2 — filled with [presentationTimeUs, flags].
     * Returns the frame data, or null if index is out of range.
     */
    @JvmStatic
    external fun nativeGetExportFrame(index: Int, outInfo: LongArray): ByteArray?

    /** Get the codec config data (SPS/PPS) for the export. Null if unavailable. */
    @JvmStatic
    external fun nativeGetExportCodecConfig(): ByteArray?

    /** End the current export and free the snapshot. */
    @JvmStatic
    external fun nativeEndExport()

    /** Update the maximum replay duration (seconds). */
    @JvmStatic
    external fun nativeUpdateConfig(maxDurationSec: Int)

    /** Destroy the native buffer and free all memory. */
    @JvmStatic
    external fun nativeDestroy()

    /**
     * Set the native log level.
     * 0=Off, 1=Error, 2=Warn, 3=Info, 4=Debug, 5=Trace.
     */
    @JvmStatic
    external fun nativeSetLogLevel(level: Int)
}
