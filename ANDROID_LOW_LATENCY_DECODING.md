# Android Low-Latency Decoding Research for OpenNOW

## Overview
Deep investigation of Android's low-latency video decoding features: `FEATURE_LowLatency`, `KEY_LOW_LATENCY`, `PARAMETER_KEY_LOW_LATENCY`. Critical for cloud gaming where end-to-end latency must be minimized.

## API Surface

### Java API (API 30+ / Android 11+)

| Key/Constant | Type | Scope | Description |
|-------------|------|-------|-------------|
| `MediaFormat.KEY_LOW_LATENCY` | String ("low-latency") | Configure-time | Set to 1 on MediaFormat before `configure()` |
| `MediaCodec.PARAMETER_KEY_LOW_LATENCY` | String ("low-latency") | Runtime | Set via `setParameters()` after start |
| `CodecCapabilities.FEATURE_LowLatency` | String ("low-latency") | Capability Query | Check via `isFeatureSupported()` |

### NDK API (API 30+)

| Key/Constant | Type | Scope | Description |
|-------------|------|-------|-------------|
| `AMEDIAFORMAT_KEY_LOW_LATENCY` | const char* | Configure-time | Set on AMediaFormat before `AMediaCodec_configure()` |
| `AMEDIACODEC_PARAMETER_KEY_LOW_LATENCY` | const char* | Runtime | Set via `AMediaCodec_setParameters()` |

## Capability Detection

### Before Configuration
```java
MediaCodecInfo codecInfo = ...; // from MediaCodecList
CodecCapabilities caps = codecInfo.getCapabilitiesForType("video/avc");
boolean supportsLowLatency = caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency);

// Also check hardware acceleration
boolean isHardware = codecInfo.isHardwareAccelerated();  // API 29+
boolean isVendor = codecInfo.isVendor();  // API 29+
```

### Runtime Validation
Even if `FEATURE_LowLatency` is reported, actual behavior varies by vendor implementation. Must validate at runtime.

## Configuration Sequence

### Java (Recommended Pattern)
```java
// 1. Create format with low-latency flag
MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);

// 2. Configure with Surface output (critical for zero-copy)
Surface surface = ...; // from SurfaceView/TextureView/SurfaceTexture
codec.configure(format, surface, null, 0);

// 3. Start codec
codec.start();

// 4. Enable runtime low-latency (belt-and-suspenders)
Bundle params = new Bundle();
params.putInt(MediaCodec.PARAMETER_KEY_LOW_LATENCY, 1);
codec.setParameters(params);
```

### NDK Pattern
```c
AMediaFormat* format = AMediaFormat_new();
AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, 1920);
AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, 1080);
AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_LOW_LATENCY, 1);

AMediaCodec* codec = AMediaCodec_createDecoderByType("video/avc");
ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
AMediaCodec_configure(codec, format, window, NULL, 0);
AMediaCodec_start(codec);

// Runtime enable
AMediaFormat* params = AMediaFormat_new();
AMediaFormat_setInt32(params, AMEDIACODEC_PARAMETER_KEY_LOW_LATENCY, 1);
AMediaCodec_setParameters(codec, params);
AMediaFormat_delete(params);
```

## Behavior When Unsupported

| Scenario | Behavior | Mitigation |
|----------|----------|------------|
| Codec doesn't support FEATURE_LowLatency | `setParameters()` may be ignored or throw | Graceful degradation - continue without |
| Vendor driver ignores KEY_LOW_LATENCY | No latency improvement, no error | Runtime validation required |
| Low-latency causes instability | Decoder crashes, frame corruption | Disable per device/model |
| Partial support (some resolutions only) | Works at 1080p, fails at 4K | Test per resolution tier |

## Interaction with Buffering

### Without Low-Latency (Default)
- Decoder may hold multiple frames internally for:
  - Reference frame management (B-frames, P-frames)
  - Smoothing output timing
  - Error concealment buffering
- Typical internal buffer: 3-8 frames (50-133ms at 60fps)

### With Low-Latency Enabled
- Decoder minimizes internal buffering
- Output frames as soon as decoded (respecting codec standards)
- Reduces decoder contribution to latency by 1-3 frames
- **Trade-off**: Less resilience to network jitter, potential for more visible artifacts on packet loss

