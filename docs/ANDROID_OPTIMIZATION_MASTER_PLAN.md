# Android Optimization Master Plan for OpenNOW

**Status**: Research Complete - Authoritative Implementation Plan  
**Target**: Android-first cloud gaming client for GeForce NOW  
**Baseline**: Current OpenNOW is desktop-only Electron app (no Android code exists)  
**Generated**: 2026

---

## Executive Summary

This master plan consolidates all research findings into a single authoritative implementation roadmap for OpenNOW Android. The research establishes that OpenNOW currently exists only as a desktop Electron application with **zero Android code**. The proposed Android-first architecture targets **sub-80ms end-to-end latency** with sustainable thermal performance through a combination of:

1. **Zero-copy MediaCodec + SurfaceView + Hardware Overlay** (eliminates 4-5 CPU copies, -50-83ms latency)
2. **Low-latency MediaCodec configuration** (API 30+, -16-50ms decode latency)
3. **Gaming-tuned WebRTC** (NetEq 20ms min, NACK+RTX, DSCP EF, -50-100ms jitter)
4. **Dedicated high-priority threads with big core affinity** (eliminates scheduler latency)
5. **Adaptive quality controller** (thermal + network + decoder feedback loop)
6. **Perfetto-integrated measurement-driven optimization** from day one

---

## Research Artifacts Produced

| Document | Purpose | Key Finding |
|----------|---------|-------------|
| `ANDROID_MEDIA_PIPELINE.md` | Complete pipeline architecture | Zero-copy path: MediaCodec → Surface → BufferQueue → HWC |
| `ANDROID_DECODER_RESEARCH.md` | SoC decoder matrix | H.264 universal; AV1 on Gen 3+/Tensor G3+/Dimensity 9000+ |
| `ANDROID_LOW_LATENCY_DECODING.md` | FEATURE_LowLatency API | KEY_LOW_LATENCY + PARAMETER_KEY_LOW_LATENCY (API 30+) |
| `ANDROID_RENDERING_RESEARCH.md` | SurfaceView vs TextureView | SurfaceView: -3-5 frames vs TextureView; HWC overlay possible |
| `ANDROID_MEMORY_COPY_ANALYSIS.md` | Copy accounting | 5 copies (ByteBuffer) vs 1 copy (Surface path) |
| `ANDROID_THREADING.md` | Thread map + priorities | 8 dedicated threads; big core affinity critical |
| `ANDROID_POWER_THERMAL.md` | Power/thermal model | Decoder = 73% power; thermal ladder: AV1→HEVC→H.264 Baseline |
| `ANDROID_INPUT_LATENCY.md` | Input pipeline | 4-50ms; dedicated thread + kernel timestamps |
| `ANDROID_NETWORK_RESEARCH.md` | WebRTC on Android | NetEq 20ms min; Wi-Fi lock HIGH_PERF; DSCP EF |
| `ANDROID_DIAGNOSTICS.md` | Measurement tools | Perfetto + FrameTimeline + dumpsys + custom traces |
| `ANDROID_OPENNOW_ARCHITECTURE.md` | Integrated architecture | 5-layer architecture with threading model |
| `MASTER_RESEARCH_REPORT.md` | Consolidated roadmap | 15 optimizations across 3 tiers with priority matrix |
| `CROSS_CHECK_ANALYSIS.md` | Desktop vs Android | **Zero Android code exists** - complete greenfield |
| `docs/ANDROID_OPTIMIZATION_ROADMAP.md` | Tiered implementation | TIER 0-7 with exact source files, API levels, fallbacks |
| `docs/ANDROID_DEVICE_COMPATIBILITY.md` | Device matrix | 4 SoC families, 4 codecs, known bugs documented |
| `docs/ANDROID_BENCHMARK_PLAN.md` | Measurement protocol | 8 metric categories, P50/P95/P99 targets, CI integration |
| `docs/ANDROID_ARCHITECTURE_REVIEW.md` | Current vs proposed | Complete architecture comparison |

---

## Implementation Phases

### PHASE 1: Foundation (Weeks 1-3) — TIER 0 + TIER 1 Core

**Goal**: Running Android app with zero-copy MediaCodec → SurfaceView pipeline, Perfetto instrumentation, baseline metrics.

#### Source Files to Create

