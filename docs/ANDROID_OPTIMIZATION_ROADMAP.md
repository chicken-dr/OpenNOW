# Android Optimization Roadmap for OpenNOW

**Status**: Research Phase - No Implementation Yet  
**Target**: Android-first cloud gaming client for GeForce NOW  
**Baseline**: Current OpenNOW is desktop-only Electron app (no Android code exists)

---

## TIER 0 — Baseline & Instrumentation (Foundation)

### 0.1 Android Project Setup
**Source Files to Create:**
- `opennow-stable/android/` (new module)
- `opennow-stable/android/build.gradle.kts`
- `opennow-stable/android/src/main/AndroidManifest.xml`
- `opennow-stable/android/src/main/java/com/opennow/...`
- `settings.gradle.kts` (include android module)

**Mechanism:**
- New Gradle module alongside existing Electron build
- Share `@shared/gfn` types via Kotlin Multiplatform or generated Kotlin from TypeScript
- Minimum SDK: API 30 (Android 11) for low-latency MediaCodec
- Target SDK: Latest stable

**Expected Effect:** Enables all subsequent Android work
**CPU/GPU/Memory Cost:** N/A (infrastructure)
**Compatibility Risks:** None
**API Level:** 30+ (low-latency MediaCodec requires API 30)
**Fallback:** N/A
**Benchmark:** Successful build, `./gradlew assembleDebug` completes
**Default:** N/A (infrastructure)

---

### 0.2 Perfetto Telemetry Integration
**Source Files to Create:**
- `opennow-stable/android/src/main/java/com/opennow/telemetry/PerfettoTrace.kt`
- `opennow-stable/android/assets/perfetto_config.pbtx`
- Custom trace points in all pipeline stages

**Mechanism:**
- `android.os.Trace` (Java) / `ATrace_beginSection` (NDK) for custom sections
- Perfetto config with: `linux.ftrace` (sched, freq, idle), `android.atrace` (gfx, view, video, binder), `android.frame_timeline`, `android.gpu`, `android.cpu`, `android.power`
- In-app trace markers: `WebRTC.recvfrom`, `WebRTC.rtpParse`, `MediaCodec.dequeueInputBuffer`, `MediaCodec.queueInputBuffer`, `MediaCodec.dequeueOutputBuffer`, `MediaCodec.releaseOutputBuffer`, `Input.processTouch`, `Input.sendPacket`

**Expected Effect:** Measurement-driven optimization from day 1
**CPU/GPU/Memory Cost:** ~1-2% CPU overhead during tracing; ~64MB buffer
**Compatibility Risks:** API 29+ for full Perfetto; API 33+ for FrameTimeline
**API Level:** 29+ (Perfetto), 33+ (FrameTimeline)
**Fallback:** Disable custom traces on older API; use `atrace` CLI
**Benchmark:** Trace loads in Perfetto UI (`ui.perfetto.dev`); all custom sections visible; SQL queries return data
**Default:** ENABLED (debug builds); DISABLED (release builds for performance)

---

### 0.3 Baseline Metrics Collection
**Source Files to Create:**
- `opennow-stable/android/src/main/java/com/opennow/metrics/BaselineCollector.kt`

**Mechanism:**
- On session start: record device model, Android version, SoC, MediaCodecList capabilities, thermal baseline, battery level
- During session: frame latency (P50/P95/P99), decode latency, jitter buffer delay, RTT, packet loss, thermal state, CPU freq, GPU freq
- On session end: save Perfetto trace, `dumpsys gfxinfo`, `dumpsys SurfaceFlinger --latency`, `dumpsys media.codec`, `dumpsys batterystats`, `dumpsys thermal`

**Expected Effect:** Establishes baseline for all future comparisons
**CPU/GPU/Memory Cost:** Minimal (sampling)
**Compatibility Risks:** `dumpsys` requires no special permission
**API Level:** 21+ (MediaCodecList), 30+ (thermal API)
**Fallback:** Skip unavailable metrics on older API
**Benchmark:** All metrics collected without crashes; data exportable
**Default:** ENABLED

---

## TIER 1 — Safe Low-Risk Optimizations (Quick Wins)

### 1.1 SurfaceView + MediaCodec Surface Output (Zero-Copy Decode)
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/render/GameSurfaceView.kt`
- `opennow-stable/android/src/main/java/com/opennow/decode/MediaCodecDecoder.kt`

**Mechanism:**
```kotlin
// GameSurfaceView - SurfaceView (NOT TextureView)
class GameSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) {
        decoder.configure(format, holder.surface, null, 0)  // Surface output
    }
}

