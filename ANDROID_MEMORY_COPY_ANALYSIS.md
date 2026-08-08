# Android Memory Copy Analysis for OpenNOW Cloud Gaming

## Overview
Identification of every memory copy in the cloud gaming video pipeline from network packet to display photon. Goal: minimize copies to reduce latency, power, and memory bandwidth.

## Complete Pipeline with Copy Analysis

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ NETWORK → DISPLAY PIPELINE                                                   │
├──────────────────┬──────────────────┬──────────────────┬────────────────────┤
│ STAGE            │ COPY TYPE        │ BUFFER OWNER     │ OPTIMIZATION       │
├──────────────────┼──────────────────┼──────────────────┼────────────────────┤
│ 1. NIC RX Ring   │ Zero-copy (DMA)  │ Kernel skb       │ RSS/RFS            │
│ 2. Kernel → User │ COPY (recvmsg)   │ Kernel → App     │ recvmmsg, io_uring │
│ 3. WebRTC RTP    │ COPY (parse)     │ WebRTC buffers   │ In-place parse     │
│ 4. Jitter Buffer │ COPY (reorder)   │ NetEq buffers    │ Pre-allocated pool │
│ 5. Depacketize   │ COPY (assemble)  │ Frame buffer     │ Zero-copy NAL ref  │
│ 6. MediaCodec In │ COPY (queueInput)│ Codec input buf  │ ★ Surface input    │
│ 7. HW Decode     │ Zero-copy (HW)   │ Codec internal   │ Vendor dependent   │
│ 8. Decode Output │ Zero-copy*       │ Codec output buf │ ★ Surface output   │
│ 9. BufferQueue   │ Reference        │ Gralloc buffer   │ Fence sync         │
│ 10. SurfaceFlinger│ Zero-copy (HWC) │ Display buffer   │ Hardware overlay   │
│ 11. Display      │ Scanout          │ Panel buffer     │ VSYNC aligned      │
└──────────────────┴──────────────────┴──────────────────┴────────────────────┘

* = Zero-copy only when using Surface output path
```

## Detailed Stage Analysis

### Stage 1: NIC Receive (Zero-Copy DMA)
- **Mechanism**: NIC DMA writes directly to kernel `sk_buff` ring buffer
- **Copies**: 0 (hardware → kernel memory)
- **Android**: `mac80211` (Wi-Fi) / `rmnet` (cellular) drivers
- **Optimization**: RSS (Receive Side Scaling) distributes across CPU cores

### Stage 2: Kernel → Userspace (COPY #1 - Unavoidable without io_uring)
```c
// Standard path: recvmsg() copies from skb to userspace iovec
recvmsg(fd, &msg, MSG_DONTWAIT);

// Optimization: recvmmsg() - batch multiple packets per syscall
recvmmsg(fd, mmsg, vlen, MSG_DONTWAIT, NULL);

// Future: io_uring (Linux 5.10+/Android 12+) - zero-copy potential
// IORING_OP_RECVMSG with IORING_FEAT_SINGLE_MMAP
```
- **Copy Size**: MTU (1500 bytes) × packets per frame (~100 for 1080p@60)
- **Frequency**: ~6000 calls/sec at 60fps
- **Cost**: ~1-2μs per call + copy bandwidth

### Stage 3: WebRTC RTP Parsing (COPY #2 - Partial)
```cpp
// WebRTC RtpReceiver::OnReceivedPacket()
void OnReceivedPacket(const uint8_t* data, size_t len) {
    // Parses RTP header, copies payload to internal buffer
    // Can avoid copy for single-packet frames (STAP-A)
}
```
- **Copies**: 
  - RTP header stripped (minimal)
  - Payload referenced or copied to `RtpPacketReceived`
  - **Optimization**: Single-packet frames can avoid payload copy

### Stage 4: Jitter Buffer / NetEq (COPY #3 - Buffer Management)
```cpp
// NetEq inserts packet into jitter buffer
// Maintains packet buffer pool (pre-allocated)
// Copies: packet → jitter buffer slot (if not single-packet frame)
```
- **Buffer Pool**: Pre-allocated at startup (avoids malloc during streaming)
- **Reordering**: Packet copies for out-of-order arrival
- **Typical**: 50-200ms buffering = 3-12 frames at 60fps

### Stage 5: Depacketization / Frame Assembly (COPY #4)
```cpp
// H.264: FU-A fragmentation → reassemble NAL units
// H.265: AP/FU → reassemble
// VP9/AV1: Similar
// Output: Complete frame in contiguous buffer
```
- **Copies**: Fragment buffers → single frame buffer
- **Optimization**: Reference counting, avoid copy for single-NAL frames

### Stage 6: MediaCodec Input (COPY #5 - CRITICAL)
```java
// Standard ByteBuffer path (COPIES)
ByteBuffer inputBuffer = codec.getInputBuffer(index);
inputBuffer.put(frameData);  // COPY: App buffer → Codec input buffer
codec.queueInputBuffer(index, offset, size, timestamp, flags);

