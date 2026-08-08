package com.opennow.decode

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import com.opennow.device.DeviceOptimizer

class DecoderSelector constructor(
    private val deviceOptimizer: DeviceOptimizer,
) {

    data class DecoderCapability(
        val name: String,
        val mimeType: String,
        val isHardware: Boolean,
        val isVendor: Boolean,
        val supportsLowLatency: Boolean,
        val supportsAdaptivePlayback: Boolean,
        val supportsTunneledPlayback: Boolean,
        val colorFormats: IntArray,
        val profiles: Array<MediaCodecInfo.CodecProfileLevel>,
        val maxInputSize: Long = 0L,
    )

    private var preferredCodecOverride: String? = null
    private var hevcDisabled = false

    fun selectDecoder(mimeType: String): DecoderCapability? {
        // Check for override
        preferredCodecOverride?.let { override ->
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder && info.name == override) {
                    return createCapability(info, mimeType)
                }
            }
        }

        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var best: DecoderCapability? = null
        var bestScore = -1

        for (info in list.codecInfos) {
            if (info.isEncoder) continue

            // Skip HEVC if disabled (MediaTek Android 15 workaround)
            if (hevcDisabled && mimeType == "video/hevc") continue

            val caps = info.getCapabilitiesForType(mimeType)
            if (caps == null) continue

            val isHardware = if (Build.VERSION.SDK_INT >= 29) {
                info.isHardwareAccelerated()
            } else {
                !info.name.startsWith("OMX.google") && !info.name.startsWith("c2.android")
            }

            if (!isHardware) continue

            val capability = createCapability(info, mimeType)
            
            // Apply device-specific filtering
            if (!deviceOptimizer.shouldUseDecoder(capability)) continue

            val score = calculateScore(capability)
            if (score > bestScore) {
                bestScore = score
                best = capability
            }
        }

        return best
    }

    private fun createCapability(info: MediaCodecInfo, mimeType: String): DecoderCapability {
        val caps = info.getCapabilitiesForType(mimeType)!!
        val isHardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated() else true
        val isVendor = if (Build.VERSION.SDK_INT >= 29) info.isVendor() else info.name.contains("vendor") || info.name.contains(".c2.")

        // maxInputSize is not available in MediaCodecInfo.CodecCapabilities
        val maxInputSize = 0L

        return DecoderCapability(
            name = info.name,
            mimeType = mimeType,
            isHardware = isHardware,
            isVendor = isVendor,
            supportsLowLatency = caps.isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency),
            supportsAdaptivePlayback = caps.isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback),
            supportsTunneledPlayback = caps.isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback),
            colorFormats = caps.colorFormats,
            profiles = caps.profileLevels,
            maxInputSize = maxInputSize,
        )
    }

    private fun calculateScore(c: DecoderCapability): Int {
        var score = 0
        if (c.isVendor) score += 100
        if (c.supportsLowLatency) score += 10
        if (c.supportsAdaptivePlayback) score += 5
        if (c.supportsTunneledPlayback) score += 3
        if (c.colorFormats.contains(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) score += 2
        return score
    }

    fun queryAllCapabilities(): Map<String, DecoderCapability> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val mimeTypes = listOf("video/avc", "video/hevc", "video/vp9", "video/av01")
        val results = mutableMapOf<String, DecoderCapability>()

        for (mime in mimeTypes) {
            val capability = selectDecoder(mime)
            capability?.let { results[mime] = it }
        }

        return results
    }

    fun setPreferredCodec(codecName: String) {
        preferredCodecOverride = codecName
    }

    fun enableLowLatencyForAll() {
        // Device-specific: enable low-latency for all codecs
    }

    fun disableHEVCHardware() {
        hevcDisabled = true
    }

    fun preferAV1() {
        preferredCodecOverride = null
        // Logic to prioritize AV1 in selection
    }

    fun enableAV1TestMode() {
        // Enable AV1 for testing on Exynos 2400
    }

    fun disableAV1() {
        setPreferredCodec("video/hevc") // Force HEVC instead
    }
}