// MediaCodecDecoder - configure with Surface
format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)  // API 30+
codec.configure(format, surface, null, 0)
codec.setCallback(callback, decoderHandler)  // Async callbacks
codec.start()
// Runtime low-latency
codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_LOW_LATENCY, 1) })
```

**Expected Effect:** Eliminates 4-5 CPU copies/frame (900MB/s to 360MB/s at 1080p60); -3 to -5 frames latency (50-83ms)
**CPU/GPU/Memory Cost:** -60% memory bandwidth; decoder internal buffers unchanged
**Compatibility Risks:** SurfaceView Z-order limitations (overlays must be separate Views); surface destroyed when hidden (use `setZOrderMediaOverlay(true)`)
**API Level:** 16+ (Surface output), 21+ (async callbacks), 30+ (KEY_LOW_LATENCY)
**Fallback:** If Surface output fails to TextureView (with latency penalty logged)
**Benchmark:** `dumpsys SurfaceFlinger --list` shows HWC composition; Perfetto `MediaCodec.*` traces show zero-copy path
**Default:** ENABLED

---

### 1.2 Hardware Overlay Enforcement
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/render/GameSurfaceView.kt` (same as 1.1)
- `opennow-stable/android/src/main/java/com/opennow/render/HWCMonitor.kt`

**Mechanism:**
- `surfaceView.setZOrderMediaOverlay(true)` - places SurfaceView on dedicated layer
- `holder.setFormat(PixelFormat.RGBA_8888)` or opaque format
- Full-screen, no transform, opaque qualifies for HWC overlay
- `HWCMonitor` verifies via `dumpsys SurfaceFlinger --list` (composition type = HWC)

**Expected Effect:** Eliminates GPU composition frame (-1 frame / 16ms); -2-5% total power
**CPU/GPU/Memory Cost:** GPU composition offloaded to display controller
**Compatibility Risks:** Some OEMs have buggy HWC; fallback to GPU composition if frames drop
**API Level:** 16+ (SurfaceView), 23+ (setZOrderMediaOverlay)
**Fallback:** Allow GPU composition if HWC causes artifacts; log warning
**Benchmark:** `dumpsys SurfaceFlinger --list` shows "HWC" for game layer; `dumpsys gfxinfo` shows no GPU time for game layer
**Default:** ENABLED

---

### 1.3 Gaming-Tuned WebRTC Configuration
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/network/WebRTCNetworkManager.kt`

**Mechanism:**
```kotlin
// PeerConnectionFactory options
val options = PeerConnectionFactory.Options().apply {
    networkIgnoreMask = 0  // Use all interfaces
}

// NetEq minimum delay for gaming
val audioConstraints = MediaConstraints().apply {
    mandatory.add(MediaConstraints.KeyValuePair("googJitterBufferMinDelayMs", "20"))
    mandatory.add(MediaConstraints.KeyValuePair("googJitterBufferMaxDelayMs", "100"))
}

// Video receive params - maintain framerate
val videoConstraints = MediaConstraints().apply {
    mandatory.add(MediaConstraints.KeyValuePair("googDegradationPreference", "maintain_framerate"))
}

// DSCP EF on sockets (requires custom WebRTC build or NDK socket wrapper)
// Wi-Fi lock
val wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OpenNOW-Gaming")
wifiLock.acquire()
```

**Expected Effect:** -50-100ms jitter buffer; better loss recovery via NACK+RTX
**CPU/GPU/Memory Cost:** Slightly higher CPU for aggressive NACK; Wi-Fi lock increases power ~100-200mW
**Compatibility Risks:** DSCP marking may be stripped by some routers; `WIFI_MODE_FULL_HIGH_PERF` not `LOW_LATENCY` (higher power but more stable)
**API Level:** 21+ (WebRTC SDK), 24+ (WifiLock modes)
**Fallback:** Default NetEq (100ms) if gaming config causes instability
**Benchmark:** WebRTC stats `jitterBufferDelayMs` < 30ms; packet loss < 0.5%; RTT < 30ms
**Default:** ENABLED

---

### 1.4 Codec Negotiation Strategy
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/decode/DecoderSelector.kt`
- `opennow-stable/android/src/main/java/com/opennow/network/WebRTCNetworkManager.kt` (SDP preference)

**Mechanism:**
```kotlin
// Client preference order (SDP munging)
val PREFERRED_CODECS = listOf("H264", "HEVC", "VP9", "AV1")

// Capability query at startup
fun queryDecoderCapabilities(): Map<String, DecoderCapability> {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    return list.codecInfos
        .filter { !it.isEncoder }
        .map { info ->
            val caps = info.getCapabilitiesForType(mimeType)
            info.name to DecoderCapability(
                isHardware = info.isHardwareAccelerated(),
                isVendor = info.isVendor(),
                supportsLowLatency = caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency),
                colorFormats = caps.colorFormats,
                profiles = caps.profileLevels
            )
        }.toMap()
}

// Device-specific fallbacks
when {
    isMediaTek && android.os.Build.VERSION.SDK_INT >= 35 -> disableHEVC()  // Android 15 bug
    isExynos2200 -> testAV1HW() ?: preferHEVC()
    isTensorG3Plus -> preferAV1()
}
```

