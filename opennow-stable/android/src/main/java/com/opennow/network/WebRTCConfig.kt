package com.opennow.network

import android.util.Log

// Stub implementation for Phase 1 - WebRTC not yet integrated
class WebRTCConfig {

    fun createPeerConnectionFactory(): Any {
        Log.i("WebRTCConfig", "Stub createPeerConnectionFactory called")
        return object {} // Stub object
    }

    private fun createVideoDecoderFactory(): Any {
        Log.i("WebRTCConfig", "Stub createVideoDecoderFactory called")
        return object {} // Stub object
    }

    fun createPeerConnectionConstraints(): Any {
        Log.i("WebRTCConfig", "Stub createPeerConnectionConstraints called")
        return object {} // Stub object
    }

    fun createVideoReceiveParameters(): Any {
        Log.i("WebRTCConfig", "Stub createVideoReceiveParameters called")
        return object {} // Stub object
    }
}