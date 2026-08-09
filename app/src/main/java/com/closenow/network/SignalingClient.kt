package com.closenow.network

import android.util.Log
import com.closenow.session.SessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

/**
 * SignalingClient handles WebRTC signaling via WebSocket connection to NVIDIA CloudMatch server.
 * Implements the NVIDIA GFN signaling protocol for session establishment.
 */
class SignalingClient constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    // CloudMatch signaling endpoints
    private val cloudMatchBaseUrl = "https://cloudmatch.nvidia.com"
    private val signalingEndpoint = "wss://cloudmatch.nvidia.com/v1/signal"
    
    private var webSocket: WebSocket? = null
    private var currentSessionId: String? = null
    
    // Channels for incoming signaling messages
    private val offerChannel = Channel<SessionDescription>()
    private val answerChannel = Channel<SessionDescription>()
    private val iceCandidateChannel = Channel<IceCandidate>()
    private val keyframeRequestChannel = Channel<String>()
    private val errorChannel = Channel<String>()
    
    val offers: ReceiveChannel<SessionDescription> = offerChannel
    val answers: ReceiveChannel<SessionDescription> = answerChannel
    val iceCandidates: ReceiveChannel<IceCandidate> = iceCandidateChannel
    val keyframeRequests: ReceiveChannel<String> = keyframeRequestChannel
    val errors: ReceiveChannel<String> = errorChannel

    /**
     * Creates offer and signals to CloudMatch.
     * Returns the local offer SDP that was sent.
     */
    suspend fun createOfferAndSignal(config: SessionConfig): String {
        this.currentSessionId = config.sessionId
        
        // 1. Create WebRTC offer (will be done by WebRTCNetworkManager)
        // 2. Send offer to CloudMatch via WebSocket
        // 3. Wait for answer from CloudMatch
        
        Log.i("SignalingClient", "Creating offer for session: ${config.sessionId}")
        
        // This will be implemented when WebRTCNetworkManager creates the offer
        // The offer will be sent via sendOffer()
        return "pending"
    }

    /**
     * Connects WebSocket to CloudMatch signaling server
     */
    fun connect(config: SessionConfig) {
        if (webSocket != null) {
            Log.w("SignalingClient", "WebSocket already connected")
            return
        }
        
        this.currentSessionId = config.sessionId
        
        val request = Request.Builder()
            .url(signalingEndpoint)
            .header("Authorization", "Bearer ${getAuthToken()}") // Will need auth token
            .header("X-CloudMatch-Session", config.sessionId)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.i("SignalingClient", "WebSocket connected to CloudMatch")
                // Send initial session join message
                sendJoinMessage(config)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignalingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                Log.w("SignalingClient", "Received binary message (unexpected)")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("SignalingClient", "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e("SignalingClient", "WebSocket failure: ${t.message}")
                errorChannel.trySend(t.message ?: "WebSocket failure")
            }
        })
    }

    private fun sendJoinMessage(config: SessionConfig) {
        val json = JSONObject().apply {
            put("type", "join")
            put("sessionId", config.sessionId)
            put("clientType", "android")
            put("capabilities", JSONObject().apply {
                put("videoCodecs", listOf("H264", "HEVC", "VP9", "AV1"))
                put("maxResolution", "${config.width}x${config.height}")
                put("maxFps", config.fps)
                put("lowLatency", config.lowLatency)
            })
        }
        webSocket?.send(json.toString())
    }

    private fun handleSignalingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.getString("type")
            
            when (type) {
                "answer" -> {
                    val sdp = json.getString("sdp")
                    val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                    answerChannel.trySend(desc)
                }
                "ice" -> {
                    val candidate = json.getString("candidate")
                    val sdpMid = json.getString("sdpMid")
                    val sdpMLineIndex = json.getInt("sdpMLineIndex")
                    val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                    iceCandidateChannel.trySend(iceCandidate)
                }
                "keyframe_request" -> {
                    val reason = json.optString("reason", "server_request")
                    keyframeRequestChannel.trySend(reason)
                }
                "error" -> {
                    val message = json.getString("message")
                    errorChannel.trySend(message)
                }
                else -> {
                    Log.w("SignalingClient", "Unknown signaling message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e("SignalingClient", "Failed to parse signaling message: ${e.message}")
            errorChannel.trySend("Parse error: ${e.message}")
        }
    }

    suspend fun sendOffer(offer: SessionDescription) {
        val json = JSONObject().apply {
            put("type", "offer")
            put("sdp", offer.description)
            if (currentSessionId != null) put("sessionId", currentSessionId)
        }
        webSocket?.send(json.toString())
        Log.i("SignalingClient", "Sent offer to CloudMatch")
    }

    suspend fun sendAnswer(answer: SessionDescription) {
        val json = JSONObject().apply {
            put("type", "answer")
            put("sdp", answer.description)
            if (currentSessionId != null) put("sessionId", currentSessionId)
        }
        webSocket?.send(json.toString())
        Log.i("SignalingClient", "Sent answer to CloudMatch")
    }

    suspend fun sendIceCandidate(candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("type", "ice")
            put("candidate", candidate.sdp)
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            if (currentSessionId != null) put("sessionId", currentSessionId)
        }
        webSocket?.send(json.toString())
        Log.d("SignalingClient", "Sent ICE candidate")
    }

    suspend fun sendKeyframeRequest(reason: String) {
        val json = JSONObject().apply {
            put("type", "keyframe_request")
            put("reason", reason)
            if (currentSessionId != null) put("sessionId", currentSessionId)
        }
        webSocket?.send(json.toString())
        Log.i("SignalingClient", "Sent keyframe request: $reason")
    }
    
    private fun getAuthToken(): String {
        // TODO: Get actual NVIDIA OAuth token from AuthManager
        return "mock-token"
    }
    
    fun shutdown() {
        webSocket?.close(1000, "Client shutdown")
        webSocket = null
        scope.coroutineContext[Job]?.cancel()
        offerChannel.close()
        answerChannel.close()
        iceCandidateChannel.close()
        keyframeRequestChannel.close()
        errorChannel.close()
    }
}