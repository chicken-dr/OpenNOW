package com.opennow.device

import android.os.Build
import android.util.Log
import com.opennow.decode.DecoderSelector
import com.opennow.thermal.QualityController

/**
 * Google Tensor optimizations.
 * Tensor G3+: AV1 HW decode preferred
 * Good platform integration - thermal zones well defined
 * Pixel vapor chamber effective for sustained
 * Prefer AV1 > HEVC > H264
 */
object TensorOptimizer {
    fun apply(
        deviceInfo: DeviceCapabilityDetector.DeviceInfo,
        decoderSelector: DecoderSelector,
        qualityController: QualityController,
    ) {
        if (isTensorG3Plus()) {
            decoderSelector.preferAV1()
            decoderSelector.enableLowLatencyForAll()
            Log.i("TensorOptimizer", "Tensor G3+ detected: preferring AV1, enabling low-latency")
        } else if (isTensorG2()) {
            Log.i("TensorOptimizer", "Tensor G2 detected: no AV1 HW, preferring HEVC")
        } else {
            Log.i("TensorOptimizer", "Tensor G1 detected: no AV1 HW, preferring HEVC")
        }

        qualityController.setVpuThermalZoneIndependent(true)
    }

    private fun isTensorG3Plus(): Boolean {
        // Tensor G3 = "gs301", G4 = "gs401"
        return Build.HARDWARE.lowercase().let { hw ->
            hw.contains("gs3") || hw.contains("gs4")
        }
    }
    
    private fun isTensorG2(): Boolean = Build.HARDWARE.lowercase().contains("gs2")
}