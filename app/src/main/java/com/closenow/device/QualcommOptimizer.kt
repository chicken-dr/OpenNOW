package com.closenow.device

import android.os.Build
import android.util.Log
import com.closenow.decode.DecoderSelector
import com.closenow.thermal.QualityController

/**
 * Qualcomm Snapdragon optimizations.
 * Venus VPU: low-latency well supported on Gen 2+ (SM8450+).
 * Independent VPU thermal zone - monitor separately.
 */
object QualcommOptimizer {
    fun apply(
        deviceInfo: DeviceCapabilityDetector.DeviceInfo,
        decoderSelector: DecoderSelector,
        qualityController: QualityController,
    ) {
        val isGen2Plus = isSnapdragonGen2Plus()
        
        if (isGen2Plus) {
            decoderSelector.enableLowLatencyForAll()
            qualityController.setVpuThermalZoneIndependent(true)
            Log.i("QualcommOptimizer", "Applied Gen 2+ optimizations: low-latency enabled, VPU thermal independent")
        }
        
        // Log detected capabilities
        val capabilities = DeviceCapabilityDetector.getInstance(decoderSelector::class.java.getDeclaredField("context").run { isAccessible = true; get(decoderSelector) as android.content.Context }).codecCapabilities()
        for ((mime, cap) in capabilities) {
            Log.i("QualcommOptimizer", "Codec $mime: ${cap.codecName} LowLatency=${cap.supportsLowLatency} Adaptive=${cap.supportsAdaptivePlayback}")
        }
    }

    private fun isSnapdragonGen2Plus(): Boolean {
        return Build.HARDWARE.lowercase().let { hw ->
            hw.contains("sm84") || hw.contains("sm85") || hw.contains("sm86") ||
            hw.contains("sm74") || hw.contains("sm75") || hw.contains("sm76") // 7+ Gen 1/2/3
        }
    }
}