**Expected Effect:** Optimal HW codec per device; avoids SW fallback
**CPU/GPU/Memory Cost:** One-time query at startup (~10ms)
**Compatibility Risks:** MediaTek Android 15 HEVC black screen (known bug); Exynos AV1 may fall back to SW
**API Level:** 21+ (MediaCodecList), 29+ (isHardwareAccelerated), 30+ (FEATURE_LowLatency)
**Fallback:** Next codec in priority list
**Benchmark:** `dumpsys media.codec` shows HW codec; zero SW decoder usage
**Default:** ENABLED

---

## TIER 2 — Media/Decoder Pipeline (Core Latency)

### 2.1 Low-Latency MediaCodec Configuration (API 30+)
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/decode/MediaCodecDecoder.kt`

**Mechanism:**
```kotlin
// 1. Configure-time low-latency
val format = MediaFormat.createVideoFormat(mimeType, width, height)
format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)  // API 30+

// 2. Surface output for zero-copy
codec.configure(format, surface, null, 0)
codec.setCallback(object : MediaCodec.Callback() {
    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        // Fill input buffer from WebRTC
    }
    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: BufferInfo) {
        codec.releaseOutputBuffer(index, info.presentationTimeUs)  // render=true
    }
}, decoderHandler)
codec.start()

// 3. Runtime low-latency (belt-and-suspenders)
val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_LOW_LATENCY, 1) }
codec.setParameters(params)
```

**Expected Effect:** -1 to -3 frames decode latency (16-50ms); reduces decoder internal buffering
**CPU/GPU/Memory Cost:** Slight decoder power increase (~1-3%); more frequent VPU wakeups
**Compatibility Risks:** Not all HW decoders support it; vendor may ignore; test per device
**API Level:** 30+ (both keys)
**Fallback:** Graceful degradation - continue without low-latency if `setParameters` throws
**Benchmark:** MediaCodec callback timestamps: decode latency P95 < 8ms; Perfetto `MediaCodec.dequeueOutputBuffer` duration
**Default:** ENABLED (if `FEATURE_LowLatency` supported)

---

### 2.2 MediaTek Android 15 HEVC Workaround
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/decode/DecoderSelector.kt`

**Mechanism:**
```kotlin
// Detect affected devices
val isAffectedMediaTek = 
    Build.HARDWARE.lowercase().contains("mtk") && 
    Build.VERSION.SDK_INT >= 35 &&  // Android 15
    listOf("mt6769", "mt6833", "mt6853", "mt6873").any { Build.HARDWARE.lowercase().contains(it) }
// Dimensity 700/900/1080

if (isAffectedMediaTek) {
    disableHEVCHardware()  // Force H.264 or SW HEVC
    logWarning("MediaTek Android 15 HEVC workaround active")
}
```

**Expected Effect:** Prevents black screen + audio-only failure
**CPU/GPU/Memory Cost:** H.264 uses ~10-20% more bitrate for same quality
**Compatibility Risks:** None (workaround for known bug)
**API Level:** 35+ (Android 15)
**Fallback:** SW HEVC if H.264 not available (rare)
**Benchmark:** HEVC sessions on affected devices show video (not black); `dumpsys media.codec` shows H.264 decoder
**Default:** ENABLED (automatic detection)

---

### 2.3 Buffer Pool Pre-allocation
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/network/WebRTCNetworkManager.kt`
- `opennow-stable/android/src/main/java/com/opennow/decode/MediaCodecDecoder.kt`

**Mechanism:**
```kotlin
// WebRTC: Pre-allocate packet/frame buffers at session start
// NetEq internal pool configured via PeerConnectionFactory

// MediaCodec: Buffers allocated by codec on configure()
// Ensure sufficient input/output buffer count
format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, calculateMaxInputSize())
// Codec allocates buffers internally; async callbacks avoid polling
```

**Expected Effect:** Eliminates GC pressure during streaming; smoother frame pacing
**CPU/GPU/Memory Cost:** ~10-20MB pre-allocated (fixed)
**Compatibility Risks:** None
**API Level:** 21+ (async callbacks)
**Fallback:** N/A (standard pattern)
**Benchmark:** Perfetto `dalvik` track shows no GC during streaming; frame time variance reduced
**Default:** ENABLED

---

## TIER 3 — Memory/Copy Reduction (Bandwidth & Power)

### 3.1 Eliminate ByteBuffer Path (Enforce Surface Path)
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/decode/MediaCodecDecoder.kt`