```
opennow-stable/android/
├── build.gradle.kts
├── src/main/
│   ├── AndroidManifest.xml
│   ├── assets/perfetto_config.pbtx
│   ├── java/com/opennow/
│   │   ├── MainApplication.kt
│   │   ├── MainActivity.kt
│   │   ├── render/
│   │   │   ├── GameSurfaceView.kt          # SurfaceView + SurfaceHolder.Callback
│   │   │   └── HWCMonitor.kt               # dumpsys SurfaceFlinger verification
│   │   ├── decode/
│   │   │   ├── MediaCodecDecoder.kt        # MediaCodec + Surface output + async callbacks
│   │   │   └── DecoderSelector.kt          # Codec capability query + priority
│   │   ├── network/
│   │   │   └── WebRTCNetworkManager.kt     # org.webrtc wrapper
│   │   ├── telemetry/
│   │   │   ├── PerfettoTrace.kt            # Custom trace points
│   │   │   └── BaselineCollector.kt        # Device info, codec caps, metrics
│   │   └── di/                             # Dependency injection (Hilt/Koin)
│   └── cpp/                                # NDK (if needed later)
```

#### Key Implementation Details

**GameSurfaceView.kt** - SurfaceView (NOT TextureView):
```kotlin
class GameSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle), SurfaceHolder.Callback {
    
    init {
        holder.addCallback(this)
        setZOrderMediaOverlay(true)  // Dedicated SurfaceFlinger layer
        holder.setFormat(PixelFormat.RGBA_8888)  // Opaque format for HWC
    }
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        sessionManager.onSurfaceReady(holder.surface)
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        sessionManager.onSurfaceSizeChanged(w, h)
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        sessionManager.onSurfaceDestroyed()
    }
}
```

**MediaCodecDecoder.kt** - Zero-copy Surface path:
```kotlin
class MediaCodecDecoder @Inject constructor() {
    private var codec: MediaCodec? = null
    private var decoderThread: HandlerThread? = null
    private var handler: Handler? = null
    
    fun start(config: DecoderConfig) {
        decoderThread = HandlerThread("OpenNOW-Decoder").apply {
            priority = android.os.Process.THREAD_PRIORITY_URGENT_AUDIO  // -16
            start()
        }
        handler = Handler(decoderThread.looper)
        
        val format = MediaFormat.createVideoFormat(config.mimeType, config.width, config.height)
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)  // API 30+
        
        codec = MediaCodec.createDecoderByType(config.mimeType)
        codec!!.configure(format, config.surface, null, 0)
        
        codec!!.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                networkLayer.fillInputBuffer(index)  // WebRTC provides encoded data
            }
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: BufferInfo) {
                codec.releaseOutputBuffer(index, info.presentationTimeUs)  // render=true
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                sessionManager.onDecoderError(e)
            }
        }, handler!!)
        
        codec!!.start()
        
        // Runtime low-latency (belt-and-suspenders)
        val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_LOW_LATENCY, 1) }
        codec!!.setParameters(params)
    }
}
```

**WebRTCNetworkManager.kt** - Gaming-tuned config:
```kotlin
class WebRTCNetworkManager @Inject constructor() {
    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    
    fun initialize() {
        val options = PeerConnectionFactory.Options().apply {
            networkIgnoreMask = 0
        }
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
        
        // NetEq gaming config via constraints
        val pcConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googJitterBufferMinDelayMs", "20"))
            mandatory.add(MediaConstraints.KeyValuePair("googJitterBufferMaxDelayMs", "100"))
            mandatory.add(MediaConstraints.KeyValuePair("googDegradationPreference", "maintain_framerate"))
        }
        
        // Wi-Fi lock
        val wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OpenNOW-Gaming"
        ).apply { acquire() }
    }
}
```

#### Success Criteria (Phase 1)

| Criterion | Target | Verification |
|-----------|--------|--------------|
| App builds | `./gradlew assembleDebug` succeeds | CI green |
| App runs | Launches on Android 11+ device | Manual test |
| Video displays | 1080p60 H.264 stream visible | Visual confirmation |
| Perfetto traces | `MediaCodec.*` + `SurfaceFlinger.*` sections visible | `ui.perfetto.dev` |
| Hardware overlay | `dumpsys SurfaceFlinger --list` shows HWC for game layer | `adb shell` |
| HW decoder | `dumpsys media.codec` shows HW decoder (not SW) | `adb shell` |
| Zero-copy path | No `getInputBuffer`/`getOutputBuffer` calls in trace | Perfetto SQL |

