package com.closenow.decode

import android.os.Build
import android.util.Log

object MediaTekWorkaround {
    
    // Check if device is affected by Android 15 HEVC black screen bug
    // Affects: Dimensity 700/900/1080 (mt6769, mt6833, mt6853, mt6873)
    fun isAffected(): Boolean = 
        Build.HARDWARE.lowercase().contains("mtk") && 
        Build.VERSION.SDK_INT >= 35 &&  // Android 15 (API 35)
        listOf("mt6769", "mt6833", "mt6853", "mt6873").any { 
            Build.HARDWARE.lowercase().contains(it) 
        }

    // Check if device is a MediaTek Dimensity flagship with AV1 HW
    fun isDimensityFlagship(): Boolean = 
        Build.HARDWARE.lowercase().let { hw ->
            listOf("mt6893", "mt6895", "mt6983", "mt6985", "mt6989").any { hw.contains(it) }
        }

    fun applyIfNeeded(decoderSelector: DecoderSelector) {
        if (isAffected()) {
            decoderSelector.disableHEVCHardware()
            Log.w("MediaTekWorkaround", "Android 15 HEVC workaround active for ${Build.HARDWARE}")
        }
        
        if (isDimensityFlagship()) {
            Log.i("MediaTekWorkaround", "Dimensity flagship detected: ${Build.HARDWARE} - AV1 HW available")
        }
    }
}