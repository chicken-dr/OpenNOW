# Android Architecture Review for OpenNOW

**Status**: Research Phase - Current vs Proposed Architecture  
**Target**: Android-first cloud gaming client for GeForce NOW  
**Baseline**: Current OpenNOW is desktop-only Electron app (no Android code exists)

---

## Current OpenNOW Architecture (Desktop Electron)

### High-Level Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         OPENNOW DESKTOP (ELECTRON)                               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌──────────────┐     ┌────────────────────────────────────────────────────┐   │
│  │ MAIN PROCESS │     │              RENDERER PROCESS (CHROMIUM)            │   │
│  │  (Node.js)   │◀───▶│  ┌─────────────┐  ┌──────────────┐  ┌───────────┐  │   │
│  │              │ IPC │  │ GfnWebRtcClient│ │ VideoShader │ │ React UI  │  │   │
│  │ - Signaling  │     │  │ - WebRTC      │ │ Pipeline    │ │           │  │   │
│  │ - Native     │     │  │ - Input Enc   │ │ - WebGL2    │ │ - Stream  │  │   │
│  │   Streamer   │     │  │ - Codec Pref  │ │ - CAS/Color │ │   View    │  │   │
│  │ - Auth       │     │  │ - Stats       │ │ - Grain     │ │ - Menus   │  │   │
│  │ - Settings   │     │  └─────────────┘  └──────────────┘  └───────────┘  │   │
│  └──────────────┘     └────────────────────────────────────────────────────┘   │
│         │                                                    ▲                  │
│         ▼                                                    │                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                    NATIVE STREAMER (OPTIONAL - GStreamer)                │   │
│  │  - GStreamer pipeline: udpsrc → rtph264depay → h264parse → decodebin    │   │
│  │  - Hardware decode: D3D11/DXVA (Win), VideoToolbox (Mac), VAAPI (Linux) │   │
│  │  - Zero-copy: D3D11 texture → swapchain / DMABUF → Wayland/X11          │   │
│  │  - Input: RawInput (Win) / evdev (Linux) → protocol v3 → network       │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Current Data Flow (Desktop WebRTC Path)

```
Network (UDP)
    ↓
Chromium WebRTC (C++)
    ↓
RTP Depacketization (NetEq jitter buffer ~100ms default)
    ↓
Chromium GPU Video Decode (DXVA/VideoToolbox/VAAPI/Vulkan)
    ↓
HTMLVideoElement (MSE/EME pipeline)
    ↓ [GPU texture - zero-copy in Chromium compositor]
VideoShaderPipeline (WebGL2) → Canvas overlay (optional)
    ↓
Chromium Compositor (viz) → GPU → Display
```

### Current Threading (Desktop)

| Thread | Responsibility | Priority |
|--------|---------------|----------|
| Main (Node.js) | Electron main process, signaling, native streamer IPC | Normal |
| Renderer Main (Chromium) | React UI, JS event loop | High |
| Chromium WebRTC Network | UDP recv, RTP, NetEq | High (internal) |
| Chromium WebRTC Decoder | GPU decode callbacks | High (internal) |
| Chromium Compositor | GPU command submission | High |
| Native Streamer (if used) | GStreamer pipeline, decode, render | Real-time |

### Current Input Flow (Desktop)

```
Physical Input (USB/BT)
    ↓
OS Input Stack (RawInput / evdev / HID)
    ↓
Electron/Chromium: DomInputCaptureController (pointerlock, rawinput)
    ↓
InputEncoder (protocol v3) → WebRTC Data Channels (reliable + partially reliable)
    ↓
Network (UDP)
```

### Current Rendering (Desktop)