---

### PHASE 2: Core Pipeline Optimization (Weeks 4-6) — TIER 1-2

**Goal**: Sub-100ms E2E latency, <8ms decode P95, <0.5% frame drops, MediaTek workaround.

#### Additional Source Files

```
opennow-stable/android/src/main/java/com/opennow/
├── network/
│   ├── NetworkOptimizer.kt           # Wi-Fi lock, DSCP EF, socket buffers
│   └── WebRTCConfig.kt               # NetEq 20ms, NACK+RTX, GCC gaming
├── decode/
│   └── MediaTekWorkaround.kt         # Android 15 HEVC black screen fix
├── threading/
│   └── ThreadManager.kt              # Dedicated threads + big core affinity
└── thermal/
    └── QualityController.kt          # Adaptive quality (thermal + network + decoder)
```

#### Key Implementation Details

**ThreadManager.kt** - Dedicated high-priority threads:
```kotlin
class ThreadManager @Inject constructor() {
    private val networkThread = Thread(networkRunnable).apply {
        name = "OpenNOW-Network"
        priority = android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
        start()
    }
    
    private val decoderThread = Thread(decoderRunnable).apply {
        name = "OpenNOW-Decoder"
        priority = -12
        start()
    }
    
    private val inputThread = Thread(inputRunnable).apply {
        name = "OpenNOW-Input"
        priority = Thread.MAX_PRIORITY
        start()
    }
    
    // Best-effort big core affinity
    private fun pinToBigCores(thread: Thread) {
        try {
            val cpuMask = getBigCoreMask()  // Parse /sys/devices/system/cpu/cpu*/topology/
            Os.sched_setaffinity(0, cpuMask)
        } catch (e: ErrnoException) {
            logWarning("CPU affinity not available: $e")
        }
    }
}
```

**MediaTekWorkaround.kt** - Android 15 HEVC fix:
```kotlin
object MediaTekWorkaround {
    fun isAffected(): Boolean = 
        Build.HARDWARE.lowercase().contains("mtk") && 
        Build.VERSION.SDK_INT >= 35 &&  // Android 15
        listOf("mt6769", "mt6833", "mt6853", "mt6873").any { 
            Build.HARDWARE.lowercase().contains(it) 
        }  // Dimensity 700/900/1080
    
    fun applyIfNeeded(decoderSelector: DecoderSelector) {
        if (isAffected()) {
            decoderSelector.disableHEVCHardware()
            logWarning("MediaTek Android 15 HEVC workaround active")
        }
    }
}
```

**QualityController.kt** - Adaptive quality:
```kotlin
class QualityController @Inject constructor(
    private val sessionManager: SessionManager,
    private val decoder: MediaCodecDecoder,
    private val webRTC: WebRTCNetworkManager
) {
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        val adjustment = when (status) {
            PowerManager.THERMAL_STATUS_CRITICAL -> QualityAdjustment.DRASTIC_REDUCTION
            PowerManager.THERMAL_STATUS_SEVERE -> QualityAdjustment.MAJOR_REDUCTION
            PowerManager.THERMAL_STATUS_MODERATE -> QualityAdjustment.MODERATE_REDUCTION
            PowerManager.THERMAL_STATUS_LIGHT -> QualityAdjustment.MINOR_REDUCTION
            else -> QualityAdjustment.NONE
        }
        
        // Network constraints
        if (metrics.packetLoss > 0.02) adjustment = adjustment.combine(REDUCE_BITRATE)
        if (metrics.rttMs > 50) adjustment = adjustment.combine(REDUCE_FPS)
        if (metrics.bandwidthMbps < targetBitrate * 0.8) adjustment = adjustment.combine(REDUCE_RESOLUTION)
        
        // Decoder constraints
        if (metrics.decodeLatencyMs > 8) adjustment = adjustment.combine(SWITCH_TO_SIMPLE_CODEC)
        if (metrics.droppedFrames > 0.05) adjustment = adjustment.combine(REDUCE_FPS)
        
        applyAdjustment(adjustment)
    }
    
    private fun applyAdjustment(adj: QualityAdjustment) {
        when {
            adj.has(REDUCE_RESOLUTION) -> webRTC.setTargetResolution(1280, 720)
            adj.has(REDUCE_FPS) -> {
                webRTC.setTargetFps(30)
                surface.setFrameRate(30.0f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            adj.has(REDUCE_BITRATE) -> webRTC.setTargetBitrate(targetBitrate * 0.7)
            adj.has(SWITCH_TO_SIMPLE_CODEC) -> decoder.switchToH264Baseline()
        }
    }
}
```

