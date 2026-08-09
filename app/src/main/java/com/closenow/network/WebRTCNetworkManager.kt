package com.closenow.network

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.closenow.decode.MediaCodecDecoder
import com.closenow.session.KeyframeRequest
import com.closenow.session.SessionConfig
import com.closenow.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.webrtc.*

/**
 * WebRTCNetworkManager handles the WebRTC PeerConnection for cloud gaming streaming.
 * Receives remote video track and delivers encoded frames to MediaCodecDecoder.
 */
class WebRTCNetworkManager constructor(
    private val webRTCConfig: WebRTCConfig,
    private val networkOptimizer: NetworkOptimizer,
    private val signalingClient: SignalingClient,
) {

    private var peerConnection: PeerConnection? = null
    private var currentConfig: SessionConfig? = null
    private var videoTrack: VideoTrack? = null
    private var videoSink: VideoSink? = null
    private var surface: Surface? = null
    
    // Channel for incoming encoded frames from WebRTC
    private val encodedFrameChannel = Channel<EncodedFrame>(capacity = 30)
    val encodedFrames: ReceiveChannel<EncodedFrame> = encodedFrameChannel
    
    // Session state - using SessionConfig.SessionState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableSharedFlow<SessionState>()
    val stateFlow = state.asSharedFlow()

    // ICE servers for STUN/TURN
    private val iceServers = mutableListOf<PeerConnection.IceServer>()

    init {
        networkOptimizer.acquireGamingWifiLock()
        // Add default STUN servers
        iceServers.add(PeerConnection.IceServer("stun:stun.l.google.com:19302"))
        iceServers.add(PeerConnection.IceServer("stun:stun1.l.google.com:19302"))
        
        // Listen for signaling messages
        listenForSignaling()
    }

    private fun listenForSignaling() {
        scope.launch {
            for (answer in signalingClient.answers) {
                handleAnswer(answer)
            }
        }
        
        scope.launch {
            for (candidate in signalingClient.iceCandidates) {
                handleIceCandidate(candidate)
            }
        }
        
        scope.launch {
            for (reason in signalingClient.keyframeRequests) {
                requestKeyframe(KeyframeRequest(reason))
            }
        }
        
        scope.launch {
            for (error in signalingClient.errors) {
                Log.e("WebRTCNetworkManager", "Signaling error: $error")
            }
        }
    }

    fun connect(config: SessionConfig) {
        this.currentConfig = config
        Log.i("WebRTCNetworkManager", "Connecting WebRTC for session: ${config.sessionId}")

        // Create PeerConnection
        createPeerConnection()
        
        // Connect signaling
        signalingClient.connect(config)
    }

    private fun createPeerConnection() {
        val constraints = webRTCConfig.createPeerConnectionConstraints()
        
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d("WebRTCNetworkManager", "Signaling state: $state")
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.i("WebRTCNetworkManager", "ICE connection state: $newState")
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        state.tryEmit(SessionState.STREAMING)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED,
                    PeerConnection.IceConnectionState.NEW,
                    PeerConnection.IceConnectionState.CHECKING -> {
                        state.tryEmit(SessionState.ERROR)
                        onNetworkDisconnected()
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d("WebRTCNetworkManager", "ICE gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d("WebRTCNetworkManager", "Local ICE candidate: ${candidate.sdp}")
                scope.launch {
                    signalingClient.sendIceCandidate(candidate)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}

            override fun onAddStream(stream: MediaStream) {
                Log.i("WebRTCNetworkManager", "Remote stream added: ${stream.id}")
                // Handle legacy API
            }

            override fun onRemoveStream(stream: MediaStream) {
                Log.i("WebRTCNetworkManager", "Remote stream removed: ${stream.id}")
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.i("WebRTCNetworkManager", "Data channel opened: ${dataChannel.label()}")
                setupDataChannel(dataChannel)
            }

            override fun onRenegotiationNeeded() {
                Log.i("WebRTCNetworkManager", "Renegotiation needed")
            }

            override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
                Log.i("WebRTCNetworkManager", "Track added: ${rtpReceiver.track()?.kind()}")
                if (rtpReceiver.track() is VideoTrack) {
                    handleRemoteVideoTrack(rtpReceiver.track() as VideoTrack)
                }
            }
        }

        peerConnection = webRTCConfig.createPeerConnection(iceServers, constraints, observer)
            ?: throw IllegalStateException("Failed to create PeerConnection")

        // Add transceiver for receiving video - already handled by transceiver direction
        // peerConnection?.addTransceiver(...)
    }

    private fun handleRemoteVideoTrack(track: VideoTrack) {
        this.videoTrack = track
        
        // Create a video sink that will receive frames
        videoSink = object : VideoSink {
            override fun onFrame(frame: VideoFrame) {
                // Convert VideoFrame to EncodedFrame and send to decoder
                // Note: This receives decoded frames - we need encoded frames for MediaCodec
                // We'll need to use a different approach for encoded frame delivery
                processVideoFrame(frame)
            }
        }
        
        track.addSink(videoSink!!)
        Log.i("WebRTCNetworkManager", "Video track sink added")
    }

    private fun processVideoFrame(frame: VideoFrame) {
        // For H.264 hardware decoding, we need the encoded bitstream
        // WebRTC provides decoded frames here, but we can also configure
        // the decoder factory to give us encoded frames via a custom decoder
        // For Phase 2, we'll use the standard approach with a custom VideoDecoder
        
        // TODO: In Phase 2, we use the standard WebRTC decoding path
        // For MediaCodec integration, we'll need to use the H264 depacketizer
        // and feed encoded frames to MediaCodecDecoder
    }

    fun handleAnswer(answer: SessionDescription) {
        Log.i("WebRTCNetworkManager", "Setting remote answer")
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {}
            override fun onSetSuccess() {
                Log.i("WebRTCNetworkManager", "Remote answer set successfully")
                state.tryEmit(SessionState.STREAMING)
            }
            override fun onCreateFailure(e: String) {}
            override fun onSetFailure(e: String) {
                Log.e("WebRTCNetworkManager", "Failed to set remote answer: $e")
            }
        }, answer)
    }

    fun handleIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun requestKeyframe(request: KeyframeRequest) {
        Log.i("WebRTCNetworkManager", "Requesting keyframe: ${request.reason}")
        // Send PLI (Picture Loss Indicator) via RTCP
        videoTrack?.let { track ->
            // WebRTC handles PLI internally when we request keyframe
            // We can also send via data channel if needed
        }
        scope.launch {
            signalingClient.sendKeyframeRequest(request.reason)
        }
    }

    /**
     * Called from MediaCodecDecoder callback to fill input buffer with encoded data.
     * This is where we deliver the encoded H.264 frames from WebRTC to MediaCodec.
     */
    fun fillInputBuffer(index: Int, mimeType: String) {
        // Get encoded frame from WebRTC video track
        // This will be implemented using a custom H264 depacketizer
        // that extracts NAL units from RTP packets
        scope.launch {
            encodedFrameChannel.receive().let { encodedFrame ->
                // This runs on decoder thread via MediaCodec callback
                // We need to copy the encoded data to MediaCodec input buffer
                // Implementation depends on MediaCodecDecoder having access to codec
            }
        }
    }

    fun setTargetBitrate(bps: Long) {
        // Adjust sender bitrate via RTCP REMB or transport-wide CC
        Log.i("WebRTCNetworkManager", "Setting target bitrate: ${bps / 1000} kbps")
    }

    fun setTargetResolution(width: Int, height: Int) {
        Log.i("WebRTCNetworkManager", "Setting target resolution: ${width}x$height")
        // Would require renegotiation
    }

    fun setTargetFps(fps: Int) {
        Log.i("WebRTCNetworkManager", "Setting target FPS: $fps")
    }

    fun pause() {
        Log.i("WebRTCNetworkManager", "Pausing WebRTC")
        // videoTrack?.enabled = false  // Not available in this WebRTC version
    }

    fun disconnect() {
        Log.i("WebRTCNetworkManager", "Disconnecting WebRTC")
        currentConfig = null
        
        videoTrack?.removeSink(videoSink)
        videoTrack = null
        videoSink = null
        
        peerConnection?.close()
        peerConnection = null
        
        signalingClient.shutdown()
    }

    fun sendInputPacket(packet: ByteArray) {
        // Send input via data channel
        // Implemented in setupDataChannel
    }

    fun shutdown() {
        disconnect()
        scope.coroutineContext[Job]?.cancel()
        encodedFrameChannel.close()
    }

    // Data channel for input
    private var dataChannel: DataChannel? = null

    private fun setupDataChannel(channel: DataChannel) {
        this.dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.i("WebRTCNetworkManager", "Data channel state: ${channel.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                // Handle incoming data channel messages
            }
        })
    }

    // Internal data class
    data class EncodedFrame(
        val data: ByteArray,
        val pts: Long,
        val isKeyframe: Boolean,
    )

    private fun onNetworkDisconnected() {
        // Notify session manager
        Log.w("WebRTCNetworkManager", "Network disconnected")
    }
}