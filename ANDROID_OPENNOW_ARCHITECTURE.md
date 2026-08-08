# Android OpenNOW Architecture

## Overview
Proposed Android-first architecture for OpenNOW cloud gaming client. Based on research of Android media pipeline, decoder capabilities, rendering paths, networking, threading, power/thermal, input latency, and diagnostics.

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        OPENNOW ANDROID ARCHITECTURE                              │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐      │
│  │   NETWORK   │    │   DECODE    │    │   RENDER    │    │   INPUT     │      │
│  │   LAYER     │───▶│   LAYER     │───▶│   LAYER     │    │   LAYER     │      │
│  └─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘      │
│         │                   │                   │                   │           │
│         ▼                   ▼                   ▼                   ▼           │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                      COORDINATION / SESSION LAYER                        │   │
│  │  • Session lifecycle  • Quality adaptation  • Thermal management        │   │
│  │  • Codec negotiation  • Latency tracking    • Resource management       │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

## Layer Details

### 1. Network Layer
**Components**:
- `WebRTCNetworkManager` - Wraps `org.webrtc` PeerConnection
- `PacketReceiver` - UDP socket, `recvmmsg()` batching
- `RTPDepacketizer` - H.264/HEVC/VP9/AV1 RTP → frames
- `JitterBuffer` - WebRTC NetEq (tuned for gaming: min 20ms)
- `CongestionController` - GCC with gaming profile

**Thread**: `OpenNOW-Network` (priority: URGENT_AUDIO, big core affinity)

**Key Interfaces**:
```kotlin
interface NetworkLayer {
    fun startSession(config: SessionConfig)
    fun stopSession()
    fun setTargetBitrate(bps: Long)
    fun setTargetResolution(width: Int, height: Int)
    fun setTargetFps(fps: Int)
    fun onNetworkCallback: (NetworkEvent) -> Unit
}
```

### 2. Decode Layer
**Components**:
- `MediaCodecDecoder` - Hardware decoder per codec
- `DecoderSelector` - Chooses best HW codec (H.264 > HEVC > VP9 > AV1)
- `LowLatencyController` - Enables KEY_LOW_LATENCY, PARAMETER_KEY_LOW_LATENCY
- `FrameQueue` - Decoded frames → Render layer (BufferQueue via Surface)

**Thread**: `OpenNOW-Decoder` (priority: HIGH, big core affinity)

**Configuration**:
```kotlin
data class DecoderConfig(
    val mimeType: String,           // "video/avc", "video/hevc", etc.
    val width: Int,
    val height: Int,
    val surface: Surface,           // SurfaceView holder surface
    val lowLatency: Boolean = true,
    val colorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
)
```

### 3. Render Layer
**Components**:
- `GameSurfaceView` - SurfaceView (not TextureView!)
- `FramePacer` - Choreographer-aligned frame presentation
- `OverlayManager` - UI overlays (minimal, translucent)
- `HWCMonitor` - Verifies hardware overlay usage

**Thread**: UI Thread (for SurfaceView callbacks) + RenderThread (GPU)

**SurfaceView Setup**:
```xml
<FrameLayout>
    <SurfaceView
        android:id="@+id/gameSurface"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:focusable="true"
        android:focusableInTouchMode="true" />
    <!-- Minimal UI overlays -->
    <GameOverlayView android:layout_width="match_parent" android:layout_height="match_parent" />
</FrameLayout>
```

### 4. Input Layer
**Components**:
- `InputProcessor` - Touch, gamepad, keyboard, mouse
- `InputEncoder` - WebRTC input protocol v3
- `InputSender` - UDP with DSCP EF, immediate send
- `GamepadMapper` - VID:PID → standard mapping

**Thread**: `OpenNOW-Input` (priority: MAX_PRIORITY, big core affinity)

**Timestamp Flow**:
```kotlin
// Kernel timestamp preserved throughout
val kernelTimeNs = event.eventTime * 1_000_000L
val packet = InputPacket(
    timestampUs = kernelTimeNs,
    inputData = encode(event)
)
networkSender.sendImmediate(packet)
```

### 5. Coordination/Session Layer
**Components**:
- `SessionManager` - Lifecycle, state machine
- `QualityController` - Adaptive bitrate/resolution/fps/codec
- `ThermalManager` - Monitors thermal status, triggers adaptation
- `LatencyTracker` - End-to-end measurements
- `ResourceManager` - Codec/surface/buffer lifecycle

