# Android Media Pipeline Research for OpenNOW

## Overview
This document provides a deep technical analysis of the Android media pipeline components relevant to cloud gaming video streaming. OpenNOW currently exists as an Electron desktop application using WebRTC for video streaming. This research establishes the baseline architecture for an Android-first implementation.

## Core Pipeline Components

### 1. MediaCodec (Android SDK / NDK)
**Primary hardware video decoder interface**

- **Architecture**: Asynchronous callback-based (API 21+) or synchronous polling (legacy)
- **Buffer Model**: 
  - Input buffers: App requests empty buffer → fills with encoded data → queues to codec
  - Output buffers: Codec produces decoded frames → app dequeues → renders/releases
- **Key Classes**:
  - `MediaCodec` (Java) / `AMediaCodec` (NDK)
  - `MediaFormat` / `AMediaFormat` - codec configuration
  - `MediaCodecInfo` / `AMediaCodecInfo` - capability enumeration
  - `CodecCapabilities` - feature detection

### 2. MediaCodec Configuration for Cloud Gaming

```java
// Java API pattern
MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);  // API 30+
MediaCodec codec = MediaCodec.createDecoderByType("video/avc");
codec.configure(format, surface, null, 0);  // Surface output for zero-copy
codec.start();
```

```c
// NDK API pattern (API 30+)
AMediaFormat* format = AMediaFormat_new();
AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);
AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_LOW_LATENCY, 1);  // API 30+
AMediaCodec* codec = AMediaCodec_createDecoderByType("video/avc");
AMediaCodec_configure(codec, format, nativeWindow, NULL, 0);
AMediaCodec_start(codec);
```

### 3. Low-Latency Decoding Features (API 30+)

| Feature | Java Key | NDK Key | Purpose |
|---------|----------|---------|---------|
| Configure-time | `MediaFormat.KEY_LOW_LATENCY` | `AMEDIAFORMAT_KEY_LOW_LATENCY` | Request low-latency mode at codec creation |
| Runtime | `MediaCodec.PARAMETER_KEY_LOW_LATENCY` | `AMEDIACODEC_PARAMETER_KEY_LOW_LATENCY` | Enable/disable at runtime |
| Capability | `CodecCapabilities.FEATURE_LowLatency` | N/A (query via CodecCapabilities) | Check if decoder supports low-latency |

**Requirements**:
- Android 11 (API 30) minimum
- Vendor decoder driver must implement Codec2/OMX low-latency parameters
- Not all hardware decoders support this feature - must query at runtime

### 4. Surface Output Path

**Zero-copy decoder → display path**:
```
MediaCodec (hardware decoder)
    ↓ configure(surface, ...)
Surface (ANativeWindow)
    ↓ BufferQueue
SurfaceFlinger (compositor)
    ↓ Hardware Composer (HWC) / GPU
Display
```

**Critical**: Pass `Surface` to `MediaCodec.configure()` to enable direct buffer flow into BufferQueue. Avoids CPU copies from decoder output buffers.

### 5. BufferQueue & SurfaceFlinger

- **BufferQueue**: Producer-consumer queue between decoder (producer) and SurfaceFlinger (consumer)
- **Typical queue depth**: 2-4 buffers for video
- **Synchronization**: Fence-based (sync framework) - decoder signals when buffer ready, SurfaceFlinger waits on fence
- **Queue behavior**: When full, `dequeueOutputBuffer` blocks; when empty, SurfaceFlinger waits

### 6. Choreographer & VSYNC

- **Choreographer**: Schedules frame callbacks aligned to VSYNC
- **VSYNC**: Display refresh signal (typically 60Hz = 16.67ms, 90Hz = 11.11ms, 120Hz = 8.33ms)
- **FrameTimeline (API 33+)**: Exposes detailed frame lifecycle timestamps:
  - `FRAME_TIMELINE_TYPE_INPUT`
  - `FRAME_TIMELINE_TYPE_ANIMATION`
  - `FRAME_TIMELINE_TYPE_PERFORM_TRAVERSALS`
  - `FRAME_TIMELINE_TYPE_DRAW`
  - `FRAME_TIMELINE_TYPE_SYNC_START`
  - `FRAME_TIMELINE_TYPE_SYNC_QUEUED`
  - `FRAME_TIMELINE_TYPE_PRESENTED`

### 7. HardwareBuffer / AHardwareBuffer

- **Purpose**: Share GPU-accessible memory across processes/components
- **Zero-copy**: `AHardwareBuffer` operations are zero-copy (shared memory view)
- **Formats**: `YCBCR_420_888`, `RGBA_8888`, `R_8`, etc.
- **Usage flags**: `USAGE_VIDEO_DECODE`, `USAGE_GPU_SAMPLED_IMAGE`, `USAGE_COMPOSER_OVERLAY`
- **MediaCodec integration**: `MediaCodec.setOutputSurface()` / `setHardwareBuffer()` for advanced buffer management

### 8. Media3 (ExoPlayer) Considerations

**Not currently used by OpenNOW** - OpenNOW uses raw WebRTC + MediaCodec directly.
Media3 provides:
- `MediaCodecAdapter` - wrapper with better async handling
- `DefaultRenderersFactory` - decoder selection logic
- `SurfaceView`/`TextureView` integration
- Adaptive playback support (`CodecCapabilities.FEATURE_AdaptivePlayback`)

**If migrating to Media3**: Would need to evaluate adapter overhead vs. benefits.

## Pipeline Summary for Cloud Gaming

```
Network (WebRTC/UDP)
    ↓
RTP Depacketization (WebRTC)
    ↓
Jitter Buffer (WebRTC - typically 100-500ms)
    ↓
MediaCodec Input Buffer (dequeueInputBuffer)
    ↓ copy
MediaCodec Hardware Decoder
    ↓ zero-copy (Surface output)
BufferQueue (ANativeWindow)
    ↓ fence sync
SurfaceFlinger Composition
    ↓ HWC overlay or GPU
Display (VSYNC aligned)
```

## Key Latency Contributors

| Stage | Typical Latency | Optimization Target |
|-------|-----------------|---------------------|
| Network RTT | 10-100ms | Server proximity, congestion control |
| Jitter Buffer | 50-200ms | Adaptive sizing, low-latency mode |
| Depacketization | <1ms | Minimal |
| MediaCodec Input Queue | 1-5ms | Async callbacks, pre-queue buffers |
| Hardware Decode | 2-10ms | Low-latency mode, profile selection |
| BufferQueue → SurfaceFlinger | 1-2 frames (16-33ms) | Reduce queue depth, direct overlay |
| Display Scanout | 0-1 frame | High refresh rate, VRR |

## References
- [Android MediaCodec API](https://developer.android.com/reference/android/media/MediaCodec)
- [Low-latency MediaCodec](https://source.android.com/docs/core/media/low-latency-media)
- [NDK Media API](https://developer.android.com/ndk/reference/group/media)
- [BufferQueue Architecture](https://source.android.com/docs/core/graphics/arch-bq-gralloc)
- [FrameTimeline](https://developer.android.com/media/optimize/performance/frame-rate)