#### Success Criteria (Phase 2)

| Metric | Target | Verification |
|--------|--------|--------------|
| E2E Latency P50 | < 100ms | Perfetto + FrameTimeline |
| Decode Latency P95 | < 8ms | MediaCodec callback timestamps |
| Frame Drop Rate | < 0.5% | `dumpsys gfxinfo` / FrameTimeline |
| Jitter Buffer Delay | < 30ms | WebRTC stats `jitterBufferDelayMs` |
| MediaTek Android 15 | No black screen | Test on affected device |
| NACK+RTX | Visible in SDP/stats | WebRTC stats `rtxPacketsSent` |
| NetEq Min Delay | 20ms configured | WebRTC stats `jitterBufferDelayMs` |

---

### PHASE 3: Threading, Input, Thermal, Device-Specific (Weeks 7-9) — TIER 3-7

**Goal**: Input latency < 20ms (USB), 30min sustained no throttle, device-specific optimizations.

#### Additional Source Files

```
opennow-stable/android/src/main/java/com/opennow/
├── input/
│   ├── InputProcessor.kt           # Dedicated thread, kernel timestamps
│   ├── InputEncoder.kt             # Protocol v3 (shared from @shared/gfn)
│   ├── GamepadMapper.kt            # VID:PID → standard mapping
│   └── TouchHandler.kt             # SurfaceView direct touch
├── device/
│   ├── QualcommOptimizer.kt        # Venus VPU, LTR keys, thermal zones
│   ├── MediaTekOptimizer.kt        # AV1 on 9000+, HEVC workaround
│   ├── ExynosOptimizer.kt          # AV1 validation, thermal monitoring
│   └── TensorOptimizer.kt          # AV1 preferred, Pixel vapor chamber
├── thermal/
│   └── ThermalMonitor.kt           # Perfetto power tracking + dumpsys thermal
└── diagnostics/
    ├── FrameTimelineObserver.kt    # API 33+ per-frame deadline tracking
    └── PerfettoQueries.kt          # Automated SQL analysis
```

#### Key Implementation Details

**InputProcessor.kt** - Dedicated input thread:
```kotlin
class InputProcessor @Inject constructor(
    private val networkSender: NetworkSender
) {
    private val queue = ArrayBlockingQueue<InputEvent>(1024)
    private val workerThread = Thread(::processLoop).apply {
        name = "OpenNOW-Input"
        priority = Thread.MAX_PRIORITY
        start()
    }
    
    fun onTouchEvent(event: MotionEvent) {
        val timestampUs = event.eventTime * 1000L  // Kernel CLOCK_MONOTONIC
        queue.offer(InputEvent.Touch(event, timestampUs))
    }
    
    fun onGamepadEvent(event: MotionEvent) {
        val timestampUs = event.eventTime * 1000L
        queue.offer(InputEvent.Gamepad(event, timestampUs))
    }
    
    private fun processLoop() {
        while (running) {
            val event = queue.take()
            val packet = encodeInputPacket(event, event.timestampUs)
            networkSender.sendImmediate(packet)  // UDP, DSCP EF, no batching
        }
    }
}
```

**Device-Specific Optimizers** - Auto-detect at startup:
```kotlin
class DeviceOptimizer @Inject constructor(
    private val decoderSelector: DecoderSelector,
    private val qualityController: QualityController
) {
    fun applyOptimizations() {
        when {
            isSnapdragon() -> QualcommOptimizer.apply(decoderSelector, qualityController)
            isMediaTek() -> MediaTekOptimizer.apply(decoderSelector, qualityController)
            isExynos() -> ExynosOptimizer.apply(decoderSelector, qualityController)
            isTensor() -> TensorOptimizer.apply(decoderSelector, qualityController)
        }
    }
    
    private fun isSnapdragon() = Build.HARDWARE.lowercase().contains("qcom") || 
                                 Build.HARDWARE.lowercase().contains("sm8")
    
    private fun isMediaTek() = Build.HARDWARE.lowercase().contains("mtk") || 
                               Build.HARDWARE.lowercase().contains("mt6")
    
    private fun isExynos() = Build.HARDWARE.lowercase().contains("exynos") || 
                             Build.HARDWARE.lowercase().contains("s5e")
    
    private fun isTensor() = Build.HARDWARE.lowercase().contains("google") || 
                             Build.HARDWARE.lowercase().contains("gs")
}
```

