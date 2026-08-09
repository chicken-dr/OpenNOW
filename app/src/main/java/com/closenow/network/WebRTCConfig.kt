package com.closenow.network

import android.content.Context
import android.util.Log
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.PeerConnectionFactory.InitializationOptions
import org.webrtc.PeerConnectionFactory.Options
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoderFactory

class WebRTCConfig(private val context: Context) {

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null

    init {
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        // Initialize WebRTC
        val initializationOptions = InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        // Create EglBase for hardware video rendering
        eglBase = EglBase.create()

        // Configure factory options
        val options = Options().apply {
            networkIgnoreMask = 0 // Accept all networks
            disableNetworkMonitor = false
            disableEncryption = false
        }

        // Create decoder/encoder factories
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true, // enableH264HighProfile
            true  // enableVp8
        )

        // Create PeerConnectionFactory
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(encoderFactory)
            .createPeerConnectionFactory()

        Log.i("WebRTCConfig", "PeerConnectionFactory initialized with HW decoder/encoder")
    }

    fun getPeerConnectionFactory(): PeerConnectionFactory {
        return peerConnectionFactory!!
            ?: throw IllegalStateException("PeerConnectionFactory not initialized")
    }

    fun getEglBase(): EglBase {
        return eglBase!!
            ?: throw IllegalStateException("EglBase not initialized")
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>, constraints: MediaConstraints, observer: PeerConnection.Observer): PeerConnection? {
        return peerConnectionFactory!!.createPeerConnection(iceServers, constraints, observer)
    }

    fun getVideoDecoderFactory(): VideoDecoderFactory {
        return DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
    }

    fun getVideoEncoderFactory(): VideoEncoderFactory {
        return DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true, // enableH264HighProfile
            true  // enableVp8
        )
    }

    fun createPeerConnectionConstraints(): MediaConstraints {
        return MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
            optional.add(MediaConstraints.KeyValuePair("RtpDataChannels", "true"))
        }
    }

    fun createVideoReceiveParameters(): VideoCodecInfo {
        // H.264 preferred for Phase 2
        return VideoCodecInfo("H264", mapOf(
            "level-asymmetry-allowed" to "1",
            "packetization-mode" to "1",
            "profile-level-id" to "42e01f" // Constrained Baseline
        ))
    }

    fun shutdown() {
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
        Log.i("WebRTCConfig", "WebRTCConfig shutdown complete")
    }
}