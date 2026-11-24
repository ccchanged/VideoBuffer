package com.example.instantreplay.recording

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.instantreplay.bridge.NativeBridge

/**
 * Manages the screen capture pipeline:
 * MediaProjection → VirtualDisplay → MediaCodec (H.264 encoder) → Rust buffer.
 *
 * This class only handles Android framework APIs. All buffer logic lives in Rust.
 */
class ScreenRecorder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val fps: Int,
    private val bitrate: Int,
) {
    companion object {
        private const val TAG = "ScreenRecorder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC // H.264
        private const val KEYFRAME_INTERVAL_SEC = 2
    }

    private var encoder: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var callbackThread: HandlerThread? = null
    private var isRecording = false

    /** Listener for recording state changes. */
    var onError: ((Exception) -> Unit)? = null
    var onStopped: (() -> Unit)? = null

    /**
     * Start recording the screen.
     *
     * Sets up the encoder, creates a VirtualDisplay that feeds into the encoder's
     * input Surface, and begins pushing encoded frames to the Rust rolling buffer.
     */
    fun start() {
        if (isRecording) return
        Log.i(TAG, "Starting recording: ${width}x${height} @ ${fps}fps, ${bitrate}bps")

        try {
            // Create the encoder.
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_SEC)
            }

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)

            // Set up the async callback on a dedicated thread.
            callbackThread = HandlerThread("EncoderCallback").also { it.start() }
            val handler = Handler(callbackThread!!.looper)

            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // Not used — Surface provides input directly.
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    try {
                        val buffer = codec.getOutputBuffer(index) ?: return
                        if (info.size > 0) {
                            // Copy the encoded data from the ByteBuffer.
                            val data = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(data)

                            // Push to Rust rolling buffer.
                            NativeBridge.nativePushFrame(
                                data,
                                info.presentationTimeUs,
                                info.flags
                            )
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing output buffer", e)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "MediaCodec error", e)
                    onError?.invoke(e)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.i(TAG, "Output format changed: $format")
                    // The encoder reports the H.264 header as two separate pieces:
                    // csd-0 = SPS, csd-1 = PPS. The MP4 muxer needs exactly these.
                    val sps = format.getByteBuffer("csd-0")?.let { buf ->
                        ByteArray(buf.remaining()).also { buf.duplicate().get(it) }
                    }
                    val pps = format.getByteBuffer("csd-1")?.let { buf ->
                        ByteArray(buf.remaining()).also { buf.duplicate().get(it) }
                    }
                    ReplayExporter.setCodecConfig(sps, pps)
                    Log.i(TAG, "Saved codec config: sps=${sps?.size ?: 0} bytes, pps=${pps?.size ?: 0} bytes")
                }
            }, handler)

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // Get the input Surface BEFORE starting the codec.
            val inputSurface = codec.createInputSurface()

            // Create VirtualDisplay that renders to the encoder's input Surface.
            virtualDisplay = projection.createVirtualDisplay(
                "InstantReplay",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                handler
            )

            codec.start()
            encoder = codec
            isRecording = true
            Log.i(TAG, "Recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            onError?.invoke(e)
        }
    }

    /** Stop recording and release all resources. */
    fun stop() {
        if (!isRecording) return
        Log.i(TAG, "Stopping recording")
        isRecording = false
        cleanup()
        onStopped?.invoke()
    }

    /** Whether the recorder is currently active. */
    fun isActive(): Boolean = isRecording

    private fun cleanup() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing VirtualDisplay", e)
        }
        virtualDisplay = null

        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaCodec", e)
        }
        encoder = null

        callbackThread?.quitSafely()
        callbackThread = null
    }
}