// Surface Input Path (ZERO-COPY)
// codec.configure(format, surface, ...);
// No input buffers - decoder writes directly to Surface/BufferQueue
```
- **ByteBuffer Path**: 1 full frame copy per frame (1080p = ~3MB for NV12)
- **Surface Path**: **ZERO COPY** - decoder writes to BufferQueue directly
- **Recommendation**: **ALWAYS use Surface output for cloud gaming**

### Stage 7: Hardware Decode (Zero-Copy Internal)
- **Mechanism**: VPU reads from input buffer, writes to internal decoded buffer
- **Memory**: Vendor-specific (VPU local memory, CMA, ION, gralloc)
- **Copies**: 0 (hardware processes in-place)
- **Note**: Some vendors may have internal copies between VPU stages

### Stage 8: MediaCodec Output (ZERO-COPY with Surface)
```java
// ByteBuffer path (COPIES)
ByteBuffer outputBuffer = codec.getOutputBuffer(index);
// Process/copy to render target
codec.releaseOutputBuffer(index, false);

// Surface path (ZERO-COPY)
codec.releaseOutputBuffer(index, true);  // render=true
// Buffer queued to BufferQueue via fence - NO CPU COPY
```
- **Surface Path**: Decoder output buffer → BufferQueue (reference transfer)
- **Fence Sync**: VPU signals fence when decode complete, SurfaceFlinger waits

### Stage 9: BufferQueue (Reference Transfer)
```cpp
// Producer (MediaCodec/VPU)
queueBuffer(slot, fence, timestamp, ...);

// Consumer (SurfaceFlinger)  
acquireBuffer(&slot, &fence, ...);
// Wait on fence → access buffer
```
- **Mechanism**: BufferQueue slots reference gralloc buffers
- **Copies**: 0 (slot index + fence passed)
- **Sync**: Kernel sync framework (dma_fence / sync_file)

### Stage 10: SurfaceFlinger Composition
```cpp
// Two paths:
if (layer.canUseOverlay()) {
    // HWC Overlay: ZERO-COPY - HWC reads buffer directly
    hwc.setLayerBuffer(layer, buffer, acquireFence, releaseFence);
} else {
    // GPU Composition: COPY - GPU reads buffer, renders to output
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, buffer);
    // ... draw quad ...
}
```
- **Hardware Overlay (Preferred)**: 0 copies, dedicated display plane
- **GPU Composition**: 1 GPU read (texture sample) - not CPU copy
- **Conditions for Overlay**: 
  - Full-screen, no transform, opaque, supported format (NV12, P010)

### Stage 11: Display Scanout
- **Mechanism**: Display controller reads framebuffer at VSYNC
- **Copies**: 0 (hardware reads memory directly)
- **Latency**: Depends on VSYNC phase when buffer ready

## Copy Summary Table

| # | Stage | Copy Type | Size/Frame (1080p) | Frequency | Avoidable? |
|---|-------|-----------|-------------------|-----------|------------|
| 1 | NIC DMA | Zero-copy | - | - | N/A |
| 2 | Kernel→User | **CPU Copy** | ~150KB (packets) | 6000/sec | io_uring* |
| 3 | RTP Parse | Partial Copy | ~3MB | 60/sec | Partially |
| 4 | Jitter Buffer | **CPU Copy** | ~3MB | 60/sec | Pool/Refcount |
| 5 | Depacketize | **CPU Copy** | ~3MB | 60/sec | Partially |
| 6 | MediaCodec In | **CPU Copy** | ~3MB | 60/sec | ✅ Surface In |
| 7 | HW Decode | Zero-copy | - | - | N/A |
| 8 | MediaCodec Out | **CPU Copy** | ~3MB | 60/sec | ✅ Surface Out |
| 9 | BufferQueue | Reference | - | 60/sec | N/A (optimal) |
| 10 | SurfaceFlinger | Zero-copy (HWC) | - | 60/sec | ✅ Overlay |
| 11 | Display | Zero-copy | - | - | N/A |

**Total CPU Copies (ByteBuffer path)**: ~5 copies × 3MB = **~15MB/frame = 900MB/s at 60fps**
**Total CPU Copies (Surface path)**: ~2 copies × 3MB = **~6MB/frame = 360MB/s at 60fps**

## Zero-Copy Optimization Strategies

### 1. Use Surface Input/Output (Mandatory)
```java
// Configure decoder with Surface - eliminates stages 6 & 8 copies
MediaFormat format = ...;
format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
codec.configure(format, surfaceView.getHolder().getSurface(), null, 0);
```

### 2. Hardware Overlay Requirements
```java
// Ensure SurfaceView qualifies for overlay:
// - Full screen (match display resolution)
// - No transform (rotation, scale)
// - Opaque (no alpha)
// - Supported format (NV12, P010)
// - Single video layer
surfaceView.setZOrderMediaOverlay(true);  // Or setZOrderOnTop(true)
```

### 3. Buffer Pool Pre-allocation
```java
// WebRTC: Pre-allocate packet/frame buffers at startup
// MediaCodec: Buffers allocated by codec (configure with appropriate count)
// BufferQueue: Fixed slot count (typically 3-4)
```

### 4. Avoid TextureView (Extra GPU Copy)
```java
// BAD: TextureView adds GPU copy
TextureView textureView = ...;
codec.configure(format, textureView.getSurfaceTexture(), ...);

