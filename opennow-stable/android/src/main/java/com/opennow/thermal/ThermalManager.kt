package com.opennow.thermal

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.opennow.decode.MediaCodecDecoder
import com.opennow.network.WebRTCNetworkManager
import com.opennow.session.SessionManager

class ThermalManager constructor(
    private val qualityController: QualityController,
    private val decoder: MediaCodecDecoder,
    private val webRTC: WebRTCNetworkManager,
    private val context: Context,
) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var thermalListenerRegistered = false

    fun start() {
        if (thermalListenerRegistered) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.addThermalStatusListener(
                { status ->
                    handleThermalStatus(status)
                }
            )
            thermalListenerRegistered = true
            Log.i("ThermalManager", "Thermal listener registered")
        } else {
            // Fallback: poll thermal zones
            startPollingFallback()
        }
    }

    fun stop() {
        if (thermalListenerRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // PowerManager doesn't have removeThermalStatusListener in all versions
            // Listener auto-removed when executor shuts down
            thermalListenerRegistered = false
        }
        stopPollingFallback()
    }

    private fun handleThermalStatus(status: Int) {
        val statusName = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            else -> "UNKNOWN($status)"
        }

        Log.w("ThermalManager", "Thermal status changed: $statusName")

        // Notify quality controller
        qualityController.onThermalStatusChanged(status)

        // Log to Perfetto (via Trace)
        android.os.Trace.beginSection("ThermalStatus")
        android.os.Trace.setCounter("thermal_status", status.toLong())
        android.os.Trace.endSection()
    }

    // Fallback for API < 29: Poll thermal zones
    private fun startPollingFallback() {
        // Implement thermal zone polling if needed
    }

    private fun stopPollingFallback() {
        // Stop polling
    }
}