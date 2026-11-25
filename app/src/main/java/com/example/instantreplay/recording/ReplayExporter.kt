package com.example.instantreplay.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.instantreplay.bridge.NativeBridge
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports the current buffer snapshot to an MP4 file using MediaMuxer.
 *
 * Reads video and audio frames from the Rust export snapshot and muxes them into
 * a synchronized file saved to the device's Movies directory.
 */
object ReplayExporter {
    private const val TAG = "ReplayExporter"
    private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC

    // H.264 header from the encoder's output format (set by ScreenRecorder).
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null

    // AAC header from the audio encoder's output format (set by AudioRecorder).
    @Volatile private var audioCsd0: ByteArray? = null

    /** Called by ScreenRecorder when the encoder reports its SPS (csd-0) and PPS (csd-1). */
    fun setCodecConfig(sps: ByteArray?, pps: ByteArray?) {
        this.sps = sps
        this.pps = pps
    }

    /** Called by AudioRecorder when the audio encoder reports its ASC (csd-0). */
    fun setAudioCodecConfig(csd0: ByteArray?) {
        this.audioCsd0 = csd0
    }

    /**
     * Export the replay buffer to an MP4 file.
     *
     * This should be called on a background thread. It blocks until the export
     * is complete.
     *
     * @param context Application context for accessing MediaStore.
     * @param durationSec How many seconds of replay to export.
     * @param width Video width.
     * @param height Video height.
     * @return The file path of the exported MP4, or null on failure.
     */
    fun export(
        context: Context,
        durationSec: Int,
        width: Int,
        height: Int,
    ): String? {
        val frameCount = NativeBridge.nativeBeginExport(durationSec)
        if (frameCount <= 0) {
            Log.w(TAG, "No video frames to export")
            return null
        }

        val audioFrameCount = NativeBridge.nativeGetExportAudioFrameCount()
        Log.i(TAG, "Exporting $frameCount video frames, $audioFrameCount audio frames")

        // Create a temporary file first, then copy to MediaStore on success.
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Replay_$timestamp.mp4"
        val tempFile = File(context.cacheDir, fileName)

        var muxer: MediaMuxer? = null
        var videoTrack = -1
        var audioTrack = -1
        var success = false

        try {
            muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 1. Set up video track: SPS as csd-0 and PPS as csd-1.
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
            val spsBytes = sps
            val ppsBytes = pps
            if (spsBytes != null && ppsBytes != null) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(spsBytes))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsBytes))
            } else {
                Log.w(TAG, "No SPS/PPS from encoder format, using buffered codec config")
                val codecConfig = NativeBridge.nativeGetExportCodecConfig()
                if (codecConfig != null) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(codecConfig))
                }
            }
            videoTrack = muxer.addTrack(format)

            // 2. Set up audio track if audio frames are present.
            if (audioFrameCount > 0) {
                val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
                val csd0 = audioCsd0 ?: NativeBridge.nativeGetExportAudioCodecConfig()
                if (csd0 != null) {
                    audioFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                }
                audioTrack = muxer.addTrack(audioFormat)
                Log.i(TAG, "Added audio track to muxer")
            }

            muxer.start()

            val outInfo = LongArray(2) // [presentationTimeUs, flags]
            val bufferInfo = MediaCodec.BufferInfo()

            // 3. Write video frames
            for (i in 0 until frameCount) {
                val data = NativeBridge.nativeGetExportFrame(i, outInfo)
                if (data == null) {
                    Log.w(TAG, "Null video frame at index $i, stopping video write")
                    break
                }

                val flags = outInfo[1].toInt()
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    continue
                }

                bufferInfo.set(0, data.size, outInfo[0], flags)
                muxer.writeSampleData(videoTrack, ByteBuffer.wrap(data), bufferInfo)
            }

            // 4. Write audio frames if available
            if (audioTrack >= 0) {
                for (i in 0 until audioFrameCount) {
                    val data = NativeBridge.nativeGetExportAudioFrame(i, outInfo)
                    if (data == null) {
                        Log.w(TAG, "Null audio frame at index $i, stopping audio write")
                        break
                    }

                    val flags = outInfo[1].toInt()
                    if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        continue
                    }

                    bufferInfo.set(0, data.size, outInfo[0], flags)
                    muxer.writeSampleData(audioTrack, ByteBuffer.wrap(data), bufferInfo)
                }
            }

            // Finalize the file.
            muxer.stop()
            muxer.release()
            muxer = null

            success = true
            Log.i(TAG, "Export complete: $fileName (audio included: ${audioTrack >= 0})")
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
        } finally {
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing muxer", e)
            }
            NativeBridge.nativeEndExport()
        }

        if (!success) {
            tempFile.delete()
            return null
        }

        // Move to MediaStore (Movies directory).
        return saveToMediaStore(context, tempFile, fileName)
    }

    private fun saveToMediaStore(context: Context, tempFile: File, fileName: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/InstantReplay")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

        if (uri == null) {
            Log.e(TAG, "Failed to create MediaStore entry")
            tempFile.delete()
            return null
        }

        try {
            resolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to MediaStore", e)
            resolver.delete(uri, null, null)
            tempFile.delete()
            return null
        }

        tempFile.delete()
        val path = "${Environment.DIRECTORY_MOVIES}/InstantReplay/$fileName"
        Log.i(TAG, "Saved to: $path")
        return path
    }
}
