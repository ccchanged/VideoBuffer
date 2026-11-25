package com.example.instantreplay.recording

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.instantreplay.bridge.NativeBridge

/**
 * Captures internal system audio using AudioPlaybackCaptureConfiguration (API 29+)
 * and encodes it to AAC using MediaCodec.
 *
 * Encoded AAC packets are pushed to the Rust rolling buffer.
 */
class AudioRecorder(
    private val projection: MediaProjection,
) {
    companion object {
        private const val TAG = "AudioRecorder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC // "audio/mp4a-latm"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 2
        private const val BITRATE = 128_000
    }

    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var recordThread: Thread? = null
    private var callbackThread: HandlerThread? = null
    @Volatile private var isRecording = false

    // Free input buffer indices handed to us by the encoder's async callback.
    private val inputBufferQueue = java.util.concurrent.LinkedBlockingQueue<Int>()

    var onError: ((Exception) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return
        Log.i(TAG, "Starting AudioRecorder...")

        try {
            // 1. Configure internal playback capture
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                record.release()
                return
            }

            // 2. Configure AAC MediaCodec Encoder
            val mediaFormat = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)

            callbackThread = HandlerThread("AudioEncoderCallback").also { it.start() }
            val handler = Handler(callbackThread!!.looper)

            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    inputBufferQueue.offer(index)
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    try {
                        val buffer = codec.getOutputBuffer(index) ?: return
                        if (info.size > 0) {
                            val data = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(data)

                            NativeBridge.nativePushAudioFrame(
                                data,
                                info.presentationTimeUs,
                                info.flags
                            )
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing audio output buffer", e)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Audio MediaCodec error", e)
                    onError?.invoke(e)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.i(TAG, "Audio output format changed: $format")
                    val csd0 = format.getByteBuffer("csd-0")?.let { buf ->
                        ByteArray(buf.remaining()).also { buf.duplicate().get(it) }
                    }
                    if (csd0 != null) {
                        ReplayExporter.setAudioCodecConfig(csd0)
                        NativeBridge.nativePushAudioFrame(csd0, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                    }
                }
            }, handler)

            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            record.startRecording()
            audioRecord = record
            encoder = codec
            isRecording = true

            // Reading PCM data and queueing into MediaCodec
            recordThread = Thread({
                val pcmBuffer = ByteArray(bufferSize)
                while (isRecording) {
                    val readBytes = record.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readBytes > 0) {
                        val presentationTimeUs = System.nanoTime() / 1000
                        try {
                            val inputIndex = inputBufferQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                            if (inputIndex >= 0) {
                                val inputBuffer = codec.getInputBuffer(inputIndex)
                                inputBuffer?.clear()
                                inputBuffer?.put(pcmBuffer, 0, readBytes)
                                codec.queueInputBuffer(inputIndex, 0, readBytes, presentationTimeUs, 0)
                            }
                        } catch (e: Exception) {
                            if (isRecording) {
                                Log.e(TAG, "Error queueing audio input buffer", e)
                            }
                        }
                    }
                }
            }, "AudioRecordThread").also { it.start() }

            Log.i(TAG, "AudioRecorder started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecorder", e)
            cleanup()
            onError?.invoke(e)
        }
    }

    fun stop() {
        if (!isRecording) return
        Log.i(TAG, "Stopping AudioRecorder...")
        isRecording = false
        cleanup()
    }

    fun isActive(): Boolean = isRecording

    private fun cleanup() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        try {
            recordThread?.interrupt()
            recordThread?.join(500)
        } catch (_: Exception) {}
        recordThread = null

        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio encoder", e)
        }
        encoder = null

        callbackThread?.quitSafely()
        callbackThread = null
    }
}
