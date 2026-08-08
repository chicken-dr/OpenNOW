# Cross-Check: Current OpenNOW Implementation vs Android Research Recommendations

## Executive Summary
OpenNOW is currently a **desktop-only Electron application** (Windows/macOS/Linux) with **no Android implementation**. The existing research documents assume a greenfield Android-native implementation. This cross-check identifies the actual gaps between the current desktop codebase and the proposed Android architecture.

---

## Component Comparison Table

| Component | Current OpenNOW Implementation (Desktop Electron) | Research Recommendation (Android) | Gap Analysis | Expected Benefit | Risk | Measurement Method | Priority |
|-----------|---------------------------------------------------|-----------------------------------|--------------|------------------|------|-------------------|----------|
| **Network Transport** | Chromium WebRTC embedded in Electron (`GfnWebRtcClient` in `webrtcClient.ts`) | WebRTC Android SDK (`org.webrtc:google-webrtc`) with gaming-tuned NetEq (20ms min delay), NACK+RTX, DSCP EF | **Complete rewrite needed** - Electron uses Chromium's WebRTC; Android needs native WebRTC SDK integration | -50-100ms jitter buffer reduction; better loss recovery | Medium - JNI overhead, binary size (~20MB) | Perfetto traces: `webrtc.neteq`, `webrtc.packet_loss` | P0 |
| **Video Decoder** | Chromium GPU decode (HTMLVideoElement + MSE/EME) - `PeerMediaLifecycleController` attaches track to `<video>` | MediaCodec with Surface output, `KEY_LOW_LATENCY=1`, `PARAMETER_KEY_LOW_LATENCY=1`, async callbacks | **Complete replacement** - Chromium decode path vs Android MediaCodec | -16-50ms decode latency; zero-copy to Surface | High - vendor codec variability, MediaTek Android 15 bugs | `dumpsys media.codec`; MediaCodec callback timestamps | P0 |
| **Rendering Surface** | HTMLVideoElement + optional WebGL shader overlay (`VideoShaderPipeline` draws to canvas over video) | SurfaceView with hardware overlay (HWC), full-screen, opaque, no transform | **Complete replacement** - DOM-based vs SurfaceView | -3-5 frames (50-83ms); eliminates GPU composition; -2-5% power | Low - standard Android API | `dumpsys SurfaceFlinger --list` (HWC vs GPU) | P0 |
| **Buffer Flow** | Chromium internal: network → WebRTC → MSE → GPU decode → compositor → display | Network → WebRTC → MediaCodec (Surface) → BufferQueue → SurfaceFlinger → HWC overlay → display | **Fundamental architecture change** | Eliminates 4-5 CPU copies (900MB/s → 360MB/s at 1080p60) | Medium - must verify Surface path works on all target devices | Perfetto: `android.atrace` `MediaCodec.*`, `SurfaceFlinger.*` | P0 |
| **Jitter Buffer** | WebRTC NetEq (Chromium default ~100ms) | NetEq min delay 20ms, adaptive max 100ms, gaming profile | **Configuration change** - same WebRTC SDK, different tuning | -50-80ms latency | Low - WebRTC SDK supports this | WebRTC stats: `jitterBufferDelayMs` | P0 |
| **Input Pipeline** | DOM events → `DomInputCaptureController` → `InputEncoder` → WebRTC data channels | Dedicated input thread (MAX_PRIORITY), kernel timestamps (`event.eventTime`), immediate UDP send, SurfaceView direct touch | **New implementation** - DOM vs native Android input | 10-30ms input latency reduction | Medium - timestamp discipline critical | High-speed camera LED test; Perfetto `input.dispatch` | P1 |
| **Threading Model** | Chromium threads (network, decoder, GPU, UI) - not directly controllable | Dedicated threads: Network (URGENT_AUDIO), Decoder (HIGH), Input (MAX_PRIORITY), big core affinity | **New implementation** - Electron manages threads; Android app controls them | Eliminates scheduler latency, prevents LITTLE core migration | High - `sched_setaffinity` needs root/privileged; CFS priority limited | Perfetto: CPU Scheduling track, CPU Frequency track | P1 |
| **Thermal Management** | None (desktop) | PowerManager thermal listener → adaptive quality (codec/resolution/fps/bitrate) | **New implementation** - desktop has no thermal constraints | Prevents throttling crashes, sustained playable session | Medium - requires server coordination for quality changes | `dumpsys thermal` + CPU freq; session duration without throttle | P1 |
| **Adaptive Quality** | Manual settings only (user picks resolution/fps/bitrate) | Automated: thermal + network + decoder metrics → quality ladder (AV1→HEVC→H.264 Baseline) | **New implementation** | Maintains playable session under thermal/network stress | Medium - complex state machine | Perfetto correlation: thermal ↔ frame drops ↔ quality | P1 |
| **Codec Negotiation** | SDP munging in `webrtcClient.ts` (`preferCodec`, `mungeAnswerSdp`) | Client preference: H.264 > HEVC > VP9 > AV1; device-specific fallbacks (MediaTek HEVC disable) | **Enhancement** - same SDP logic, Android-specific capability query | Optimal HW codec per device, avoids SW fallback | Low - capability query is standard | `MediaCodecList` query at startup; SDP analysis | P1 |
| **Hardware Overlay** | N/A (Chromium compositor) | SurfaceView full-screen + opaque + `setZOrderMediaOverlay` → HWC | **New requirement** - verify via `dumpsys SurfaceFlinger` | -1 frame latency, -2-5% power | Low - standard if SurfaceView used correctly | `dumpsys SurfaceFlinger --list` composition type | P0 |
| **Frame Pacing** | `requestVideoFrameCallback` in `VideoShaderPipeline` | `surface.setFrameRate(fps, FIXED_SOURCE)` + Choreographer/FrameTimeline targeting previous VSYNC | **New implementation** | Reduces VSYNC miss probability | Medium - complex timing logic | FrameTimeline (API 33+); `dumpsys gfxinfo` percentiles | P2 |
| **Diagnostics/Telemetry** | Basic `StreamDiagnosticsStore` + PostHog | Perfetto custom trace points + FrameTimeline observer + automated SQL analysis | **Enhancement** - desktop has no Perfetto integration | Measurement-driven optimization | Medium - instrumentation effort | Perfetto UI + custom SQL queries | P1 |