**Thread**: `OpenNOW-Coordination` (normal priority)

## Threading Model

```
Thread Name            Priority          Affinity        Purpose
────────────────────────────────────────────────────────────────────────
OpenNOW-Network        URGENT_AUDIO(-16) Big cores       UDP recv, RTP, NetEq
OpenNOW-Decoder        HIGH(-12)         Big cores       MediaCodec callbacks
OpenNOW-Input          MAX_PRIORITY(10)  Big cores       Input capture, encode
OpenNOW-Coordination   NORMAL(0)         Any             Session, quality, thermal
UI Thread              URGENT_DISPLAY(-8) Any             SurfaceView, UI, Choreographer
RenderThread           URGENT_DISPLAY(-8) Big preferred   GPU command submission
SurfaceFlinger         RT/FIFO           Dedicated       Composition, VSYNC
HWC/Display            RT/FIFO           Dedicated       Overlay, scanout
```

## Memory/Buffer Flow (Zero-Copy Path)

```
Network Packet (kernel skb)
    ↓ recvmsg() copy
WebRTC RTP Buffer (pre-allocated pool)
    ↓ Reference (single-NAL) or Copy (fragmented)
Complete Frame Buffer
    ↓ queueInputBuffer() copy (BYTEBUFFER PATH - AVOID)
    ↓ ZERO COPY (SURFACE PATH - USE THIS)
MediaCodec Hardware Decoder (VPU internal)
    ↓ Zero-copy to gralloc buffer
BufferQueue Slot (reference + fence)
    ↓ SurfaceFlinger acquire + fence wait
Hardware Overlay Plane (HWC)
    ↓ Display Controller
Photon
```

## Codec Negotiation Strategy

### Priority Order (Client Preference)
1. **H.264 High Profile** - Universal HW, lowest latency baseline
2. **HEVC Main/Main10** - Better compression, 10-bit HDR
3. **VP9 Profile 0/2** - Google ecosystem, good fallback
4. **AV1 Main** - Best compression, HW only on Gen 3+/Tensor G3+/Dimensity 9000+

### Server-Side Encoding Hints
```json
{
  "preferredCodecs": ["H264", "HEVC", "VP9", "AV1"],
  "maxResolution": "1920x1080",
  "maxFps": 60,
  "maxBitrateKbps": 50000,
  "lowLatencyMode": true,
  "constrainedBaseline": true,    // H.264: no B-frames
  "referenceFrames": 2,           // Minimize decoder buffer
  "keyframeInterval": 2,          // Fast recovery
  "colorDepth": 8,                // or 10 for HDR
  "hdrFormat": "HDR10"            // if 10-bit
}
```

## Adaptive Quality Algorithm

```kotlin
class QualityController {
    fun evaluateAdjustment(metrics: SessionMetrics): QualityAdjustment {
        var adjustment = QualityAdjustment.NONE
        
        // Thermal takes priority
        when (metrics.thermalStatus) {
            THERMAL_STATUS_CRITICAL -> adjustment = DRASTIC_REDUCTION
            THERMAL_STATUS_SEVERE   -> adjustment = MAJOR_REDUCTION
            THERMAL_STATUS_MODERATE -> adjustment = MODERATE_REDUCTION
            THERMAL_STATUS_LIGHT    -> adjustment = MINOR_REDUCTION
        }
        
        // Network constraints
        if (metrics.packetLoss > 0.02) adjustment = adjustment.combine(REDUCE_BITRATE)
        if (metrics.rttMs > 50) adjustment = adjustment.combine(REDUCE_FPS)
        if (metrics.bandwidthMbps < targetBitrate * 0.8) adjustment = adjustment.combine(REDUCE_RESOLUTION)
        
        // Decoder performance
        if (metrics.decodeLatencyMs > 8) adjustment = adjustment.combine(SWITCH_TO_SIMPLE_CODEC)
        if (metrics.droppedFrames > 0.05) adjustment = adjustment.combine(REDUCE_FPS)
        
        return adjustment
    }
}
```

## Session Lifecycle

```
DISCONNECTED
    │
    ▼ (user selects game)
CONNECTING ──▶ Auth (NVIDIA OAuth)
    │
    ▼ (auth complete)
NEGOTIATING ──▶ CloudMatch session request
    │              WebRTC offer/answer
    │              Codec negotiation
    ▼
STARTING ──▶ MediaCodec configure/start
    │           SurfaceView surface ready
    │           Input capture start
    ▼
STREAMING ──▶ Main loop
    │           Network ↔ Decode ↔ Render
    │           Input → Network
    │           Quality adaptation
    │           Thermal monitoring
    ▼
PAUSED ──▶ (background, thermal critical)
    │
    ▼ (resume or stop)
STREAMING or STOPPING
    │
    ▼
DISCONNECTED
```