#### Success Criteria (Phase 3)

| Metric | Target | Verification |
|--------|--------|--------------|
| Input Latency (USB) | < 20ms | High-speed camera LED test |
| Input Latency (BT 5.0) | < 35ms | High-speed camera LED test |
| Thermal Stability | 30min no throttle | `dumpsys thermal` + CPU freq |
| Big Core Affinity | Decoder on big cores | Perfetto CPU Scheduling track |
| Quality Adaptation | Triggers on thermal events | Telemetry logs |
| Device Codec Selection | Correct per SoC | `dumpsys media.codec` + logs |

---

### PHASE 4: Advanced & Production Hardening (Weeks 10-12) — Polish

**Goal**: Production-ready with CI regression detection, crash reporting, telemetry.

#### Additional Source Files

```
opennow-stable/android/src/main/java/com/opennow/
├── diagnostics/
│   ├── AutomatedBenchmark.kt       # CI benchmark runner
│   └── CrashReporter.kt            # Crashlytics / custom
├── ui/                             # Compose or WebView for settings
│   ├── GameOverlayView.kt          # Minimal UI overlays
│   └── SettingsScreen.kt           # Port from React
├── networking/
│   └── SessionManager.kt           # Full lifecycle + reconnection
└── build/
    └── AndroidReleaseConfig.kt     # ProGuard, signing, app bundles
```

#### CI Regression Detection

```yaml
# .github/workflows/android-benchmark.yml
name: Android Benchmark
on: [push, pull_request]
jobs:
  benchmark:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Connect device
        run: adb devices
      - name: Build & Install
        run: ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
      - name: Run Benchmark
        run: python benchmark_runner.py --test baseline --duration 300
      - name: Check Regression
        run: |
          python check_regression.py \
            --threshold latency_p50=10% \
            --threshold frame_drop_rate=0.5% \
            --threshold decode_latency_p95=8ms
```

#### Success Criteria (Phase 4)

| Criterion | Target |
|-----------|--------|
| All Phase 3 targets met on 5+ devices | Verified |
| CI regression detection | Blocks merge on thresholds |
| Crash reporting | < 0.1% crash rate |
| Telemetry upload | > 95% sessions |
| App size | < 100MB (including WebRTC ~20MB) |
| Cold start | < 3s to stream |
| Background/foreground | Seamless resume |

---

## Measurement Targets (Final Acceptance Criteria)

| Metric | P50 Target | P95 Target | P99 Target | Method |
|--------|------------|------------|------------|--------|
| **End-to-End Latency** | < 80ms | < 100ms | < 120ms | High-speed camera / FrameTimeline |
| **Decode Latency** | < 4ms | < 8ms | < 12ms | MediaCodec callbacks |
| **Frame Drop Rate** | < 0.1% | < 0.5% | < 1% | `dumpsys gfxinfo` / FrameTimeline |
| **Jitter Buffer Delay** | < 20ms | < 30ms | < 50ms | WebRTC stats |
| **Input Latency (USB)** | < 15ms | < 20ms | < 30ms | High-speed camera |
| **Input Latency (BT 5.0)** | < 25ms | < 35ms | < 50ms | High-speed camera |
| **Hardware Overlay Rate** | 100% | 100% | 100% | `dumpsys SurfaceFlinger` |
| **HW Decoder Usage** | 100% | 100% | 100% | `dumpsys media.codec` |
| **Thermal Stability** | 30min | 30min | 30min | `dumpsys thermal` + CPU freq |
| **Battery Life (Flagship)** | > 2h | > 1.5h | > 1h | `dumpsys batterystats` |

---