**Mechanism:**
- **HARD REQUIREMENT**: Never call `codec.getInputBuffer()` or `codec.getOutputBuffer()`
- Only use `codec.configure(format, surface, ...)` + `releaseOutputBuffer(index, true)`
- If native streamer needed: use `AMediaCodec_setOutputSurface` (NDK) - still BufferQueue

**Expected Effect:** -4 CPU copies/frame (900MB/s to 360MB/s to 180MB/s with overlay)
**CPU/GPU/Memory Cost:** Zero additional
**Compatibility Risks:** None if SurfaceView used
**API Level:** 16+ (Surface output)
**Fallback:** N/A (architectural requirement)
**Benchmark:** Perfetto trace shows no `MediaCodec.getInputBuffer`/`getOutputBuffer` calls
**Default:** ENABLED (enforced by architecture)

---

### 3.2 AHardwareBuffer + Vulkan (Advanced - Optional)
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/render/VulkanRenderer.kt` (NDK)
- `opennow-stable/android/src/main/cpp/vulkan_renderer.cpp`

**Mechanism:**
```c
// NDK MediaCodec + AHardwareBuffer
AMediaCodec* codec = AMediaCodec_createDecoderByType("video/avc");
AMediaFormat* format = AMediaFormat_new();
AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);
AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_LOW_LATENCY, 1);

// Use AMediaImageReader for AHardwareBuffer output
AImageReader* reader = AImageReader_new(width, height, AIMAGE_FORMAT_YUV_420_888, 4);
ANativeWindow* nativeWindow = AImageReader_getWindow(reader);
AMediaCodec_configure(codec, format, nativeWindow, NULL, 0);

// In render loop:
AImage* image = nullptr;
AImageReader_acquireLatestImage(reader, &image);
AHardwareBuffer* buffer = nullptr;
AImage_getHardwareBuffer(image, &buffer);
// Import to Vulkan via VK_ANDROID_external_memory_android_hardware_buffer
```

**Expected Effect:** Zero-copy to Vulkan for custom post-processing/VRR; maximum control
**CPU/GPU/Memory Cost:** High implementation complexity; Vulkan sync overhead
**Compatibility Risks:** Requires `VK_ANDROID_external_memory_android_hardware_buffer` (API 29+); not all devices support
**API Level:** 29+ (AHardwareBuffer), 29+ (Vulkan external memory)
**Fallback:** Standard SurfaceView path
**Benchmark:** Perfetto shows zero CPU copies; Vulkan GPU time < 2ms/frame
**Default:** DISABLED (enable only if custom render needed)

---

## TIER 4 — Threading/Scheduling (Scheduler Latency)

### 4.1 Dedicated High-Priority Threads
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/threading/ThreadManager.kt`

**Mechanism:**
```kotlin
// Network thread
val networkThread = Thread(networkRunnable).apply {
    name = "OpenNOW-Network"
    priority = android.os.Process.THREAD_PRIORITY_URGENT_AUDIO  // -16
    start()
}

// Decoder thread
val decoderThread = Thread(decoderRunnable).apply {
    name = "OpenNOW-Decoder"
    priority = -12  // Custom high
    start()
}

// Input thread
val inputThread = Thread(inputRunnable).apply {
    name = "OpenNOW-Input"
    priority = Thread.MAX_PRIORITY  // 10
    start()
}

// Big core affinity (requires root or privileged; best effort)
fun pinToBigCores(thread: Thread) {
    try {
        val cpuMask = getBigCoreMask()  // Parse /sys/devices/system/cpu/cpu*/topology/thread_siblings
        Os.sched_setaffinity(0, cpuMask)
    } catch (e: ErrnoException) {
        logWarning("Could not set CPU affinity: $e")
    }
}
```

**Expected Effect:** Eliminates scheduler latency (1-5ms); prevents LITTLE core migration under thermal
**CPU/GPU/Memory Cost:** Threads consume minimal memory; big cores use more power when active
**Compatibility Risks:** `sched_setaffinity` requires `CAP_SYS_NICE` (root/privileged); CFS priority limited to -20..19
**API Level:** 21+ (Thread priorities), 28+ (Os.sched_setaffinity)
**Fallback:** Run without affinity if permission denied; log warning
**Benchmark:** Perfetto CPU Scheduling track shows threads on big cores; wakeup latency < 1ms
**Default:** ENABLED (priority); AFFINITY BEST-EFFORT

---

