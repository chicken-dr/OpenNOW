package com.closenow.device

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import com.closenow.decode.DecoderSelector
import com.closenow.thermal.QualityController

/**
 * Universal device capability detection system.
 * Detects SoC vendor, codec capabilities, display properties, thermal API availability,
 * and provides safe fallbacks for unknown devices.
 */
class DeviceCapabilityDetector private constructor(private val context: Context) {

    data class DeviceInfo(
        val manufacturer: String = Build.MANUFACTURER,
        val model: String = Build.MODEL,
        val hardware: String = Build.HARDWARE,
        val brand: String = Build.BRAND,
        val apiLevel: Int = Build.VERSION.SDK_INT,
        val cpuAbi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        val isVendorDetected: Boolean = false,
        val socVendor: SocVendor = SocVendor.UNKNOWN,
    )

    enum class SocVendor {
        QUALCOMM,
        MEDIATEK,
        SAMSUNG_EXYNOS,
        GOOGLE_TENSOR,
        HISILICON,
        UNKNOWN
    }

    data class CodecCapability(
        val mimeType: String,
        val codecName: String,
        val isHardware: Boolean,
        val isVendor: Boolean,
        val supportsLowLatency: Boolean,
        val supportsAdaptivePlayback: Boolean,
        val supportsTunneledPlayback: Boolean,
        val colorFormats: IntArray,
        val profiles: Array<MediaCodecInfo.CodecProfileLevel>,
        val maxWidth: Int,
        val maxHeight: Int,
    )

    data class DisplayInfo(
        val refreshRate: Float = 60f,
        val width: Int = 1920,
        val height: Int = 1080,
        val supportsHighRefreshRate: Boolean = false,
    )

    data class ThermalInfo(
        val thermalApiAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        val thermalZoneCount: Int = 0,
    )

    companion object {
        @Volatile
        private var INSTANCE: DeviceCapabilityDetector? = null

        fun getInstance(context: Context): DeviceCapabilityDetector {
            val instance = INSTANCE
            if (instance != null) return instance
            return synchronized(this) {
                val newInstance = DeviceCapabilityDetector(context.applicationContext)
                INSTANCE = newInstance
                newInstance
            }
        }
    }

    private val deviceInfo: DeviceInfo by lazy { detectDeviceInfo() }
    private val codecCapabilities: Map<String, CodecCapability> by lazy { detectCodecCapabilities() }
    private var displayInfo: DisplayInfo = DisplayInfo()
    private val thermalInfo: ThermalInfo by lazy { detectThermalInfo() }

    fun deviceInfo(): DeviceInfo = deviceInfo
    fun codecCapabilities(): Map<String, CodecCapability> = codecCapabilities
    fun displayInfo(): DisplayInfo = displayInfo
    fun thermalInfo(): ThermalInfo = thermalInfo

    fun getBestDecoderFor(mimeType: String): CodecCapability? {
        return codecCapabilities[mimeType]
    }

    fun getPreferredCodecOrder(): List<String> {
        val mimeTypes = listOf("video/avc", "video/hevc", "video/vp9", "video/av01")
        return mimeTypes.filter { codecCapabilities.containsKey(it) }
    }

    fun updateDisplayInfo(refreshRate: Float, width: Int, height: Int) {
        // Called from GameSurfaceView when surface is created
        displayInfo = DisplayInfo(
            refreshRate = refreshRate,
            width = width,
            height = height,
            supportsHighRefreshRate = refreshRate > 60f
        )
    }

    private fun detectDeviceInfo(): DeviceInfo {
        val hardware = Build.HARDWARE.lowercase()
        var vendor = SocVendor.UNKNOWN
        var isVendorDetected = false

        when {
            hardware.contains("qcom") || hardware.contains("sdm") || hardware.contains("sm8") || hardware.contains("sm7") || hardware.contains("sm6") -> {
                vendor = SocVendor.QUALCOMM
                isVendorDetected = true
            }
            hardware.contains("mtk") || hardware.contains("mt6") -> {
                vendor = SocVendor.MEDIATEK
                isVendorDetected = true
            }
            hardware.contains("exynos") || hardware.contains("s5e") -> {
                vendor = SocVendor.SAMSUNG_EXYNOS
                isVendorDetected = true
            }
            hardware.contains("google") || hardware.contains("gs") -> {
                vendor = SocVendor.GOOGLE_TENSOR
                isVendorDetected = true
            }
            hardware.contains("kirin") || hardware.contains("hi36") -> {
                vendor = SocVendor.HISILICON
                isVendorDetected = true
            }
        }

        Log.i("DeviceCapabilityDetector", "Detected SoC vendor: $vendor (hardware: ${Build.HARDWARE})")
        
        return DeviceInfo(
            isVendorDetected = isVendorDetected,
            socVendor = vendor,
        )
    }

