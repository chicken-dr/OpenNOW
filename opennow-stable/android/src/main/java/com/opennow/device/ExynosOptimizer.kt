package com.opennow.device

import android.os.Build
import android.util.Log
import com.opennow.decode.DecoderSelector
import com.opennow.thermal.QualityController

/**
 * Samsung Exynos optimizations.
 * Exynos 2200: Test AV1 HW (may fall back to SW)
 * Exynos 2400: Better sustained, test AV1 HW
 * Integrated thermal - monitor closely
 * Limited vendor docs - empirical validation required
 */
object ExynosOptimizer {
    fun apply(
        deviceInfo: DeviceCapabilityDetector.DeviceInfo,
        decoderSelector: DecoderSelector,
        qualityController: QualityController,
    ) {
        if (isExynos2200()) {
            decoderSelector.disableAV1()  // Conservative: force HEVC/H.264
            qualityController.setAggressiveThermalReduction(true)
            Log.i("ExynosOptimizer", "Exynos 2200 detected: disabled AV1, aggressive thermal reduction")
        } else if (isExynos2400()) {
            decoderSelector.enableAV1TestMode()  // Test then enable
            Log.i("ExynosOptimizer", "Exynos 2400 detected: AV1 test mode enabled")
        } else {
            Log.i("ExynosOptimizer", "Exynos detected: applying standard optimizations")
        }

        qualityController.setIntegratedThermalZone(true)
    }

    private fun isExynos2200(): Boolean = Build.HARDWARE.lowercase().contains("s5e9925")
    private fun isExynos2400(): Boolean = Build.HARDWARE.lowercase().contains("s5e9945")
}