### Jitter Buffer Coordination
```
Network Jitter Buffer (WebRTC): 50-200ms (adaptive)
    ↓
MediaCodec Input Queue: 1-2 frames
    ↓
Hardware Decoder (with low-latency): 1-2 frames internal
    ↓
BufferQueue: 2-3 frames queued to SurfaceFlinger
    ↓
SurfaceFlinger Composition: 0-1 frame
    ↓
Display: VSYNC aligned
```

**Recommendation**: Reduce WebRTC jitter buffer when low-latency decode is active, since decoder adds less latency.

## Interaction with Surface Rendering

### SurfaceView (Preferred for Low Latency)
- Direct BufferQueue → SurfaceFlinger path
- Hardware overlay possible (bypass GPU composition)
- Lowest latency surface type

### TextureView
- Extra GPU copy: BufferQueue → GPU texture → View hierarchy → SurfaceFlinger
- Adds 1-3 frames latency
- **Avoid for cloud gaming**

### SurfaceTexture + Custom Rendering
- Most control but most complexity
- Can implement custom frame pacing
- Risk: easy to introduce extra frames if not careful

## Power/Thermal Cost

| Aspect | Impact |
|--------|--------|
| **Decoder Power** | Slight increase (less frame buffering = more frequent wake/schedule) |
| **Thermal** | Minimal direct impact |
| **Battery** | ~1-3% increase in decode power |
| **Trade-off** | Acceptable for cloud gaming where latency is paramount |

## Vendor-Specific Behavior

### Qualcomm (Venus VPU)
- Supports low-latency via vendor MediaCodec keys
- May require specific profile (constrained baseline) for best effect
- LTR (Long-Term Reference) frame control via vendor extensions

### MediaTek
- Limited documentation on low-latency support
- Android 15 regressions suggest driver instability
- Test per device model

### Samsung Exynos
- Variable support across generations
- Exynos 2200/2400 likely support but undocumented

### Google Tensor
- Good platform integration (Google controls both)
- Tensor G3+ expected to support properly

## Testing & Validation Strategy

### 1. Capability Query Test
```java
// Run at app startup, cache results per device model
Map<String, Boolean> lowLatencySupport = new HashMap<>();
for (String mime : Arrays.asList("video/avc", "video/hevc", "video/vp9", "video/av01")) {
    MediaCodecInfo decoder = findDecoderForType(mime);
    if (decoder != null) {
        CodecCapabilities caps = decoder.getCapabilitiesForType(mime);
        lowLatencySupport.put(mime, caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency));
    }
}
```

### 2. Latency Measurement Test
```
1. Encode test frames with known presentation timestamps (PTS)
2. Send via WebRTC to client
3. Client: Record wall-clock when:
   - Frame received from network
   - Frame queued to MediaCodec (dequeueInputBuffer)
   - Frame dequeued from MediaCodec (dequeueOutputBuffer)  
   - Frame presented (SurfaceTexture.OnFrameAvailable + Choreographer)
4. Compare with/without KEY_LOW_LATENCY
5. Cache results per device+codec+resolution
```

### 3. Stability Test
- Run 30-minute decode stress test with low-latency enabled
- Monitor for: crashes, frame corruption, ANRs, thermal throttling
- Test resolution changes (adaptive playback)

## Implementation Checklist for OpenNOW Android

- [ ] Query `FEATURE_LowLatency` per codec at startup
- [ ] Configure `KEY_LOW_LATENCY=1` on MediaFormat for supported codecs
- [ ] Call `setParameters(PARAMETER_KEY_LOW_LATENCY=1)` after codec start
- [ ] Use SurfaceView (not TextureView) for rendering
- [ ] Reduce WebRTC jitter buffer target when low-latency active
- [ ] Implement per-device fallback (disable if instability detected)
- [ ] Add telemetry: decoder name, low-latency enabled, measured latency
- [ ] Test on target device matrix (Snapdragon 8 Gen 2/3, Tensor G3, Dimensity 9000+)

## References
- [Android Low-latency Media](https://source.android.com/docs/core/media/low-latency-media)
- [MediaCodec API Reference](https://developer.android.com/reference/android/media/MediaCodec)
- [MediaFormat API Reference](https://developer.android.com/reference/android/media/MediaFormat)
- [NDK Media API](https://developer.android.com/ndk/reference/group/media)
- [CodecCapabilities FEATURE_LowLatency](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#FEATURE_LowLatency)