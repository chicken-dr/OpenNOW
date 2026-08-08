package com.opennow.session

import com.opennow.decode.MediaCodecDecoder

data class SessionConfig(
    val sessionId: String,
    val mimeType: String,           // "video/avc", "video/hevc", "video/vp9", "video/av01"
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val colorQuality: String,       // "8bit_420", "8bit_444", "10bit_420", "10bit_444"
    val lowLatency: Boolean = true,
)

enum class SessionState {
    DISCONNECTED,
    CONNECTING,
    NEGOTIATING,
    STARTING,
    STREAMING,
    PAUSED,
    STOPPING,
    ERROR
}

data class SessionMetrics(
    val packetLoss: Float = 0f,
    val rttMs: Int = 0,
    val bandwidthMbps: Int = 0,
    val decodeLatencyMs: Double = 0.0,
    val droppedFrames: Float = 0f,
    val jitterBufferDelayMs: Int = 0,
    val thermalStatus: Int = 0,  // PowerManager.THERMAL_STATUS_*
    val timestampMs: Long = System.currentTimeMillis(),
)

data class KeyframeRequest(
    val reason: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

sealed class SessionEvent {
    data class StateChanged(val oldState: SessionState, val newState: SessionState) : SessionEvent()
    data class MetricsUpdated(val metrics: SessionMetrics) : SessionEvent()
    data class Error(val message: String, val recoverable: Boolean) : SessionEvent()
    data class SurfaceReady(val surface: android.view.Surface) : SessionEvent()
    data class SurfaceSizeChanged(val width: Int, val height: Int) : SessionEvent()
    object SurfaceDestroyed : SessionEvent()
    data class DecoderError(val exception: MediaCodecDecoder.CodecException) : SessionEvent()
    data class ThermalStatusChanged(val status: Int) : SessionEvent()
    object NetworkDisconnected : SessionEvent()
    data class KeyframeRequested(val request: KeyframeRequest) : SessionEvent()
}