package com.closenow.di

import android.content.Context
import com.closenow.decode.DecoderSelector
import com.closenow.decode.MediaCodecDecoder
import com.closenow.decode.MediaTekWorkaround
import com.closenow.device.DeviceOptimizer
import com.closenow.diagnostics.PerfettoTrace
import com.closenow.diagnostics.TelemetryCollector
import com.closenow.input.InputProcessor
import com.closenow.network.NetworkOptimizer
import com.closenow.network.NetworkSender
import com.closenow.network.SignalingClient
import com.closenow.network.WebRTCConfig
import com.closenow.network.WebRTCNetworkManager
import com.closenow.render.FramePacer
import com.closenow.render.GameSurfaceView
import com.closenow.render.HWCMonitor
import com.closenow.session.LatencyTracker
import com.closenow.session.ResourceManager
import com.closenow.session.SessionManager
import com.closenow.thermal.QualityController
import com.closenow.thermal.ThermalManager
import com.closenow.threading.CpuAffinity
import com.closenow.threading.ThreadManager

// Simple manual dependency injection for Phase 1 (Hilt disabled)
class AppContainerImpl constructor(private val context: Context) {
    
    // Core components - initialized in dependency order
    private val cpuAffinity: CpuAffinity = CpuAffinity()
    private val threadManager: ThreadManager = ThreadManager(cpuAffinity)
    private val webRTCConfig: WebRTCConfig = WebRTCConfig(context)
    private val networkOptimizer: NetworkOptimizer = NetworkOptimizer(context)
    private val signalingClient: SignalingClient = SignalingClient()
    private val webRTCNetworkManager: WebRTCNetworkManager = WebRTCNetworkManager(webRTCConfig, networkOptimizer, signalingClient)
    
    // These need to be lazy to avoid circular initialization
    private val decoderSelector: DecoderSelector by lazy { DecoderSelector(deviceOptimizer) }
    private val qualityController: QualityController by lazy { QualityController(decoder, webRTCNetworkManager, sessionManager) }
    private val thermalManager: ThermalManager by lazy { ThermalManager(qualityController, decoder, webRTCNetworkManager, context) }
    private val deviceOptimizer: DeviceOptimizer by lazy { DeviceOptimizer(decoderSelector, qualityController, context) }
    private val decoder: MediaCodecDecoder by lazy { MediaCodecDecoder(decoderSelector, webRTCNetworkManager, threadManager) }
    private val sessionManager: SessionManager by lazy { SessionManager(decoder, webRTCNetworkManager, qualityController, thermalManager) }
    private val inputProcessor: InputProcessor by lazy { InputProcessor(networkSender, threadManager) }
    private val networkSender: NetworkSender by lazy { NetworkSender(webRTCNetworkManager) }
    private val latencyTracker: LatencyTracker by lazy { LatencyTracker() }
    private val resourceManager: ResourceManager by lazy { ResourceManager() }
    private val perfettoTrace: PerfettoTrace by lazy { PerfettoTrace() }
    private val telemetryCollector: TelemetryCollector by lazy { TelemetryCollector() }
    private val framePacer: FramePacer by lazy { FramePacer(gameSurfaceView!!, telemetryCollector) }
    private val hwcMonitor: HWCMonitor by lazy { HWCMonitor(context) }
    private var gameSurfaceView: GameSurfaceView? = null

    fun setGameSurfaceView(view: GameSurfaceView) {
        gameSurfaceView = view
    }

    // Getters
    fun threadManager(): ThreadManager = threadManager
    fun cpuAffinity(): CpuAffinity = cpuAffinity
    fun webRTCConfig(): WebRTCConfig = webRTCConfig
    fun networkOptimizer(): NetworkOptimizer = networkOptimizer
    fun signalingClient(): SignalingClient = signalingClient
    fun webRTCNetworkManager(): WebRTCNetworkManager = webRTCNetworkManager
    fun decoderSelector(): DecoderSelector = decoderSelector
    fun deviceOptimizer(): DeviceOptimizer = deviceOptimizer
    fun decoder(): MediaCodecDecoder = decoder
    fun sessionManager(): SessionManager = sessionManager
    fun qualityController(): QualityController = qualityController
    fun thermalManager(): ThermalManager = thermalManager
    fun inputProcessor(): InputProcessor = inputProcessor
    fun networkSender(): NetworkSender = networkSender
    fun latencyTracker(): LatencyTracker = latencyTracker
    fun resourceManager(): ResourceManager = resourceManager
    fun perfettoTrace(): PerfettoTrace = perfettoTrace
    fun telemetryCollector(): TelemetryCollector = telemetryCollector
    fun framePacer(): FramePacer = framePacer
    fun hwcMonitor(): HWCMonitor = hwcMonitor
    fun gameSurfaceView(): GameSurfaceView? = gameSurfaceView
}

object AppContainerProvider {
    @Volatile
    private var INSTANCE: AppContainerImpl? = null

    fun initialize(context: Context): AppContainerImpl {
        val instance = INSTANCE
        if (instance != null) return instance
        return synchronized(this) {
            val newInstance = AppContainerImpl(context.applicationContext)
            INSTANCE = newInstance
            newInstance
        }
    }

    fun get(): AppContainerImpl {
        return INSTANCE ?: throw IllegalStateException("AppContainer not initialized. Call AppContainerProvider.initialize() first.")
    }
}