---

## Key Findings

### 1. **No Android Code Exists**
The current codebase is 100% desktop Electron. There are zero Android-specific files, no Gradle build, no AndroidManifest, no JNI, no native Android modules.

### 2. **Architecture Mismatch**
- **Desktop**: Electron (Chromium) → WebRTC → HTMLVideoElement → WebGL shader → Compositor
- **Android Research Target**: WebRTC SDK → MediaCodec (Surface) → BufferQueue → SurfaceFlinger → HWC → Display

### 3. **Shared Contracts Only**
The `@shared/gfn` package contains platform-agnostic types (codec enums, session settings, input protocol) that CAN be reused. But the implementation layer is completely different.

### 4. **Native Streamer is Desktop-Only**
The `nativeStreamer` module (GStreamer-based) is for Windows/macOS/Linux desktop. It has no Android equivalent.

### 5. **Research Documents Are Correct for Greenfield**
The research documents accurately describe Android best practices. The gap is that they assume a native Android app, but OpenNOW is currently Electron desktop.

---

## Revised Implementation Approach

Since OpenNOW has **no Android code**, the "cross-check" reveals that **all Android work is greenfield**. The roadmap should focus on:

1. **Tier 0**: Android project setup, build system, basic app structure
2. **Tier 1**: Core pipeline (WebRTC + MediaCodec + SurfaceView)
3. **Tier 2**: Optimizations (low-latency, threading, thermal, input)
3. **Tier 3**: Advanced (frame pacing, io_uring, device-specific)

The existing desktop codebase provides:
- Shared types (`@shared/gfn`)
- WebRTC SDP handling patterns (`sdp/`)
- Input protocol (`inputProtocol.ts`)
- Signaling flow (`cloudmatch*.ts`)
- Settings schema

But **zero implementation code can be reused directly** for Android.