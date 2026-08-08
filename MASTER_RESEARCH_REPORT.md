# Master Research Report: Android Cloud Gaming Optimization for OpenNOW

## Executive Summary

This report consolidates deep technical research across 10 critical areas of Android cloud gaming performance. OpenNOW currently exists as an Electron desktop application using WebRTC for GeForce NOW streaming. This research establishes the foundation for an Android-first implementation targeting minimal latency, optimal power efficiency, and sustained thermal performance.

**Key Finding**: The Android media pipeline can achieve **sub-80ms end-to-end latency** with proper architecture choices: SurfaceView + hardware decoder + hardware overlay + low-latency MediaCodec configuration + gaming-tuned WebRTC.

---

## Research Documents Produced

| Document | Focus Area | Key Deliverable |
|----------|------------|-----------------|
| `ANDROID_MEDIA_PIPELINE.md` | MediaCodec, Surface, BufferQueue, SurfaceFlinger, Choreographer, FrameTimeline, HardwareBuffer | Complete pipeline architecture with zero-copy path |
| `ANDROID_DECODER_RESEARCH.md` | SoC decoder matrix (Qualcomm, MediaTek, Samsung, Google, HiSilicon) | Codec support table, capability detection, device-specific handling |
| `ANDROID_LOW_LATENCY_DECODING.md` | FEATURE_LowLatency, KEY_LOW_LATENCY, PARAMETER_KEY_LOW_LATENCY | Configuration sequence, validation, vendor behavior |
| `ANDROID_RENDERING_RESEARCH.md` | SurfaceView vs TextureView vs SurfaceTexture vs SurfaceControl | Latency comparison, SurfaceView recommended |
| `ANDROID_MEMORY_COPY_ANALYSIS.md` | Every copy from network to display | 5 copies (ByteBuffer) vs 1 copy (Surface path) |
| `ANDROID_THREADING.md` | All threads, scheduling, big.LITTLE, thermal interaction | Thread priority/affinity map, contention analysis |
| `ANDROID_POWER_THERMAL.md` | Decoder 73% of power, thermal throttling stages | Adaptive quality algorithm, thermal-aware codec selection |
| `ANDROID_INPUT_LATENCY.md` | InputReader → InputDispatcher → ViewRootImpl → Network | 4-50ms input latency, dedicated input thread |
| `ANDROID_NETWORK_RESEARCH.md` | WebRTC on Android, Wi-Fi/5G, socket tuning | Gaming-optimized WebRTC config |
| `ANDROID_DIAGNOSTICS.md` | Perfetto, ATrace, dumpsys, FrameTimeline, GPU profiling | Measurement-driven optimization methodology |
| `ANDROID_OPENNOW_ARCHITECTURE.md` | Integrated architecture proposal | Layered architecture, threading model, session lifecycle |

---

## Android Optimization Roadmap

This section contains **only technically justified opportunities** derived from the research above. Each opportunity includes the research basis, expected impact, and implementation complexity.

### Tier 1: Critical Path Optimizations (Must Implement)

#### 1. SurfaceView + MediaCodec Surface Output (Zero-Copy Decode)
**Research Basis**: `ANDROID_RENDERING_RESEARCH.md`, `ANDROID_MEMORY_COPY_ANALYSIS.md`
- TextureView adds 3-5 frames latency (Chromium measured)
- SurfaceView enables hardware overlay (HWC) bypassing GPU composition
- MediaCodec `configure(format, surface, ...)` eliminates input/output buffer copies
- **Impact**: -3 to -5 frames latency (50-83ms at 60fps), -60% memory bandwidth
- **Complexity**: Low (standard Android APIs)

#### 2. Low-Latency MediaCodec Configuration (API 30+)
**Research Basis**: `ANDROID_LOW_LATENCY_DECODING.md`
- `MediaFormat.KEY_LOW_LATENCY = 1` at configure time
- `MediaCodec.PARAMETER_KEY_LOW_LATENCY = 1` at runtime
- Reduces decoder internal buffering by 1-3 frames
- **Impact**: -1 to -3 frames decode latency (16-50ms)
- **Complexity**: Low (2 API calls, capability detection required)

