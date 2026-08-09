package com.closenow.network

import android.util.Log
import com.closenow.network.WebRTCNetworkManager

class NetworkSender constructor(
    private val webRTCManager: WebRTCNetworkManager,
) {

    fun sendImmediate(packet: ByteArray) {
        // Send via WebRTC data channel (partially reliable for input)
        webRTCManager.sendInputPacket(packet)
    }
}