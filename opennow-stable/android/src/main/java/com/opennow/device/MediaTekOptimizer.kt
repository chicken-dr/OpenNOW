package com.opennow.device

import android.os.Build
import android.util.Log
import com.opennow.decode.DecoderSelector
import com.opennow.thermal.QualityController

/**
 * MediaTek Dimensity/Helio optimizations.
 * Android 15 HEVC workaround for Dimensity 700/900/1080.
 * Dimensity 1000/9000/9200/9300: AV1 HW available.
 * Combined CPU/GPU/VPU thermal zone - aggressive quality reduction.
 */
object MediaTekOptimizer {
    fun apply(
        deviceInfo: DeviceCapabilityDetector.DeviceInfo,
        decoderSelector: DecoderSelector,
        qualityController: QualityController,
    ) {
        // Android 15 HEVC workaround (Dimensity 700/900/1080)
        if (Build.VERSION.SDK_INT >= 35 && isAffectedDimensity()) {
            decoderSelector.disableHEVCHardware()
            Log.w("MediaTekOptimizer", "Android 15 HEVC workaround active for affected Dimensity")
        }

        // Dimensity 1000/9000/9200/9300: AV1 HW available
        if (isDimensityFlagship()) {
            decoderSelector.preferAV1()
            Log.i("MediaTekOptimizer", "Dimensity flagship detected: preferring AV1")
        }

        // Helio G99: mid-range, no AV1, use H.264/HEVC
        if (isHelioG99()) {
            Log.i("MediaTekOptimizer", "Helio G99 detected: using H.264/HEVC (no AV1 HW)")
        }

        // Combined CPU/GPU/VPU thermal zone - aggressive quality reduction
        qualityController.setCombinedThermalZone(true)
    }

    private fun isAffectedDimensity(): Boolean {
        return Build.HARDWARE.lowercase().let { hw ->
            listOf("mt6769", "mt6833", "mt6853", "mt6873").any { hw.contains(it) }
        }
    }

    private fun isDimensityFlagship(): Boolean {
        return Build.HARDWARE.lowercase().let { hw ->
            listOf("mt6893", "mt6895", "mt6983", "mt6985", "mt6989").any { hw.contains(it) }
        }
    }

    private fun isHelioG99(): Boolean {
        return Build.HARDWARE.lowercase().contains("mt6769") || Build.MODEL.lowercase().contains("g99")
    }
}