#### 3. Hardware Overlay Enforcement
**Research Basis**: `ANDROID_RENDERING_RESEARCH.md`, `ANDROID_MEMORY_COPY_ANALYSIS.md`
- SurfaceView full-screen, opaque, no transform → HWC overlay
- Verify via `dumpsys SurfaceFlinger --list` (composition type = HWC)
- **Impact**: Eliminates GPU composition frame, -1 frame latency, -2-5% power
- **Complexity**: Low (SurfaceView setup + verification)

#### 4. Gaming-Tuned WebRTC Configuration
**Research Basis**: `ANDROID_NETWORK_RESEARCH.md`
- NetEq minimum delay: 20ms (vs default 100ms)
- Enable NACK + RTX, consider FEC for lossy networks
- GCC congestion control with gaming profile
- DSCP EF (0xB8) on UDP sockets
- Wi-Fi lock: `WIFI_MODE_FULL_HIGH_PERF`
- **Impact**: -50-100ms network jitter buffer, better loss recovery
- **Complexity**: Medium (WebRTC SDK configuration)

#### 5. Dedicated High-Priority Threads with Big Core Affinity
**Research Basis**: `ANDROID_THREADING.md`
- Network thread: `THREAD_PRIORITY_URGENT_AUDIO` (-16)
- Decoder thread: Custom high priority (-12)
- Input thread: `MAX_PRIORITY` (10)
- All pinned to big CPU cores via `sched_setaffinity()`
- **Impact**: Eliminates scheduler latency, prevents LITTLE core migration
- **Complexity**: Medium (requires native/JNI for affinity)

### Tier 2: High-Impact Optimizations (Should Implement)

#### 6. Adaptive Quality Controller (Thermal + Network + Decoder)
**Research Basis**: `ANDROID_POWER_THERMAL.md`, `ANDROID_DECODER_RESEARCH.md`
- Thermal state listener (PowerManager) → quality reduction ladder
- Codec fallback: AV1 → HEVC → H.264 Baseline as thermal increases
- Resolution/FPS/bitrate reduction based on thermal + network + decode metrics
- **Impact**: Prevents thermal throttling crashes, maintains playable session
- **Complexity**: Medium (state machine + server coordination)

#### 7. Input Pipeline Optimization
**Research Basis**: `ANDROID_INPUT_LATENCY.md`
- SurfaceView direct touch handling (no View hierarchy)
- Dedicated input thread at MAX_PRIORITY
- Kernel timestamp (`event.eventTime`) preserved end-to-end
- Immediate UDP send (no batching)
- BT 5.0+ controller preference, USB OTG support
- **Impact**: 10-30ms input latency reduction
- **Complexity**: Low-Medium (thread + timestamp discipline)

#### 8. Buffer Pool Pre-allocation
**Research Basis**: `ANDROID_MEMORY_COPY_ANALYSIS.md`, `ANDROID_THREADING.md`
- WebRTC packet/frame buffers pre-allocated at session start
- MediaCodec async callbacks (avoid polling)
- Eliminates GC pressure during streaming
- **Impact**: Removes allocation stalls, smoother frame pacing
- **Complexity**: Low (standard pattern)

#### 9. Perfetto-Integrated Telemetry
**Research Basis**: `ANDROID_DIAGNOSTICS.md`
- Custom trace points for: network recv, RTP parse, codec queue, codec release, input send
- FrameTimeline observer (API 33+) for per-frame deadline tracking
- Automated SQL queries for: frame latency percentiles, decoder throughput, thermal correlation
- **Impact**: Measurement-driven optimization, regression detection
- **Complexity**: Medium (instrumentation + analysis pipeline)

#### 10. Codec Negotiation Strategy
**Research Basis**: `ANDROID_DECODER_RESEARCH.md`
- Client preference: H.264 > HEVC > VP9 > AV1
- Server hints: constrained baseline (H.264), 2 ref frames, 2s keyframe interval
- Device-specific fallback: MediaTek Android 15 HEVC disable, Exynos AV1 test
- **Impact**: Optimal codec per device, avoids software decode fallback
- **Complexity**: Low (capability query + SDP preference)

### Tier 3: Advanced Optimizations (Nice to Have)

