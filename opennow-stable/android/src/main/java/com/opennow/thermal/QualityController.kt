package com.opennow.thermal

import android.os.PowerManager
import android.util.Log
import com.opennow.decode.MediaCodecDecoder
import com.opennow.network.WebRTCNetworkManager
import com.opennow.session.SessionManager

class QualityController constructor(
    private val decoder: MediaCodecDecoder,
    private val webRTC: WebRTCNetworkManager,
    private val sessionManager: SessionManager,
) {

    private var currentThermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var pendingAdjustment: QualityAdjustment? = null
    
    // Device-specific thermal configuration
    private var vpuThermalZoneIndependent = false
    private var combinedThermalZone = false
    private var aggressiveThermalReduction = false
    private var integratedThermalZone = false

    fun onThermalStatusChanged(status: Int) {
        // Debounce rapid changes
        pendingAdjustment = evaluateAdjustment(status)
        applyAdjustment(pendingAdjustment!!)
    }

    private fun evaluateAdjustment(thermalStatus: Int): QualityAdjustment {
        var adjustment = QualityAdjustment.NONE

        // Thermal takes priority
        when (thermalStatus) {
            PowerManager.THERMAL_STATUS_CRITICAL -> adjustment = QualityAdjustment.DRASTIC_REDUCTION
            PowerManager.THERMAL_STATUS_SEVERE -> adjustment = QualityAdjustment.MAJOR_REDUCTION
            PowerManager.THERMAL_STATUS_MODERATE -> adjustment = QualityAdjustment.MODERATE_REDUCTION
            PowerManager.THERMAL_STATUS_LIGHT -> adjustment = QualityAdjustment.MINOR_REDUCTION
            else -> adjustment = QualityAdjustment.NONE
        }

        // Network constraints (from WebRTC stats)
        val metrics = sessionManager.getCurrentMetrics()
        if (metrics.packetLoss > 0.02f) adjustment = QualityAdjustment.combine(adjustment, QualityAdjustment.REDUCE_BITRATE)
        if (metrics.rttMs > 50) adjustment = QualityAdjustment.combine(adjustment, QualityAdjustment.REDUCE_FPS)
        if (metrics.bandwidthMbps < 5000) { // Hardcoded fallback
            adjustment = QualityAdjustment.combine(adjustment, QualityAdjustment.REDUCE_RESOLUTION)
        }

        // Decoder constraints
        if (metrics.decodeLatencyMs > 8.0) adjustment = QualityAdjustment.combine(adjustment, QualityAdjustment.SWITCH_TO_SIMPLE_CODEC)
        if (metrics.droppedFrames > 0.05f) adjustment = QualityAdjustment.combine(adjustment, QualityAdjustment.REDUCE_FPS)

        return adjustment
    }

    private fun applyAdjustment(adjustment: QualityAdjustment) {
        // Thermal takes precedence - apply most restrictive
        if (adjustment.has(QualityAdjustment.DRASTIC_REDUCTION)) {
            // CRITICAL: Pause session, show warning
            sessionManager.pauseSession("Thermal critical")
            return
        }

        if (adjustment.has(QualityAdjustment.SWITCH_TO_SIMPLE_CODEC)) {
            decoder.switchToH264Baseline()
        }

        if (adjustment.has(QualityAdjustment.REDUCE_RESOLUTION)) {
            webRTC.setTargetResolution(1280, 720)
        }

        if (adjustment.has(QualityAdjustment.REDUCE_FPS)) {
            webRTC.setTargetFps(30)
            // Also hint display (if surface available)
            // surfaceView.getHolder().surface.setFrameRate(30.0f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }

        if (adjustment.has(QualityAdjustment.REDUCE_BITRATE)) {
            webRTC.setTargetBitrate(3500000) // 3.5 Mbps
        }
    }

    // Device-specific thermal configuration methods
    fun setVpuThermalZoneIndependent(enabled: Boolean) {
        vpuThermalZoneIndependent = enabled
        Log.i("QualityController", "VPU thermal zone independent: $enabled")
    }

    fun setCombinedThermalZone(enabled: Boolean) {
        combinedThermalZone = enabled
        Log.i("QualityController", "Combined thermal zone: $enabled")
    }

    fun setAggressiveThermalReduction(enabled: Boolean) {
        aggressiveThermalReduction = enabled
        Log.i("QualityController", "Aggressive thermal reduction: $enabled")
    }

    fun setIntegratedThermalZone(enabled: Boolean) {
        integratedThermalZone = enabled
        Log.i("QualityController", "Integrated thermal zone: $enabled")
    }
}

// QualityAdjustment.kt
class QualityAdjustment private constructor(private val flags: Int) {
    companion object {
        val NONE = QualityAdjustment(0)
        val MINOR_REDUCTION = QualityAdjustment(1 shl 0)
        val MODERATE_REDUCTION = QualityAdjustment(1 shl 1)
        val MAJOR_REDUCTION = QualityAdjustment(1 shl 2)
        val DRASTIC_REDUCTION = QualityAdjustment(1 shl 3)

        val REDUCE_BITRATE = QualityAdjustment(1 shl 4)
        val REDUCE_FPS = QualityAdjustment(1 shl 5)
        val REDUCE_RESOLUTION = QualityAdjustment(1 shl 6)
        val SWITCH_TO_SIMPLE_CODEC = QualityAdjustment(1 shl 7)

        fun combine(a: QualityAdjustment, b: QualityAdjustment): QualityAdjustment {
            return QualityAdjustment(a.flags or b.flags)
        }
    }

    fun has(flag: QualityAdjustment): Boolean = (flags and flag.flags) != 0
}