    private fun detectCodecCapabilities(): Map<String, CodecCapability> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val mimeTypes = listOf("video/avc", "video/hevc", "video/vp9", "video/av01")
        val results = mutableMapOf<String, CodecCapability>()

        for (mime in mimeTypes) {
            var bestCap: CodecCapability? = null

            for (info in list.codecInfos) {
                if (info.isEncoder) continue

                val caps = info.getCapabilitiesForType(mime)
                if (caps == null) continue

                val isHardware = if (Build.VERSION.SDK_INT >= 29) {
                    info.isHardwareAccelerated()
                } else {
                    !info.name.startsWith("OMX.google") && !info.name.startsWith("c2.android")
                }

                if (!isHardware) continue

                val isVendor = if (Build.VERSION.SDK_INT >= 29) {
                    info.isVendor()
                } else {
                    info.name.contains("vendor") || info.name.contains(".c2.")
                }

                val supportsLowLatency = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                val supportsAdaptivePlayback = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
                val supportsTunneledPlayback = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)

                val profileLevels = caps.profileLevels
                var maxWidth = 0
                var maxHeight = 0
                if (profileLevels != null) {
                    for (pl in profileLevels) {
                        // CodecProfileLevel width/height may not be accessible in all API levels
                        // Skip extraction for compatibility
                    }
                }

                val cap = CodecCapability(
                    mimeType = mime,
                    codecName = info.name,
                    isHardware = isHardware,
                    isVendor = isVendor,
                    supportsLowLatency = supportsLowLatency,
                    supportsAdaptivePlayback = supportsAdaptivePlayback,
                    supportsTunneledPlayback = supportsTunneledPlayback,
                    colorFormats = caps.colorFormats,
                    profiles = profileLevels,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                )

                // Prefer vendor codec, then low-latency, then any hardware
                if (bestCap == null ||
                    (cap.isVendor && !bestCap.isVendor) ||
                    (cap.supportsLowLatency && !bestCap.supportsLowLatency) ||
                    (cap.maxWidth > bestCap.maxWidth)) {
                    bestCap = cap
                }
            }

            bestCap?.let { results[mime] = it }
        }

        for ((mime, cap) in results) {
            Log.i("DeviceCapabilityDetector", "Codec $mime: ${cap.codecName} HW=${cap.isHardware} Vendor=${cap.isVendor} LowLatency=${cap.supportsLowLatency} Adaptive=${cap.supportsAdaptivePlayback} Max=${cap.maxWidth}x${cap.maxHeight}")
        }

        return results
    }

    private fun detectDisplayInfo(): DisplayInfo {
        // This would typically use WindowManager to get display metrics
        // For now, return defaults - actual values set when SurfaceView is created
        return DisplayInfo()
    }

    private fun detectThermalInfo(): ThermalInfo {
        var zoneCount = 0
        try {
            // Try to read thermal zones from sysfs
            java.io.File("/sys/class/thermal").listFiles()?.forEach { file ->
                if (file.name.startsWith("thermal_zone")) zoneCount++
            }
        } catch (e: Exception) {
            // Ignore - thermal info not available
        }

        return ThermalInfo(
            thermalApiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            thermalZoneCount = zoneCount,
        )
    }
}

/**
 * DeviceOptimizer applies vendor-specific optimizations based on detected capabilities.
 * This is a compatibility wrapper for Phase 1/2 code that expects DeviceOptimizer.
 */
class DeviceOptimizer constructor(
    private val decoderSelector: DecoderSelector,
    private val qualityController: QualityController,
    private val context: Context,
) {
    private val capabilityDetector = DeviceCapabilityDetector.getInstance(context)

    fun applyOptimizations() {
        val deviceInfo = capabilityDetector.deviceInfo()
        
        when (deviceInfo.socVendor) {
            DeviceCapabilityDetector.SocVendor.QUALCOMM -> QualcommOptimizer.apply(deviceInfo, decoderSelector, qualityController)
            DeviceCapabilityDetector.SocVendor.MEDIATEK -> MediaTekOptimizer.apply(deviceInfo, decoderSelector, qualityController)
            DeviceCapabilityDetector.SocVendor.SAMSUNG_EXYNOS -> ExynosOptimizer.apply(deviceInfo, decoderSelector, qualityController)
            DeviceCapabilityDetector.SocVendor.GOOGLE_TENSOR -> TensorOptimizer.apply(deviceInfo, decoderSelector, qualityController)
            else -> Log.i("DeviceOptimizer", "No specific optimizations for hardware: ${Build.HARDWARE}")
        }
    }

    fun shouldUseDecoder(capability: DecoderSelector.DecoderCapability): Boolean {
        // Default: allow all decoders
        return true
    }
    
    fun getCapabilityDetector(): DeviceCapabilityDetector = capabilityDetector
}