#### 11. Frame Pacing with Choreographer/FrameTimeline
**Research Basis**: `ANDROID_RENDERING_RESEARCH.md`, `ANDROID_DIAGNOSTICS.md`
- Target previous VSYNC deadline (present early)
- `surface.setFrameRate(fps, FIXED_SOURCE)` for VSYNC alignment
- FrameTimeline API for per-stage latency measurement
- **Impact**: Reduces VSYNC miss probability, smoother frame delivery
- **Complexity**: Medium (custom frame pacing logic)

#### 12. io_uring for Network Receive (Android 12+)
**Research Basis**: `ANDROID_MEMORY_COPY_ANALYSIS.md`, `ANDROID_NETWORK_RESEARCH.md`
- Zero-copy kernel→user for UDP packets
- `IORING_OP_RECVMSG` with `IORING_FEAT_SINGLE_MMAP`
- **Impact**: Eliminates Stage 2 copy (kernel→user), ~1-2μs per packet
- **Complexity**: High (NDK, kernel version dependent, complex)

#### 13. AHardwareBuffer + Vulkan Custom Render (Advanced)
**Research Basis**: `ANDROID_MEDIA_PIPELINE.md`, `ANDROID_RENDERING_RESEARCH.md`
- MediaCodec → AHardwareBuffer → Vulkan `VK_ANDROID_external_memory_android_hardware_buffer`
- Custom frame pacing, post-processing shaders
- **Impact**: Maximum render control, potential for VRR integration
- **Complexity**: Very High (Vulkan, synchronization, high maintenance)

#### 14. SurfaceControl Direct Layer (Privileged)
**Research Basis**: `ANDROID_RENDERING_RESEARCH.md`
- Direct SurfaceFlinger layer via `SurfaceControl.Transaction`
- Bypasses View system entirely
- **Impact**: Theoretical minimum latency
- **Complexity**: Very High (requires platform signature/privileged)

#### 15. Wi-Fi 7 / 5G mmWave Specific Optimizations
**Research Basis**: `ANDROID_NETWORK_RESEARCH.md`, `ANDROID_POWER_THERMAL.md`
- MLO (Multi-Link Operation) for simultaneous band usage
- `WIFI_MODE_FULL_LOW_LATENCY` vs `HIGH_PERF` trade-off analysis
- 5G mmWave: high throughput but 3-5W modem power
- **Impact**: Best wireless latency, but thermal cost
- **Complexity**: Medium (connectivity manager integration)

---

## Implementation Priority Matrix

| Optimization | Latency Impact | Power Impact | Stability Impact | Complexity | Priority |
|-------------|----------------|--------------|------------------|------------|----------|
| SurfaceView + Surface Output | ⭐⭐⭐⭐⭐ (50-83ms) | ⭐⭐⭐ (60% BW) | ⭐⭐⭐ | Low | **P0** |
| Low-Latency MediaCodec | ⭐⭐⭐⭐ (16-50ms) | ⭐ (slight ↑) | ⭐⭐ | Low | **P0** |
| Hardware Overlay | ⭐⭐⭐ (16ms) | ⭐⭐ (GPU off) | ⭐⭐⭐ | Low | **P0** |
| WebRTC Gaming Config | ⭐⭐⭐⭐ (50-100ms) | ⭐ | ⭐⭐⭐ | Medium | **P0** |
| High-Priority Threads | ⭐⭐⭐ (scheduler) | ⭐ | ⭐⭐⭐ | Medium | **P0** |
| Adaptive Quality | ⭐⭐ (prevents drops) | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Medium | **P1** |
| Input Pipeline | ⭐⭐⭐ (10-30ms) | ⭐ | ⭐⭐ | Low-Med | **P1** |
| Buffer Pre-allocation | ⭐⭐ (smoothness) | ⭐⭐ | ⭐⭐ | Low | **P1** |
| Perfetto Telemetry | ⭐ (measurement) | ⭐ | ⭐⭐⭐ | Medium | **P1** |
| Codec Negotiation | ⭐⭐ (optimal HW) | ⭐⭐ | ⭐⭐⭐ | Low | **P1** |
| Frame Pacing | ⭐⭐ (VSYNC) | ⭐ | ⭐⭐ | Medium | **P2** |
| io_uring | ⭐ (1-2μs/pkt) | ⭐⭐ | ⭐ | High | **P3** |
| Vulkan Custom Render | ⭐⭐ (control) | ⭐ | ⭐ | Very High | **P3** |
| SurfaceControl | ⭐⭐⭐ (minimal) | ⭐ | ⭐ | Very High | **P3** |
| Wi-Fi 7/5G Optimizations | ⭐⭐ (wireless) | ⭐⭐ | ⭐⭐ | Medium | **P2** |