## Resource Management

### MediaCodec Lifecycle
```kotlin
class MediaCodecDecoder {
    fun start(config: DecoderConfig) {
        format = createFormat(config)
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        codec = MediaCodec.createDecoderByType(config.mimeType)
        codec.configure(format, config.surface, null, 0)
        codec.setCallback(callback, decoderHandler)  // Async callbacks
        codec.start()
        
        // Runtime low-latency enable
        val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_LOW_LATENCY, 1) }
        codec.setParameters(params)
    }
    
    fun stop() {
        codec?.setCallback(null)
        codec?.stop()
        codec?.release()
        codec = null
    }
}
```

### Surface Lifecycle
```kotlin
class GameSurfaceView : SurfaceView, SurfaceHolder.Callback {
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

## Error Handling & Recovery

| Error Type | Detection | Recovery |
|------------|-----------|----------|
| Decoder crash | MediaCodec callback error | Recreate codec, request keyframe |
| Network disconnect | ICE connection failed | Reconnect WebRTC, new offer |
| Thermal critical | PowerManager callback | Pause session, notify user |
| Surface lost | SurfaceHolder.callback | Recreate surface, restart decode |
| Codec negotiation fail | No common codec | Fallback to next codec in priority |

## Integration with OpenNOW Desktop Codebase

### Shared Contracts (from `@shared/gfn`)
- Session context types
- Stream settings (resolution, fps, bitrate, codec)
- Input protocol types
- Error codes

### Platform-Specific Implementation
- `opennow-stable/src/main/platforms/android/` (new)
- Main process: Network, Decode, Session coordination
- Renderer process: SurfaceView, Input, UI overlays

## Configuration Defaults (Gaming Optimized)

```kotlin
object GamingDefaults {
    const val TARGET_FPS = 60
    const val TARGET_RESOLUTION = "1920x1080"
    const val MAX_BITRATE_KBPS = 50000
    const val MIN_BITRATE_KBPS = 5000
    const val JITTER_BUFFER_MIN_MS = 20
    const val JITTER_BUFFER_MAX_MS = 100
    const val KEYFRAME_INTERVAL_SEC = 2
    const val REFERENCE_FRAMES = 2
    const val LOW_LATENCY_DECODE = true
    const val HARDWARE_OVERLAY_REQUIRED = true
    const val THERMAL_MONITOR_INTERVAL_MS = 1000
    const val LATENCY_SAMPLE_WINDOW = 100  // frames
}
```

## Testing Matrix

| Device Tier | SoC Examples | Expected Codecs | Max Quality |
|-------------|--------------|-----------------|-------------|
| Flagship 2024 | Snapdragon 8 Gen 3, Tensor G4, Dimensity 9300 | All HW | 4K@60 AV1 |
| Flagship 2023 | Snapdragon 8 Gen 2, Tensor G3, Dimensity 9200 | All HW | 4K@60 HEVC |
| High 2023 | Snapdragon 7+ Gen 2, Dimensity 8200 | H.264, HEVC, VP9 | 1080p@120 |
| Mid 2023 | Snapdragon 7 Gen 1, Dimensity 1080 | H.264, HEVC, VP9 | 1080p@60 |
| Budget | Snapdragon 6 Gen 1, Dimensity 700 | H.264, HEVC | 720p@60 |

## References
- [Android MediaPipeline Research](./ANDROID_MEDIA_PIPELINE.md)
- [Android Decoder Research](./ANDROID_DECODER_RESEARCH.md)
- [Android Low-Latency Decoding](./ANDROID_LOW_LATENCY_DECODING.md)
- [Android Rendering Research](./ANDROID_RENDERING_RESEARCH.md)
- [Android Memory Copy Analysis](./ANDROID_MEMORY_COPY_ANALYSIS.md)
- [Android Threading](./ANDROID_THREADING.md)
- [Android Power/Thermal](./ANDROID_POWER_THERMAL.md)
- [Android Input Latency](./ANDROID_INPUT_LATENCY.md)
- [Android Network Research](./ANDROID_NETWORK_RESEARCH.md)
- [Android Diagnostics](./ANDROID_DIAGNOSTICS.md)