- **Primary**: HTMLVideoElement (Chromium's video pipeline)
- **Optional Enhancement**: VideoShaderPipeline (WebGL2 canvas overlay)
  - CAS sharpening, color grading, film grain
  - Renders to canvas positioned over `<video>` element
  - Uses `requestVideoFrameCallback` for frame-aligned rendering

### Current Limitations for Android

1. **No Android code exists** - entirely desktop Electron
2. **Chromium WebRTC** not directly portable to Android (different binary, no MediaCodec integration)
3. **HTMLVideoElement** path uses Chromium's GPU decode → not MediaCodec Surface path
4. **VideoShaderPipeline** uses WebGL2 → would need Vulkan/OpenGL ES rewrite
5. **Native Streamer** is GStreamer-based for desktop only
5. **Threading** managed by Chromium, not controllable
6. **No thermal management** (desktop has no thermal constraints)
7. **No battery/power optimization** needed on desktop

---

## Proposed Android Architecture (Optimized)

### High-Level Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        OPENNOW ANDROID (NATIVE)                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                      ANDROID APP PROCESS                                  │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐    │   │
│  │  │   NETWORK   │  │   DECODE    │  │   RENDER    │  │   INPUT     │    │   │
│  │  │   LAYER     │──▶│   LAYER     │──▶│   LAYER     │  │   LAYER     │    │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘    │   │
│  │        │                │                │                │              │   │
│  │        ▼                ▼                ▼                ▼              │   │
│  │  ┌─────────────────────────────────────────────────────────────────┐   │   │
│  │  │              COORDINATION / SESSION LAYER                        │   │   │
│  │  │  SessionManager │ QualityController │ ThermalManager │ LatencyTracker  │   │
│  │  └─────────────────────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         ANDROID SYSTEM SERVICES                                  │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐          │
│  │ MediaServer  │ │SurfaceFlinger│ │  InputFlinger│ │  PowerManager│          │
│  │ (MediaCodec) │ │  (HWC/GPU)   │ │  (Dispatcher)│ │  (Thermal)   │          │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘          │
└─────────────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            HARDWARE                                              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐            │
│  │   VPU    │ │   GPU    │ │  Display │ │  Modem   │ │  Sensors │            │
│  │ (Decode) │ │ (Compose)│ │ (Scanout)│ │ (Wi-Fi/5G)│ │ (Touch)  │            │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘            │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Proposed Data Flow (Android Optimized)

```
Network (UDP) → WebRTC Android SDK (org.webrtc)
    ↓
RTP Depacketization + NetEq Jitter Buffer (tuned: 20ms min)
    ↓
MediaCodec (Hardware Decoder) ← Surface (from SurfaceView)
    ↓ [Zero-copy: decoder → gralloc buffer]
BufferQueue (ANativeWindow)
    ↓ [Fence sync]
SurfaceFlinger (Composition)
    ↓ [Hardware Overlay (HWC) - zero GPU]
Display Controller → Panel (VSYNC aligned)
```

### Proposed Threading Model

| Thread | Priority (nice) | CPU Affinity | Responsibility |
|--------|----------------|--------------|----------------|
| OpenNOW-Network | -16 (URGENT_AUDIO) | Big cores | UDP recv, RTP parse, NetEq |
| OpenNOW-Decoder | -12 (custom high) | Big cores | MediaCodec async callbacks |
| OpenNOW-Input | 10 (MAX_PRIORITY) | Big cores | Touch/gamepad capture, encode, send |
| OpenNOW-Coordination | 0 (normal) | Any | Session, quality, thermal |
| UI Thread | -8 (URGENT_DISPLAY) | Any | SurfaceView callbacks, Choreographer |
| RenderThread | -8 (URGENT_DISPLAY) | Big preferred | GPU commands (minimal) |
| SurfaceFlinger | RT/FIFO | Dedicated big | Composition, VSYNC |
| HWC/Display | RT/FIFO | Dedicated | Overlay, scanout |

### Proposed Input Flow (Android)

```
Physical Input (USB/BT/Touch)
    ↓
Linux evdev → InputReader → InputDispatcher
    ↓
InputChannel (socketpair) → ViewRootImpl
    ↓
SurfaceView.onTouchListener / Activity.onGenericMotionEvent
    ↓ [IMMEDIATE - no View hierarchy traversal]
OpenNOW-Input Thread (MAX_PRIORITY)
    ↓
InputEncoder (protocol v3) → kernel timestamp preserved
    ↓
UDP send (DSCP EF, no batching)
    ↓
Network
```

### Proposed Rendering (Android)

- **Primary**: SurfaceView (full-screen, opaque, no transform)
  - `setZOrderMediaOverlay(true)` → dedicated SurfaceFlinger layer
  - Qualifies for Hardware Overlay (HWC) → zero GPU composition
- **UI Overlays**: Regular Views on top (minimal, translucent)
- **No WebGL/Vulkan** in baseline (SurfaceView + HWC = optimal)
- **Optional Advanced**: AHardwareBuffer + Vulkan for custom post-processing

---

## Architecture Comparison

| Aspect | Current (Desktop Electron) | Proposed (Android Optimized) | Change Type |
|--------|---------------------------|------------------------------|-------------|
| **Platform** | Electron (Chromium + Node.js) | Native Android (Kotlin + NDK) | Complete rewrite |
| **WebRTC** | Chromium embedded WebRTC | `org.webrtc:google-webrtc` SDK | Library swap |
| **Video Decode** | Chromium GPU decode (DXVA/VideoToolbox) | MediaCodec + Surface (zero-copy) | Pipeline replacement |
| **Rendering** | HTMLVideoElement + WebGL2 canvas | SurfaceView + HWC overlay | Complete replacement |
| **Jitter Buffer** | NetEq default (~100ms) | NetEq tuned (20ms min, 100ms max) | Config change |
| **Decoder Latency** | Chromium internal (~20-40ms) | MediaCodec low-latency (KEY_LOW_LATENCY) | API usage |
| **Buffer Copies** | 4-5 CPU copies/frame | 1 CPU copy/frame (Surface path) | -60-80% bandwidth |
| **GPU Composition** | Always (Chromium compositor) | Hardware Overlay (zero GPU) | -1 frame latency |
| **Threading** | Chromium-managed | App-controlled dedicated threads | Control gain |
| **Input Latency** | DOM → WebRTC data channel | Dedicated thread, kernel timestamps | -10-30ms |
| **Thermal Management** | None | Adaptive quality + thermal listener | New feature |
| **Power Optimization** | None | Wi-Fi lock, DSCP, codec ladder, FPS reduction | New feature |
| **Device-Specific** | None | Per-SoC codec/thermal tuning | New feature |
| **Diagnostics** | Basic StreamDiagnosticsStore | Perfetto + FrameTimeline + dumpsys | Major upgrade |

---

## Where OpenNOW's Current Implementation Differs from Android Model

### 1. Network Layer
| Desktop (Current) | Android (Target) | Gap |
|-------------------|------------------|-----|
| Chromium WebRTC (C++ → JS bindings) | WebRTC Android SDK (AAR) | Different binary, no JS bindings |
| `GfnWebRtcClient` manages PeerConnection | `WebRTCNetworkManager` wraps SDK | Wrapper needed |
| NetEq default config | NetEq gaming config (20ms min) | Config only |
| WebRTC data channels for input | UDP + custom protocol (or data channels) | Transport choice |

### 2. Decode Layer
| Desktop (Current) | Android (Target) | Gap |
|-------------------|------------------|-----|
| Chromium GPU decode (MSE/EME) | MediaCodec + Surface | Complete replacement |
| `<video>` element receives frames | MediaCodec writes to Surface | Zero-copy path |
| Chromium manages decode thread | App controls decoder thread + callbacks | Thread control |
| No low-latency API exposed | `KEY_LOW_LATENCY` + `PARAMETER_KEY_LOW_LATENCY` | New API usage |

### 3. Render Layer
| Desktop (Current) | Android (Target) | Gap |
|-------------------|------------------|-----|
| HTMLVideoElement + WebGL2 canvas | SurfaceView | Complete replacement |
| Chromium compositor (always GPU) | SurfaceFlinger + HWC overlay | Zero GPU |
| VideoShaderPipeline (CAS, color, grain) | None (baseline) / Vulkan (advanced) | Feature parity decision |
| `requestVideoFrameCallback` | Choreographer / FrameTimeline | Different API |

### 4. Input Layer
| Desktop (Current) | Android (Target) | Gap |
|-------------------|------------------|-----|
| DOM events → `DomInputCaptureController` | SurfaceView touch + Activity gamepad | Direct handling |
| `InputEncoder` → WebRTC data channels | `InputEncoder` → UDP (or data channels) | Transport |
| Timestamp: `performance.now()` | Timestamp: `event.eventTime` (kernel) | Clock domain |
| Runs on renderer main thread | Dedicated `OpenNOW-Input` thread | Threading |

### 5. Threading & Scheduling
| Desktop (Current) | Android (Target) | Gap |
|-------------------|------------------|-----|
| Chromium manages all threads | App creates dedicated threads | Control |
| No CPU affinity control | Big core affinity (best effort) | New |
| No thermal awareness | Thermal listener + adaptive quality | New |

### 6. Diagnostics
| Desktop (Current) | Android (Target) | Gap |
|-------------------|------------------|-----|
| `StreamDiagnosticsStore` (JS) | Perfetto + FrameTimeline + dumpsys | Complete replacement |
| PostHog telemetry | Perfetto traces + custom SQL + PostHog | Enhancement |
| No frame timeline | FrameTimeline (API 33+) | New |

---

## Shared Contracts (Reusable from Current Codebase)

The following `@shared/gfn` types CAN be reused directly:

- **Session/Stream Types**: `SessionInfo`, `StreamSettings`, `VideoCodec`, `ColorQuality`
- **Input Protocol**: `InputEncoder`, `INPUT_MOUSE_REL`, protocol v3 packet structures
- **Signaling**: `IceCandidatePayload`, `SessionInfo`, offer/answer flow
- **Settings Schema**: Resolution, FPS, bitrate, codec, color quality enums
- **Error Codes**: `GfnErrorCodeEnum`, error handling patterns
- **Device Identity**: Steam Deck identification, client headers

The following are PLATFORM-SPECIFIC and need Android implementations:

- `GfnWebRtcClient` → `WebRTCNetworkManager` (Android SDK wrapper)
- `PeerMediaLifecycleController` → `MediaCodecDecoder` + `GameSurfaceView`
- `VideoShaderPipeline` → Not in baseline (optional Vulkan later)
- `DomInputCaptureController` → `InputProcessor` (native Android)
- `NativeStreamerManager` → Not applicable (desktop GStreamer only)

---

## Migration Strategy

### Phase 1: New Android Module
- Create `opennow-stable/android/` Gradle module
- Share `@shared/gfn` via Kotlin Multiplatform or code generation
- Implement Tier 0 (setup, Perfetto, baseline metrics)

### Phase 2: Core Pipeline
- Implement `WebRTCNetworkManager` wrapping `org.webrtc`
- Implement `MediaCodecDecoder` with Surface output
- Implement `GameSurfaceView` (SurfaceView + HWC)
- Connect: WebRTC → MediaCodec → SurfaceView

### Phase 3: Optimizations
- Tier 1-7 optimizations from roadmap
- Threading, input, thermal, device-specific

### Phase 4: Feature Parity
- Port UI (React → Compose or keep WebView for UI?)
- Settings, game catalog, auth flow
- Native streamer not needed on Android (MediaCodec replaces it)

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| WebRTC SDK binary size (~20MB) | High | App size | Acceptable for gaming app; consider dynamic feature module |
| MediaCodec vendor bugs | Medium | Crashes/corruption | Per-device blacklist; graceful fallback to next codec |
| SurfaceView Z-order limitations | Low | UI constraints | Design overlays carefully; use `setZOrderMediaOverlay` |
| Hardware overlay not available | Low | Falls back to GPU | Verify at runtime; log warning |
| CPU affinity requires root | Medium | Threads on LITTLE cores | Best-effort; log if denied |
| Thermal API not available < API 29 | Low (min SDK 30) | No thermal adaptation | Min SDK 30 requirement |
| FrameTimeline only API 33+ | Medium | No per-frame deadline tracking | Fallback to `dumpsys gfxinfo` |
| AV1 HW not on all devices | High | Codec negotiation handles | Priority order: H.264 > HEVC > VP9 > AV1 |

---

## References

- [Android MediaPipeline Research](./ANDROID_MEDIA_PIPELINE.md)
- [Android OpenNOW Architecture](./ANDROID_OPENNOW_ARCHITECTURE.md)
- [Android Optimization Roadmap](./ANDROID_OPTIMIZATION_ROADMAP.md)
- [Cross-Check Analysis](./CROSS_CHECK_ANALYSIS.md)