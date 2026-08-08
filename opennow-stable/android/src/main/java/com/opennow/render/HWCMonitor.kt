package com.opennow.render

import android.content.Context
import android.util.Log

class HWCMonitor constructor(
    private val context: Context,
) {

    fun verifyHardwareOverlay(): Boolean {
        // Check via dumpsys SurfaceFlinger --list
        // Look for layer with compositionType = HWC
        try {
            val process = Runtime.getRuntime().exec("dumpsys SurfaceFlinger --list")
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                // Parse output for HWC composition type
                val hasHWC = output.contains("HWC") || output.contains("DEVICE")
                Log.i("HWCMonitor", "HWC verification: ${if (hasHWC) "PASS" else "FAIL"}")
                return hasHWC
            }
        } catch (e: Exception) {
            Log.w("HWCMonitor", "Could not verify HWC: ${e.message}")
        }
        return false
    }

    fun startPeriodicVerification(intervalMs: Long = 5000) {
        // Periodic check during streaming
        // Implement with coroutine or handler if needed
    }
}