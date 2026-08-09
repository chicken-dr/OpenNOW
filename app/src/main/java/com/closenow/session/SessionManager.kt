package com.closenow.session

import android.util.Log
import com.closenow.decode.MediaCodecDecoder
import com.closenow.network.WebRTCNetworkManager
import com.closenow.thermal.QualityController
import com.closenow.thermal.ThermalManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SessionManager constructor(
    private val decoder: MediaCodecDecoder,
    private val webRTC: WebRTCNetworkManager,
    private val qualityController: QualityController,
    private val thermalManager: ThermalManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow(SessionState.DISCONNECTED)
    private val events = kotlinx.coroutines.flow.MutableSharedFlow<SessionEvent>()
    private var currentConfig: SessionConfig? = null
    private var retryCount = 0
    private val maxRetries = 3
    private var currentSurface: android.view.Surface? = null

    val stateFlow = state.asStateFlow()
    val eventsFlow = events

    fun initialize() {
        // Setup activity lifecycle callbacks
        Log.i("SessionManager", "SessionManager initialized")
    }

    fun startSession(config: SessionConfig) {
        if (state.value != SessionState.DISCONNECTED && state.value != SessionState.ERROR) {
            Log.w("SessionManager", "Cannot start session: already in ${state.value}")
            return
        }

        currentConfig = config
        retryCount = 0
        transitionTo(SessionState.CONNECTING)

        scope.launch {
            doStartSession(config)
        }
    }

    private suspend fun doStartSession(config: SessionConfig) {
        try {
            transitionTo(SessionState.NEGOTIATING)
            
            // 1. Signal with CloudMatch (placeholder - implement actual signaling)
            // val answer = webRTC.createOfferAndSignal(config)
            
            transitionTo(SessionState.STARTING)
            
            // 2. Wait for surface to be ready
            awaitSurfaceReady()
            
            // 3. Start decoder with surface
            val decoderConfig = MediaCodecDecoder.DecoderConfig(
                mimeType = config.mimeType,
                width = config.width,
                height = config.height,
                surface = currentSurface!!,
                bitrateKbps = config.bitrateKbps,
                fps = config.fps,
                colorQuality = config.colorQuality,
            )
            decoder.start(decoderConfig)
            
            // 4. Start WebRTC connection
            webRTC.connect(config)
            
            // 5. Start thermal monitoring
            thermalManager.start()
            
            transitionTo(SessionState.STREAMING)
            Log.i("SessionManager", "Session started successfully")
            
        } catch (e: Exception) {
            Log.e("SessionManager", "Failed to start session", e)
            handleSessionError(e)
        }
    }

    private suspend fun awaitSurfaceReady() {
        if (currentSurface != null) return
        
        // Wait for surface to be ready via events flow
        eventsFlow
            .filter { event -> event is SessionEvent.SurfaceReady }
            .map { event -> event as SessionEvent.SurfaceReady }
            .first()
            .let { event ->
                currentSurface = event.surface
            }
    }

    fun stopSession() {
        scope.launch {
            transitionTo(SessionState.STOPPING)
            
            decoder.stop()
            webRTC.disconnect()
            thermalManager.stop()
            
            currentSurface = null
            currentConfig = null
            retryCount = 0
            
            transitionTo(SessionState.DISCONNECTED)
            Log.i("SessionManager", "Session stopped")
        }
    }

    fun pauseSession(reason: String) {
        if (state.value == SessionState.STREAMING) {
            Log.i("SessionManager", "Pausing session: $reason")
            transitionTo(SessionState.PAUSED)
            decoder.stop()
            webRTC.pause()
        }
    }

    fun resumeSession() {
        if (state.value == SessionState.PAUSED && currentConfig != null) {
            Log.i("SessionManager", "Resuming session")
            startSession(currentConfig!!)
        }
    }

    fun onAppForeground() {
        if (state.value == SessionState.PAUSED) {
            resumeSession()
        }
    }

    fun onAppBackground() {
        if (state.value == SessionState.STREAMING) {
            pauseSession("App backgrounded")
        }
    }

    fun onAppActive() {
        // Resume input capture if needed
    }

    fun onSurfaceReady(surface: android.view.Surface) {
        currentSurface = surface
        events.tryEmit(SessionEvent.SurfaceReady(surface))
    }

    fun onSurfaceSizeChanged(width: Int, height: Int) {
        currentConfig?.let { config ->
            if (config.width != width || config.height != height) {
                events.tryEmit(SessionEvent.SurfaceSizeChanged(width, height))
                // Reconfigure decoder and WebRTC if needed
                decoder.onSurfaceSizeChanged(width, height)
                webRTC.setTargetResolution(width, height)
            }
        }
    }

    fun onSurfaceDestroyed() {
        currentSurface = null
        events.tryEmit(SessionEvent.SurfaceDestroyed)
        if (state.value == SessionState.STREAMING) {
            pauseSession("Surface destroyed")
        }
    }

    fun onDecoderError(error: MediaCodecDecoder.CodecException) {
        events.tryEmit(SessionEvent.DecoderError(error))
        retryCount++
        
        if (retryCount <= maxRetries) {
            Log.w("SessionManager", "Decoder error, retrying ($retryCount/$maxRetries): ${error.message}")
            webRTC.requestKeyframe(KeyframeRequest("decoder_error"))
            restartDecoder()
        } else {
            fallbackToNextCodec()
        }
    }

    fun onNetworkDisconnected() {
        events.tryEmit(SessionEvent.NetworkDisconnected)
        if (state.value == SessionState.STREAMING) {
            pauseSession("Network disconnected")
            scheduleReconnect()
        }
    }

    fun onThermalCritical() {
        events.tryEmit(SessionEvent.ThermalStatusChanged(android.os.PowerManager.THERMAL_STATUS_CRITICAL))
        pauseSession("Thermal critical")
        showThermalWarning()
    }

    fun getCurrentMetrics(): SessionMetrics {
        // Return current metrics from various components
        return SessionMetrics()
    }

    fun shutdown() {
        scope.coroutineContext[Job]?.cancel()
        decoder.stop()
        webRTC.disconnect()
        thermalManager.stop()
    }

    private fun transitionTo(newState: SessionState) {
        val oldState = state.value
        if (oldState != newState) {
            state.value = newState
            events.tryEmit(SessionEvent.StateChanged(oldState, newState))
            Log.i("SessionManager", "State: $oldState -> $newState")
        }
    }

    private fun handleSessionError(e: Exception) {
        val errorMsg = e.message ?: "Unknown error"
        events.tryEmit(SessionEvent.Error(errorMsg, true))
        transitionTo(SessionState.ERROR)
    }

    private fun fallbackToNextCodec() {
        val currentCodec = currentConfig?.mimeType
        val nextCodec = when (currentCodec) {
            "video/av01" -> "video/hevc"
            "video/hevc" -> "video/avc"
            "video/vp9" -> "video/avc"
            else -> null
        }

        nextCodec?.let { codec ->
            Log.i("SessionManager", "Falling back to $codec")
            currentConfig = currentConfig?.copy(mimeType = codec)
            restartDecoder()
        } ?: run {
            transitionTo(SessionState.ERROR)
            events.tryEmit(SessionEvent.Error("No fallback codec available", false))
        }
    }

    private fun restartDecoder() {
        currentSurface?.let { surface ->
            currentConfig?.let { config ->
                decoder.stop()
                val decoderConfig = MediaCodecDecoder.DecoderConfig(
                    mimeType = config.mimeType,
                    width = config.width,
                    height = config.height,
                    surface = surface,
                    bitrateKbps = config.bitrateKbps,
                    fps = config.fps,
                    colorQuality = config.colorQuality,
                )
                decoder.start(decoderConfig)
            }
        }
    }

    private fun scheduleReconnect() {
        scope.launch {
            // Implement reconnect logic with backoff
            Log.i("SessionManager", "Scheduling reconnect...")
        }
    }

    private fun showThermalWarning() {
        // Show thermal warning UI
        Log.w("SessionManager", "Thermal critical - showing warning")
    }

    // Delegate to injected components
    private var _inputProcessor: com.closenow.input.InputProcessor? = null

    fun setInputProcessor(inputProcessor: com.closenow.input.InputProcessor) {
        _inputProcessor = inputProcessor
    }

    fun getInputProcessor(): com.closenow.input.InputProcessor {
        return _inputProcessor ?: throw IllegalStateException("InputProcessor not initialized")
    }
}