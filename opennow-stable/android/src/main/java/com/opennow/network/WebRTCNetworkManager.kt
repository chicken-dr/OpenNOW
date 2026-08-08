package com.opennow.network

import android.util.Log
import com.opennow.session.KeyframeRequest
import com.opennow.session.SessionConfig

// Stub implementation for Phase 1 - WebRTC not yet integrated
class WebRTCNetworkManager constructor(
    private val config: WebRTCConfig,
    private val networkOptimizer: NetworkOptimizer,
    private val signalingClient: SignalingClient,
) {

    private var currentConfig: SessionConfig? = null
    
    init {
        networkOptimizer.acquireGamingWifiLock()
    }

    fun connect(config: SessionConfig) {
        this.currentConfig = config
        Log.i("WebRTCNetworkManager", "Stub connect called")
    }

    fun handleAnswer(answer: SessionDescription) {
        Log.i("WebRTCNetworkManager", "Stub handleAnswer called")
    }

    fun handleIceCandidate(candidate: IceCandidate) {
        Log.i("WebRTCNetworkManager", "Stub handleIceCandidate called")
    }

    fun requestKeyframe(request: KeyframeRequest) {
        Log.i("WebRTCNetworkManager", "Stub requestKeyframe called: ${request.reason}")
    }

    fun fillInputBuffer(index: Int, mimeType: String) {
        // Called from MediaCodec decoder callback
        // Provide encoded frame data from WebRTC
    }

    fun setTargetBitrate(bps: Long) {
        Log.i("WebRTCNetworkManager", "Stub setTargetBitrate: ${bps / 1000} kbps")
    }

    fun setTargetResolution(width: Int, height: Int) {
        Log.i("WebRTCNetworkManager", "Stub setTargetResolution: ${width}x$height")
    }

    fun setTargetFps(fps: Int) {
        Log.i("WebRTCNetworkManager", "Stub setTargetFps: $fps")
    }

    fun pause() {
        Log.i("WebRTCNetworkManager", "Stub pause called")
    }

    fun disconnect() {
        currentConfig = null
        Log.i("WebRTCNetworkManager", "Stub disconnect called")
    }

    fun sendInputPacket(packet: ByteArray) {
        Log.i("WebRTCNetworkManager", "Stub sendInputPacket called")
    }

    fun shutdown() {
        disconnect()
    }

    // Stub classes for Phase 1
    data class SessionDescription(
        val type: String,
        val description: String
    )

    data class IceCandidate(
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        val sdp: String
    )

    data class EncodedFrame(
        val data: ByteArray,
        val pts: Long,
        val isKeyframe: Boolean,
    )
}