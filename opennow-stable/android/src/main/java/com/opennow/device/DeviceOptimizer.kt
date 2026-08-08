package com.opennow.device

import android.os.Build
import android.util.Log
import com.opennow.decode.DecoderSelector
import com.opennow.thermal.QualityController

class DeviceOptimizer constructor(
    private val decoderSelector: DecoderSelector,
    private val qualityController: QualityController,
) {

    fun applyOptimizations() {
        when {
            isSnapdragon() -> QualcommOptimizer.apply(decoderSelector, qualityController)
            isMediaTek() -> MediaTekOptimizer.apply(decoderSelector, qualityController)
            isExynos() -> ExynosOptimizer.apply(decoderSelector, qualityController)
            isTensor() -> TensorOptimizer.apply(decoderSelector, qualityController)
            else -> Log.i("DeviceOptimizer", "No specific optimizations for hardware: ${Build.HARDWARE}")
        }
    }

    fun shouldUseDecoder(capability: DecoderSelector.DecoderCapability): Boolean {
        // Default: allow all decoders
        return true
    }

    private fun isSnapdragon(): Boolean =
        Build.HARDWARE.lowercase().contains("qcom") ||
        Build.HARDWARE.lowercase().contains("sdm") ||
        Build.HARDWARE.lowercase().contains("sm8") ||
        Build.HARDWARE.lowercase().contains("sm7")

    private fun isMediaTek(): Boolean =
        Build.HARDWARE.lowercase().contains("mtk") ||
        Build.HARDWARE.lowercase().contains("mt6")

    private fun isExynos(): Boolean =
        Build.HARDWARE.lowercase().contains("exynos") ||
        Build.HARDWARE.lowercase().contains("s5e")

    private fun isTensor(): Boolean =
        Build.HARDWARE.lowercase().contains("google") ||
        Build.HARDWARE.lowercase().contains("gs")
}

// QualcommOptimizer.kt
object QualcommOptimizer {
    fun apply(decoderSelector: DecoderSelector, qualityController: QualityController) {
        // Venus VPU: Enable low-latency (well supported on Gen 2+)
        // Use vendor MediaCodec keys for LTR control if needed
        // Independent VPU thermal zone - monitor separately via thermal zones

        val isGen2Plus = isSnapdragonGen2Plus()
        if (isGen2Plus) {
            decoderSelector.enableLowLatencyForAll()
            qualityController.setVpuThermalZoneIndependent(true)
        }
    }

    private fun isSnapdragonGen2Plus(): Boolean {
        return Build.HARDWARE.lowercase().let { hw ->
            hw.contains("sm84") || hw.contains("sm85") || hw.contains("sm86") ||
            hw.contains("sm74") || hw.contains("sm75")  // 7+ Gen 1/2
        }
    }
}

// MediaTekOptimizer.kt
object MediaTekOptimizer {
    fun apply(decoderSelector: DecoderSelector, qualityController: QualityController) {
        // Android 15 HEVC workaround (Dimensity 700/900/1080)
        if (Build.VERSION.SDK_INT >= 35 && isAffectedDimensity()) {
            decoderSelector.disableHEVCHardware()
            Log.w("MediaTekOptimizer", "Android 15 HEVC workaround active")
        }

        // Dimensity 1000/9000/9200/9300: AV1 HW available
        if (isDimensityFlagship()) {
            decoderSelector.preferAV1()
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
}

// ExynosOptimizer.kt
object ExynosOptimizer {
    fun apply(decoderSelector: DecoderSelector, qualityController: QualityController) {
        // Exynos 2200: Test AV1 HW (may fall back to SW)
        // Exynos 2400: Better sustained, test AV1 HW
        // Integrated thermal - monitor closely
        // Limited vendor docs - empirical validation required

        if (isExynos2200()) {
            decoderSelector.disableAV1()  // Conservative: force HEVC/H.264
            qualityController.setAggressiveThermalReduction(true)
        } else if (isExynos2400()) {
            decoderSelector.enableAV1TestMode()  // Test then enable
        }

        qualityController.setIntegratedThermalZone(true)
    }

    private fun isExynos2200(): Boolean = Build.HARDWARE.lowercase().contains("s5e9925")
    private fun isExynos2400(): Boolean = Build.HARDWARE.lowercase().contains("s5e9945")
}

// TensorOptimizer.kt
object TensorOptimizer {
    fun apply(decoderSelector: DecoderSelector, qualityController: QualityController) {
        // Tensor G3+: AV1 HW decode preferred
        // Good platform integration - thermal zones well defined
        // Pixel vapor chamber effective for sustained
        // Prefer AV1 > HEVC > H264

        if (isTensorG3Plus()) {
            decoderSelector.preferAV1()
            decoderSelector.enableLowLatencyForAll()
        }

        qualityController.setVpuThermalZoneIndependent(true)
    }

    private fun isTensorG3Plus(): Boolean {
        // Tensor G3 = "gs301", G4 = "gs401"
        return Build.HARDWARE.lowercase().let { hw ->
            hw.contains("gs3") || hw.contains("gs4")
        }
    }
}