### 4.2 MediaCodec Async Callbacks (No Polling)
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/decode/MediaCodecDecoder.kt`

**Mechanism:**
```kotlin
// Use Callback API (API 21+) - NOT polling loop
val handler = Handler(decoderThread.looper)
codec.setCallback(object : MediaCodec.Callback() {
    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        // WebRTC fills buffer immediately
    }
    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: BufferInfo) {
        codec.releaseOutputBuffer(index, info.presentationTimeUs)
    }
    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
        // Handle error, request keyframe
    }
}, handler)
```

**Expected Effect:** Eliminates polling overhead (~1-2ms/frame); better responsiveness
**CPU/GPU/Memory Cost:** Callback runs on decoder thread (already dedicated)
**Compatibility Risks:** None (standard API)
**API Level:** 21+
**Fallback:** N/A (required)
**Benchmark:** Perfetto shows no `dequeueInputBuffer`/`dequeueOutputBuffer` blocking calls
**Default:** ENABLED

---

## TIER 5 — Network/Jitter/Input Latency

### 5.1 NetEq Minimum Delay Tuning
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/network/WebRTCNetworkManager.kt`

**Mechanism:**
```kotlin
// PeerConnectionFactory initialization
val factoryOptions = PeerConnectionFactory.Options()
val factory = PeerConnectionFactory.builder()
    .setOptions(factoryOptions)
    .createPeerConnectionFactory()

// Receiver side - set minimum jitter buffer
// Note: WebRTC Android SDK exposes this via RtpParameters
val receiver = pc.createReceiver("video", videoTrack)
val params = receiver.getParameters()
params.degradationPreference = DegradationPreference.MAINTAIN_FRAMERATE
// NetEq min delay set via PeerConnectionFactory constraints
```

**Expected Effect:** -50-80ms jitter buffer latency
**CPU/GPU/Memory Cost:** Less buffering = less memory; slightly higher loss sensitivity
**Compatibility Risks:** Higher packet loss visibility; enable NACK+RTX
**API Level:** 21+ (WebRTC SDK)
**Fallback:** Default 100ms if instability detected
**Benchmark:** WebRTC stats `jitterBufferDelayMs` P50 < 30ms
**Default:** ENABLED (20ms min, 100ms max)

---

### 5.2 NACK + RTX + FEC Configuration
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/network/WebRTCNetworkManager.kt`

**Mechanism:**
```kotlin
// SDP includes RTX and NACK
// WebRTC enables by default; verify in SDP:
// a=rtpmap:XX rtx/90000
// a=fmtp:XX apt=YY
// a=rtcp-fb:YY nack
// a=rtcp-fb:YY nack pli

// For high loss: consider ULPFEC (adds overhead)
// SDP: a=rtpmap:ZZ ulpfec/90000
```

**Expected Effect:** Better loss recovery; fewer freezes
**CPU/GPU/Memory Cost:** RTX adds ~10-20% bitrate; FEC adds more
**Compatibility Risks:** Server must support RTX/NACK (GFN does)
**API Level:** 21+ (WebRTC SDK)
**Fallback:** NACK only if RTX unsupported
**Benchmark:** Packet loss < 0.5% after recovery; RTX frames visible in stats
**Default:** ENABLED (NACK+RTX); FEC DISABLED by default

---

### 5.3 Input Pipeline Optimization
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/input/InputProcessor.kt`
- `opennow-stable/android/src/main/java/com/opennow/input/InputEncoder.kt`

**Mechanism:**
```kotlin
// Dedicated input thread (MAX_PRIORITY)
class InputProcessor @Inject constructor(
    private val networkSender: NetworkSender
) {
    private val queue = ArrayBlockingQueue<InputEvent>(1024)
    
    fun onTouchEvent(event: MotionEvent) {
        // Capture KERNEL timestamp immediately
        val timestampUs = event.eventTime * 1000L  // eventTime is ms, CLOCK_MONOTONIC
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

**Expected Effect:** 10-30ms input latency reduction
**CPU/GPU/Memory Cost:** Dedicated thread; minimal queue memory
**Compatibility Risks:** BT gamepad latency variance (prefer USB OTG); timestamp discipline critical
**API Level:** 21+ (InputDevice, MotionEvent)
**Fallback:** UI thread processing if thread fails (with latency penalty logged)
**Benchmark:** High-speed camera LED test: USB < 20ms, BT 5.0 < 35ms; Perfetto `input.dispatch` latency
**Default:** ENABLED

---

### 5.4 Wi-Fi Lock & DSCP EF
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/network/NetworkOptimizer.kt`

**Mechanism:**
```kotlin
// Wi-Fi lock (HIGH_PERF not LOW_LATENCY - more stable)
val wifiLock = wifiManager.createWifiLock(
    WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OpenNOW-Gaming"
).apply { acquire() }

// DSCP EF on WebRTC sockets (requires NDK or custom WebRTC build)
// Alternative: setTrafficClass on DatagramChannel if using custom UDP
datagramChannel.socket().setTrafficClass(0xB8)  // DSCP 46 (EF)

// Prefer Wi-Fi 6/6E/7
val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiInfo = connectivityManager.getNetworkInfo(network)
            // Check for Wi-Fi 6/6E/7 (802.11ax/be)
        }
    }
}
```