## Risk Register & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| WebRTC SDK binary size (~20MB) | High | App size | Acceptable for gaming; consider dynamic feature module |
| MediaCodec vendor bugs/crashes | Medium | Session fail | Per-device blacklist; graceful fallback to next codec |
| Low-latency decode unsupported on some HW | Medium | Fallback to normal | Capability query + runtime validation; disable per device |
| Thermal throttling on sustained high load | High | Unplayable | Adaptive quality; max 1080p@60 default; FPS reduction |
| SurfaceView Z-order limitations | Low | UI constraints | Design overlays carefully; `setZOrderMediaOverlay` |
| Hardware overlay unavailable on some devices | Low | Falls back to GPU | Verify at runtime; log warning; allow GPU fallback |
| CPU affinity requires root/privileged | Medium | Threads on LITTLE cores | Best-effort; log if denied; priority still helps |
| FrameTimeline only API 33+ | Medium | No per-frame tracking | Fallback to `dumpsys gfxinfo` percentiles |
| AV1 HW not available on all devices | High | Codec negotiation fails | Priority order: H.264 > HEVC > VP9 > AV1 |
| MediaTek Android 15 HEVC black screen | High (known bug) | Session fail | Detect device+OS; force H.264 or SW HEVC |
| DSCP marking stripped by ISP/router | Medium | No QoS benefit | Still set; no harm; Wi-Fi lock primary |

---

## Dependencies & Prerequisites

### Android SDK/NDK
- **minSdk**: 30 (Android 11) - required for `KEY_LOW_LATENCY`
- **targetSdk**: 34+ (latest)
- **NDK**: r26+ (for potential AHardwareBuffer/Vulkan)

### Key Dependencies
```kotlin
// build.gradle.kts
dependencies {
    // WebRTC
    implementation("org.webrtc:google-webrtc:1.0.32006")
    
    // Perfetto (built into Android, no extra dep needed)
    // Custom trace points use android.os.Trace
    
    // DI
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Serialization (for shared types)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
```

### Shared Types Strategy
- Option A: Kotlin Multiplatform - compile `@shared/gfn` TypeScript to Kotlin
- Option B: Code generation - `ts-to-kotlin` or custom script
- Option C: Manual port - maintain parity manually (simpler for now)

---

## Team & Timeline Summary

| Phase | Duration | Focus | Key Deliverable |
|-------|----------|-------|-----------------|
| **Phase 1** | Weeks 1-3 | Foundation | Running Android app with zero-copy pipeline, Perfetto traces |
| **Phase 2** | Weeks 4-6 | Core Pipeline | <100ms E2E latency, <8ms decode, MediaTek fix |
| **Phase 3** | Weeks 7-9 | Threading/Input/Thermal | <20ms input, 30min thermal stability, device-specific |
| **Phase 4** | Weeks 10-12 | Production | CI regression, crash reporting, 5-device validation |

**Total**: 12 weeks (3 months) to production-ready Android client

---

## References

All research documents in repository root and `docs/`:
- `ANDROID_MEDIA_PIPELINE.md` - Pipeline architecture
- `ANDROID_DECODER_RESEARCH.md` - SoC decoder matrix
- `ANDROID_LOW_LATENCY_DECODING.md` - Low-latency API
- `ANDROID_RENDERING_RESEARCH.md` - SurfaceView vs TextureView
- `ANDROID_MEMORY_COPY_ANALYSIS.md` - Copy accounting
- `ANDROID_THREADING.md` - Thread map + priorities
- `ANDROID_POWER_THERMAL.md` - Power/thermal model
- `ANDROID_INPUT_LATENCY.md` - Input pipeline
- `ANDROID_NETWORK_RESEARCH.md` - WebRTC Android
- `ANDROID_DIAGNOSTICS.md` - Perfetto + dumpsys
- `ANDROID_OPENNOW_ARCHITECTURE.md` - Integrated architecture
- `MASTER_RESEARCH_REPORT.md` - Consolidated roadmap
- `CROSS_CHECK_ANALYSIS.md` - Desktop vs Android reality
- `docs/ANDROID_OPTIMIZATION_ROADMAP.md` - Tiered implementation
- `docs/ANDROID_DEVICE_COMPATIBILITY.md` - Device matrix
- `docs/ANDROID_BENCHMARK_PLAN.md` - Measurement protocol
- `docs/ANDROID_ARCHITECTURE_REVIEW.md` - Current vs proposed

---

**This master plan is the authoritative implementation reference. All implementation work should reference this document and the linked research artifacts.**