// GOOD: SurfaceView direct to SurfaceFlinger
SurfaceView surfaceView = ...;
codec.configure(format, surfaceView.getHolder().getSurface(), ...);
```

### 5. NDK MediaCodec with AHardwareBuffer (Advanced)
```c
// For custom render path - zero-copy to Vulkan/OpenGL
AHardwareBuffer* buffer = ...;
AMediaCodec_setOutputSurface(codec, nativeWindow);  // Still uses BufferQueue
// Or: AMediaImageReader + AHardwareBuffer for manual control
```

## Memory Bandwidth Calculation

### 1080p@60 NV12 (1.5 bytes/pixel)
- Frame size: 1920 × 1080 × 1.5 = 3,110,400 bytes ≈ 3 MB
- Bandwidth per copy: 3 MB × 60 = 180 MB/s

### Copy Budget Comparison

| Path | Copies | Bandwidth | Power Impact |
|------|--------|-----------|--------------|
| ByteBuffer → ByteBuffer | 5 | 900 MB/s | High (DDR active) |
| ByteBuffer → Surface | 3 | 540 MB/s | Medium |
| Surface → Surface | 1 | 180 MB/s | **Low (optimal)** |
| Surface → Overlay | 0 (GPU) | 0 CPU | **Lowest** |

## Android Version Differences

| Feature | API Level | Impact |
|---------|-----------|--------|
| MediaCodec async callback | 21 | Avoids polling overhead |
| Surface output to MediaCodec | 16 | Zero-copy decode output |
| MediaCodec.setOutputSurface | 23 | Dynamic surface switching |
| AHardwareBuffer | 26 | Native buffer sharing |
| Low-latency decode | 30 | Reduces internal buffering |
| FrameRate API | 30 | VSYNC alignment hints |
| SurfaceControl | 29 | Direct layer control (privileged) |

## Recommendations for OpenNOW Android

1. **Mandatory**: Use SurfaceView + MediaCodec Surface output
2. **Mandatory**: Ensure hardware overlay qualification (full-screen, opaque)
3. **Recommended**: Pre-allocate WebRTC buffer pools at session start
4. **Recommended**: Use `recvmmsg()` for batch packet receive (if custom UDP)
5. **Advanced**: Consider `io_uring` on Android 12+ for kernel→user zero-copy
6. **Avoid**: TextureView, software rendering, unnecessary buffer copies
7. **Monitor**: `dumpsys gfxinfo` for GPU composition vs overlay confirmation

## References
- [Big Flake MediaCodec](https://bigflake.com/mediacodec/) - Buffer flow diagrams
- [BufferQueue Architecture](https://source.android.com/docs/core/graphics/arch-bq-gralloc)
- [AHardwareBuffer NDK](https://developer.android.com/ndk/reference/group/a-hardware-buffer)
- [SurfaceFlinger Hardware Composer](https://source.android.com/docs/core/graphics/arch-hwc)
- [io_uring on Android](https://source.android.com/docs/core/io/uring)