**Expected Effect:** Stable Wi-Fi (no PSM); QoS reduces router queuing latency
**CPU/GPU/Memory Cost:** Wi-Fi lock +100-200mW; DSCP no cost
**Compatibility Risks:** DSCP may be stripped by ISP/router; `WIFI_MODE_FULL_HIGH_PERF` not available on all OEMs
**API Level:** 24+ (WifiLock modes), 21+ (setTrafficClass), 29+ (NetworkCallback)
**Fallback:** No lock / no DSCP if unavailable
**Benchmark:** `dumpsys wifi` shows lock held; RTT stable < 30ms
**Default:** ENABLED

---

## TIER 6 — Thermal/Power Optimization

### 6.1 Adaptive Quality Controller
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/thermal/QualityController.kt`

**Mechanism:**
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
            adj.has(REDUCE_FPS) -> webRTC.setTargetFps(30)
            adj.has(REDUCE_BITRATE) -> webRTC.setTargetBitrate(targetBitrate * 0.7)
            adj.has(SWITCH_TO_SIMPLE_CODEC) -> decoder.switchToH264Baseline()
        }
    }
}
```

**Expected Effect:** Prevents thermal throttling crashes; maintains playable session
**CPU/GPU/Memory Cost:** Quality reduction saves power; thermal monitoring negligible
**Compatibility Risks:** Server must support dynamic quality changes (GFN CloudMatch does via REMB)
**API Level:** 29+ (PowerManager thermal API)
**Fallback:** Pause session if CRITICAL
**Benchmark:** 30min session without throttle; `dumpsys thermal` shows status transitions; frame drops < 0.5%
**Default:** ENABLED

---

