package com.opennow.decode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.opennow.network.WebRTCNetworkManager
import com.opennow.threading.ThreadManager

class MediaCodecDecoder constructor(
    private val decoderSelector: DecoderSelector,
    private val networkManager: WebRTCNetworkManager,
    private val threadManager: ThreadManager,
) {

    private var codec: MediaCodec? = null
    private var decoderThread: Thread? = null
    private var handler: android.os.Handler? = null
    private var currentConfig: DecoderConfig? = null
    private var isLowLatencySupported = false

    data class DecoderConfig(
        val mimeType: String,
        val width: Int,
        val height: Int,
        val surface: Surface,
        val bitrateKbps: Int,
        val fps: Int,
        val colorQuality: String,  // "8bit_420", "10bit_420", etc.
    )

    class CodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun start(config: DecoderConfig) {
        if (codec != null) {
            Log.w("MediaCodecDecoder", "Decoder already started, stopping first")
            stop()
        }

        currentConfig = config

        // 1. Select best hardware decoder
        val decoderInfo = decoderSelector.selectDecoder(config.mimeType)
            ?: throw CodecException("No hardware decoder for ${config.mimeType}")

        isLowLatencySupported = decoderInfo.supportsLowLatency

        // 2. Create dedicated decoder thread with high priority
        decoderThread = threadManager.createDecoderThread {
            android.os.Looper.prepare()
            val looper = android.os.Looper.myLooper()
            if (looper != null) {
                handler = android.os.Handler(looper)
                threadManager.registerHandler("OpenNOW-Decoder", handler!!)
                android.os.Looper.loop()
            }
        }

        // 3. Configure MediaFormat
        val format = MediaFormat.createVideoFormat(config.mimeType, config.width, config.height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, calculateMaxInputSize(config))
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

        // API 30+: Configure-time low-latency
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isLowLatencySupported) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        // 4. Create and configure codec
        try {
            codec = MediaCodec.createDecoderByType(config.mimeType)
        } catch (e: Exception) {
            throw CodecException("Failed to create decoder for ${config.mimeType}", e)
        }

        codec!!.configure(format, config.surface, null, 0)

        // 5. Set async callback (runs on decoderThread)
        codec!!.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // WebRTC network layer fills this buffer
                networkManager.fillInputBuffer(index, config.mimeType)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                // Zero-copy: render directly to Surface via BufferQueue
                codec.releaseOutputBuffer(index, info.presentationTimeUs)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e("MediaCodecDecoder", "Decoder error: ${e.message}")
                throw CodecException("Decoder error: ${e.message}", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i("MediaCodecDecoder", "Output format changed: $format")
            }
        }, handler!!)

        // 6. Start codec
        codec!!.start()

        // 7. Enable runtime low-latency (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isLowLatencySupported) {
            val params = android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_LOW_LATENCY, 1) }
            try {
                codec!!.setParameters(params)
            } catch (e: IllegalStateException) {
                Log.w("MediaCodecDecoder", "Runtime low-latency not supported: ${e.message}")
                isLowLatencySupported = false
            }
        }

        Log.i("MediaCodecDecoder", "Decoder started: ${decoderInfo.name}, lowLatency=$isLowLatencySupported")
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        codec?.setCallback(null)
        codec?.stop()
        codec?.release()
        codec = null

        decoderThread?.interrupt()
        decoderThread = null
        handler = null
        currentConfig = null
        isLowLatencySupported = false
    }

    fun switchToH264Baseline() {
        // Reconfigure for thermal emergency - H.264 constrained baseline
        val h264Config = currentConfig?.copy(mimeType = "video/avc") ?: return
        Log.i("MediaCodecDecoder", "Switching to H.264 baseline for thermal")
        stop()
        start(h264Config)
    }

    fun onSurfaceSizeChanged(width: Int, height: Int) {
        // Handle surface size change - typically requires decoder restart
        currentConfig?.let { config ->
            if (config.width != width || config.height != height) {
                Log.i("MediaCodecDecoder", "Surface size changed: ${width}x$height")
                val surface = config.surface
                val newConfig = config.copy(width = width, height = height, surface = surface)
                stop()
                start(newConfig)
            }
        }
    }

    private fun calculateMaxInputSize(config: DecoderConfig): Int {
        // Rough estimate: 1.5x bitrate for peak frames
        return (config.bitrateKbps * 1000L / 8 * 3 / 2).coerceAtLeast(512 * 1024).toInt()
    }
}