---

## Device-Specific Implementation Notes

### Qualcomm Snapdragon (Priority Target)
- **Best support**: All codecs HW, Venus VPU, good low-latency
- **Use**: Vendor MediaCodec keys for LTR control if needed
- **Thermal**: Independent VPU thermal zone, good sustained

### MediaTek Dimensity (Caution Required)
- **Android 15 HEVC regression** on 700/900/1080: Disable HEVC HW, force H.264
- **Dimensity 1000+**: AV1 HW available
- **Test**: Widevine seek behavior on secure decoder

### Samsung Exynos (Validate Per Device)
- **Exynos 2200**: AV1 Main 4K@60 may fall back to SW
- **Exynos 2400**: Better sustained, test AV1 HW
- **Limited vendor docs**: Empirical validation required

### Google Tensor (Preferred for AV1)
- **Tensor G3+**: AV1 HW decode, good platform integration
- **Thermal**: Custom TPU/VPU zones, Pixel vapor chamber effective

### HiSilicon Kirin (Deprecated)
- **Limited data**: Treat as evidence gap, test if encountered

---

## Measurement Targets (Acceptance Criteria)

| Metric | Target | Measurement Method |
|--------|--------|-------------------|
| **End-to-end latency** | < 80ms (P50) | High-speed camera / FrameTimeline |
| **Decode latency** | < 8ms (P95) | MediaCodec callback timestamps |
| **Frame drop rate** | < 0.5% | `dumpsys gfxinfo` / FrameTimeline |
| **Input latency** | < 20ms (USB) / < 35ms (BT 5.0) | High-speed camera LED method |
| **Thermal stability** | 30min no throttle | `dumpsys thermal` + CPU freq |
| **Battery life** | > 1.5h flagship | `dumpsys batterystats` |
| **Hardware overlay** | 100% frames HWC | `dumpsys SurfaceFlinger --list` |
| **Codec HW usage** | 100% frames HW | `dumpsys media.codec` |
| **Jitter buffer** | 20-50ms adaptive | WebRTC NetEq stats |

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| MediaTek HEVC Android 15 black screen | High (known bug) | Session fail | Detect device+OS, force H.264 |
| Low-latency decode unsupported on some HW | Medium | Fallback to normal | Capability query + runtime validation |
| Thermal throttling on sustained 4K/120 | High | Unplayable | Adaptive quality, max 1080p@60 default |
| WebRTC binary size (~20MB) | Medium | App size | Acceptable for gaming app |
| SurfaceView Z-order limitations | Low | UI constraints | Design overlays carefully |
| BT controller latency variance | High | Poor UX | USB OTG primary, BT 5.0+ only |
| Vendor codec bugs (crashes, corruption) | Medium | Session crash | Per-device blacklist, graceful fallback |
| Frame pacing complexity | Medium | Jank | Start simple, iterate with Perfetto |

---

## Next Steps (Not Implementation - Research Complete)

1. **Prototype Phase**: Implement Tier 1 (P0) optimizations in minimal Android app
2. **Device Lab Testing**: Validate on target device matrix (5+ devices across SoCs)
3. **Perfetto Baseline**: Establish latency/power baselines for each device
4. **Server Integration**: Coordinate codec negotiation, adaptive quality signals
5. **Iterative Refinement**: Add Tier 2 (P1) based on measurements
6. **Production Hardening**: Error recovery, telemetry, crash reporting

---

## Conclusion

The research establishes a clear, technically justified path for OpenNOW Android. The **critical path** is well-understood: SurfaceView + MediaCodec Surface output + low-latency decode + hardware overlay + gaming-tuned WebRTC + high-priority threads. This combination can achieve **sub-80ms latency** with **sustainable thermal performance** on modern Android flagships.

All opportunities in this roadmap are derived from primary Android documentation, AOSP source analysis, vendor specifications, and community-validated patterns (Chromium, ExoPlayer, Moonlight, WebRTC). No speculative or unproven techniques are included.

**The research phase is complete. Implementation should proceed with measurement-driven validation at each step.**