### 6.2 Frame Rate Reduction for Thermal
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/thermal/QualityController.kt` (same as 6.1)

**Mechanism:**
```kotlin
// 60fps -> 30fps: ~40% decoder power reduction
fun reduceTo30fps() {
    surface.setFrameRate(30.0f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
    webRTC.setTargetFps(30)
    decoder.setTargetFps(30)
    // Maintain input sampling at 60/120Hz for responsiveness
}
```

**Expected Effect:** ~40% decoder power reduction
**CPU/GPU/Memory Cost:** Half decoder workload
**Compatibility Risks:** Visual smoothness reduced; game may feel less responsive
**API Level:** 30+ (Surface.setFrameRate)
**Fallback:** N/A (quality reduction)
**Benchmark:** `dumpsys batterystats` shows reduced power; decode latency P95 maintained
**Default:** ENABLED (when thermal SEVERE/CRITICAL)

---

### 6.3 Codec Selection for Thermal
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/decode/DecoderSelector.kt`
- `opennow-stable/android/src/main/java/com/opennow/thermal/QualityController.kt`

**Mechanism:**
```kotlin
// Thermal-aware codec ladder
val thermalCodecLadder = mapOf(
    PowerManager.THERMAL_STATUS_NONE to listOf("AV1", "HEVC", "H264"),
    PowerManager.THERMAL_STATUS_LIGHT to listOf("HEVC", "H264"),
    PowerManager.THERMAL_STATUS_MODERATE to listOf("HEVC", "H264"),
    PowerManager.THERMAL_STATUS_SEVERE to listOf("H264"),
    PowerManager.THERMAL_STATUS_CRITICAL to listOf("H264_BASELINE")
)

fun switchToH264Baseline() {
    // Reconfigure MediaCodec with H.264 constrained baseline
    // Request keyframe from server
}
```

**Expected Effect:** Minimum VPU load at critical thermal
**CPU/GPU/Memory Cost:** H.264 baseline uses ~20-30% more bitrate
**Compatibility Risks:** Requires server codec renegotiation
**API Level:** 29+ (thermal API), 21+ (MediaCodec)
**Fallback:** N/A
**Benchmark:** Decode latency drops at each thermal tier; session continues
**Default:** ENABLED

---

## TIER 7 — Device-Specific Optimization

### 7.1 Qualcomm Snapdragon Optimizations
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/device/QualcommOptimizer.kt`

**Mechanism:**
```kotlin
// Detect Snapdragon
val isSnapdragon = Build.HARDWARE.lowercase().contains("qcom") || 
                   Build.HARDWARE.lowercase().contains("sdm") ||
                   Build.HARDWARE.lowercase().contains("sm8")

if (isSnapdragon) {
    // Venus VPU: use vendor MediaCodec keys for LTR control if needed
    // Enable low-latency (well supported on Gen 2+)
    // Independent VPU thermal zone - monitor separately
    // Pin decoder to performance cores (if rooted)
}
```

**Expected Effect:** Best low-latency decode on flagship Snapdragon
**CPU/GPU/Memory Cost:** Vendor keys minimal
**Compatibility Risks:** Vendor keys may not be documented; test per device
**API Level:** 30+ (low-latency)
**Fallback:** Standard path
**Benchmark:** Decode latency < 5ms on 8 Gen 2/3
**Default:** ENABLED (auto-detect)

---

### 7.2 MediaTek Dimensity Optimizations
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/device/MediaTekOptimizer.kt`

**Mechanism:**
```kotlin
val isMediaTek = Build.HARDWARE.lowercase().contains("mtk") || 
                 Build.HARDWARE.lowercase().contains("mt6")

if (isMediaTek) {
    // Android 15 HEVC workaround (Dimensity 700/900/1080)
    if (Build.VERSION.SDK_INT >= 35 && isAffectedDimensity()) {
        disableHEVCHardware()
    }
    
    // Dimensity 1000/9000/9200/9300: AV1 HW available
    // Widevine seek: test secure decoder path
    // Combined CPU/GPU/VPU thermal zone - aggressive quality reduction
}
```

**Expected Effect:** Avoids black screen; uses AV1 where available
**CPU/GPU/Memory Cost:** H.264 fallback uses more bitrate
**Compatibility Risks:** Android 15 HEVC bug is OS-level; workaround required
**API Level:** 35+ (Android 15 detection)
**Fallback:** H.264 or SW HEVC
**Benchmark:** No black screen on affected devices; AV1 HW used on 9000+
**Default:** ENABLED (auto-detect)

---

### 7.3 Samsung Exynos Optimizations
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/device/ExynosOptimizer.kt`

**Mechanism:**
```kotlin
val isExynos = Build.HARDWARE.lowercase().contains("exynos") || 
               Build.HARDWARE.lowercase().contains("s5e")

if (isExynos) {
    // Exynos 2200: test AV1 HW (may fall back to SW)
    // Exynos 2400: better sustained, test AV1 HW
    // Integrated thermal - monitor closely
    // Limited vendor docs - empirical validation required
}
```

**Expected Effect:** Stable decode on Exynos
**CPU/GPU/Memory Cost:** Empirical testing required
**Compatibility Risks:** AV1 may fall back to SW on 2200
**API Level:** 30+ (AV1)
**Fallback:** HEVC or H.264
**Benchmark:** Decode latency < 10ms; no SW fallback
**Default:** ENABLED (auto-detect; validate at runtime)

---

### 7.4 Google Tensor Optimizations
**Source Files:**
- `opennow-stable/android/src/main/java/com/opennow/device/TensorOptimizer.kt`

**Mechanism:**
```kotlin
val isTensor = Build.HARDWARE.lowercase().contains("google") || 
               Build.HARDWARE.lowercase().contains("gs")

if (isTensor) {
    // Tensor G3+: AV1 HW decode preferred
    // Good platform integration - thermal zones well defined
    // Pixel vapor chamber effective for sustained
    // Prefer AV1 > HEVC > H264
}
```

**Expected Effect:** Best AV1 decode on Pixel 8+
**CPU/GPU/Memory Cost:** AV1 HW efficient
**Compatibility Risks:** Tensor G2 and earlier: no AV1 HW
**API Level:** 30+ (AV1)
**Fallback:** HEVC
**Benchmark:** AV1 decode latency < 6ms on Pixel 8/9
**Default:** ENABLED (auto-detect)

---

## Summary: Optimization Priority Matrix

| Tier | Optimization | Latency Impact | Power Impact | Complexity | API Min | Default |
|------|--------------|----------------|--------------|------------|---------|---------|
| 0.1 | Android Project Setup | N/A | N/A | Low | 30 | N/A |
| 0.2 | Perfetto Telemetry | Measurement | ~1% CPU | Medium | 29/33 | Debug only |
| 0.3 | Baseline Metrics | Measurement | Minimal | Low | 21/30 | Yes |
| 1.1 | SurfaceView + Surface Output | ⭐⭐⭐⭐⭐ (-50-83ms) | -60% BW | Low | 16/21/30 | Yes |
| 1.2 | Hardware Overlay | ⭐⭐⭐ (-16ms) | -2-5% | Low | 16/23 | Yes |
| 1.3 | Gaming WebRTC Config | ⭐⭐⭐⭐ (-50-100ms) | ~100mW | Medium | 21/24 | Yes |
| 1.4 | Codec Negotiation | ⭐⭐ (optimal HW) | - | Low | 21/29 | Yes |
| 2.1 | Low-Latency MediaCodec | ⭐⭐⭐⭐ (-16-50ms) | +1-3% | Low | 30 | Yes (if supported) |
| 2.2 | MediaTek Android 15 Fix | Stability | - | Low | 35 | Auto |
| 2.3 | Buffer Pre-allocation | Smoothness | -GC | Low | 21 | Yes |
| 3.1 | Enforce Surface Path | ⭐⭐⭐⭐⭐ (arch req) | -900MB/s | Low | 16 | Yes (arch) |
| 3.2 | AHardwareBuffer + Vulkan | Control | - | Very High | 29 | No |
| 4.1 | High-Priority Threads | Scheduler (-1-5ms) | Big core pwr | Medium | 21/28 | Yes |
| 4.2 | MediaCodec Async | -1-2ms/frame | - | Low | 21 | Yes |
| 5.1 | NetEq Min Delay | ⭐⭐⭐⭐ (-50-80ms) | -buf | Low | 21 | Yes |
| 5.2 | NACK+RTX | Loss recovery | +10-20% bw | Low | 21 | Yes |
| 5.3 | Input Pipeline | ⭐⭐⭐ (-10-30ms) | - | Low-Med | 21 | Yes |
| 5.4 | Wi-Fi Lock + DSCP | Stability | +100-200mW | Low | 24/29 | Yes |
| 6.1 | Adaptive Quality | Prevents drops | -40% at 30fps | Medium | 29 | Yes |
| 6.2 | FPS Reduction | -40% decoder | - | Low | 30 | Thermal |
| 6.3 | Thermal Codec Ladder | Min VPU load | - | Low | 29 | Thermal |
| 7.1-7.4 | Device-Specific | Optimal per SoC | - | Low | Varies | Auto |

---

## Implementation Order (Phases)

### PHASE 1: Foundation (Weeks 1-3)
- [ ] 0.1 Android project setup + Gradle
- [ ] 0.2 Perfetto integration + custom trace points
- [ ] 0.3 Baseline metrics collector
- [ ] 1.1 SurfaceView + MediaCodec Surface output
- [ ] 1.2 Hardware overlay enforcement
- [ ] 1.4 Codec negotiation + capability query
- [ ] 3.1 Enforce Surface path (architectural)

**Success Criteria:**
- App builds and runs on Android 11+ device
- Perfetto trace shows `MediaCodec.*` + `SurfaceFlinger.*` sections
- `dumpsys SurfaceFlinger --list` shows HWC for game layer
- `dumpsys media.codec` shows HW decoder (not software)
- 1080p60 H.264 stream plays with visible video

### PHASE 2: Core Pipeline Optimization (Weeks 4-6)
- [ ] 1.3 Gaming WebRTC config (NetEq 20ms, NACK+RTX, DSCP)
- [ ] 2.1 Low-latency MediaCodec (KEY_LOW_LATENCY + PARAMETER_KEY_LOW_LATENCY)
- [ ] 2.2 MediaTek Android 15 HEVC workaround
- [ ] 2.3 Buffer pre-allocation
- [ ] 4.2 MediaCodec async callbacks
- [ ] 5.1 NetEq minimum delay
- [ ] 5.2 NACK+RTX

**Success Criteria:**
- End-to-end latency P50 < 100ms (Perfetto + FrameTimeline)
- Decode latency P95 < 8ms
- Jitter buffer delay < 30ms
- Frame drop rate < 0.5%
- No black screen on MediaTek Android 15

### PHASE 3: Threading, Input, Thermal (Weeks 7-9)
- [ ] 4.1 Dedicated high-priority threads
- [ ] 5.3 Input pipeline optimization
- [ ] 5.4 Wi-Fi lock + DSCP EF
- [ ] 6.1 Adaptive quality controller
- [ ] 6.2/6.3 FPS reduction + thermal codec ladder
- [ ] 7.1-7.4 Device-specific optimizations

**Success Criteria:**
- Input latency < 20ms (USB) / < 35ms (BT 5.0)
- 30min sustained session without thermal throttle
- Perfetto CPU Scheduling shows threads on big cores
- Quality adaptation triggers on thermal/network events
- Device-specific codecs selected correctly

### PHASE 4: Advanced & Polish (Weeks 10-12)
- [ ] 3.2 AHardwareBuffer + Vulkan (if custom render needed)
- [ ] 1.1/1.2 verification on full device matrix
- [ ] 0.2 Perfetto automated CI
- [ ] Documentation, crash reporting, production hardening

**Success Criteria:**
- All Tier 0-3 optimizations verified on 5+ devices
- Automated Perfetto trace collection in CI
- Measurement targets met (see MASTER_RESEARCH_REPORT.md)
- Production-ready error recovery and telemetry