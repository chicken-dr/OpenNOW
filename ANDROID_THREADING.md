# Android Threading Model Research for OpenNOW Cloud Gaming

## Overview
Mapping of all threads involved in the cloud gaming pipeline on Android, identifying contention points, scheduling interactions, and optimization opportunities.

## Thread Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│ ANDROID CLOUD GAMING THREAD MAP                                                  │
├─────────────────────┬───────────────────────────────────────────────────────────┤
│ THREAD              │ RESPONSIBILITY                                            │
├─────────────────────┼───────────────────────────────────────────────────────────┤
│ UI Thread (Main)    │ View hierarchy, input dispatch, Choreographer callbacks  │
│ RenderThread        │ GPU command submission (HWUI), View drawing              │
│ WebRTC Network      │ UDP socket recv, RTP parsing, jitter buffer (NetEq)      │
│ WebRTC Decoder      │ MediaCodec callback / dequeueOutputBuffer loop           │
│ WebRTC Input        │ Input encoding, packetization, sendto()                  │
│ MediaCodec Internal │ VPU driver threads (kernel), codec worker threads        │
│ SurfaceFlinger      │ Composition, VSYNC, BufferQueue management               │
│ HWC / Display       │ Hardware composer, display controller                    │
│ Choreographer       │ VSYNC-aligned frame callbacks                            │
│ AudioTrack          │ Audio playback, mixer thread                             │
│ Binder Threads      │ IPC (MediaCodec, SurfaceFlinger, AudioFlinger)           │
└─────────────────────┴───────────────────────────────────────────────────────────┘
```

## Detailed Thread Analysis

### 1. UI Thread (Main Looper)
- **Priority**: `THREAD_PRIORITY_DISPLAY` (-4) / `THREAD_PRIORITY_URGENT_DISPLAY` (-8)
- **Work**: 
  - `ViewRootImpl.performTraversals()` (measure/layout/draw)
  - Input event dispatch (`View.dispatchTouchEvent()`)
  - `Choreographer.FrameCallback` execution
  - Binder callbacks (MediaCodec, Surface callbacks)
- **Contention Risks**:
  - Heavy layout/work blocks input processing
  - GC pauses (especially with TextureView `onFrameAvailable` JNI attach)
  - Binder transaction latency (MediaCodec callbacks)
- **Optimization**: Minimize work; offload to background threads

### 2. RenderThread (HWUI)
- **Priority**: `THREAD_PRIORITY_URGENT_DISPLAY` (-8)
- **Work**:
  - Records GPU commands from `View.draw()`
  - Submits to GPU via `eglSwapBuffers` / Vulkan queue submit
  - Synchronizes with SurfaceFlinger via fences
- **Contention Risks**:
  - Complex UI drawing delays frame submission
  - GPU backend busy (driver thread)
- **Note**: Decoupled from UI thread since Android 5.0

### 3. WebRTC Network Thread
- **Priority**: `THREAD_PRIORITY_URGENT_AUDIO` (-16) or custom high
- **Work**:
  - `recvfrom()` / `recvmmsg()` on UDP socket
  - RTP header parsing
  - Jitter buffer (NetEq) insertion
  - NACK/PLI generation
  - Bandwidth estimation (GCC)
- **Blocking Points**:
  - `recvfrom()` blocking on socket
  - NetEq mutex for buffer access
- **Android Specific**: 
  - Runs on dedicated `NetworkThread` in WebRTC
  - JNI calls to Java `MediaCodecVideoDecoder` for frame delivery

### 4. WebRTC Decoder Thread
- **Priority**: High (user-set)
- **Work** (Callback Mode - API 21+):
  - `MediaCodec.Callback.onInputBufferAvailable()` → `queueInputBuffer()`
  - `MediaCodec.Callback.onOutputBufferAvailable()` → `releaseOutputBuffer()`
- **Work** (Polling Mode - Legacy):
  - Loop: `dequeueInputBuffer()` → `queueInputBuffer()`
  - Loop: `dequeueOutputBuffer()` → `releaseOutputBuffer()`
- **Blocking Points**:
  - `dequeueInputBuffer()` timeout (codec input full)
  - `dequeueOutputBuffer()` timeout (no decoded frame ready)
- **JNI Overhead**: Each callback crosses JNI boundary

### 5. WebRTC Input Thread
- **Priority**: High
- **Work**:
  - Gamepad/touch/keyboard event capture
  - Input encoding (protocol v3)
  - `sendto()` UDP packets
  - Timestamp capture (`captureTimestampUs()`)
- **Latency Critical**: Input → network send must be <1ms

### 6. MediaCodec Internal Threads (Vendor)
- **Location**: Kernel (VPU driver) / vendor userspace daemons
- **Threads**:
  - VPU firmware command processor
  - Buffer management (ION/CMA/gralloc)
  - Interrupt handlers (decode complete)
- **Scheduling**: 
  - Often `SCHED_FIFO` / `SCHED_RR` real-time
  - CPU affinity to big cores (vendor dependent)

### 7. SurfaceFlinger Main Thread
- **Priority**: `THREAD_PRIORITY_URGENT_DISPLAY` (-8) / RT
- **Work**:
  - `Transaction` processing (layer updates)
  - Composition scheduling (VSYNC aligned)
  - BufferQueue acquire/release
  - HWC communication
- **VSYNC Loop**:
  ```
  VSYNC interrupt
    ↓
  SurfaceFlinger wakes
    ↓
  Collect layer updates
    ↓
  HWC prepare/set
    ↓
  Present fence signaled
  ```

### 8. Hardware Composer (HWC) / Display
- **Location**: Kernel (display driver) / vendor HWC HAL
- **Work**:
  - Layer composition planning (overlay vs GPU)
  - Display controller programming
  - VSYNC generation
- **Scheduling**: Real-time, often dedicated CPU core

### 9. Choreographer
- **Runs on**: UI Thread (main looper)
- **Work**:
  - `doFrame()` at VSYNC
  - Execute `FrameCallback`s (input, animation, traversal)
  - Post next VSYNC callback
- **FrameTimeline** (API 33+): Exposes per-stage timestamps

### 10. Binder Threads (IPC)
- **Pool**: `Binder` driver manages thread pool per process
- **Work**:
  - MediaCodec ↔ MediaServer (codec callbacks)
  - SurfaceFlinger ↔ App (BufferQueue, SurfaceControl)
  - AudioFlinger ↔ App (AudioTrack)
- **Contention**: Binder lock, transaction latency (typically 50-200μs)

## Thread Interaction Diagram

```
NETWORK                    DECODER                    RENDER/DISPLAY
────────────────────────────────────────────────────────────────────
                                                                      
WebRTC NetThread                                                        
    │ recvfrom()                                                         
    ▼                                                                   
RTP Parse ──▶ NetEq Jitter Buffer                                        
    │                                                                   
    │ Frame Ready Callback                                              
    ▼                                                                   
┌──────────────────────────────────────────────────────────────────┐   
│ JNI Boundary (WebRTC C++ → Java MediaCodec)                      │   
└──────────────────────────────────────────────────────────────────┘   
    │                                                                   
    ▼                                                                   
MediaCodec.Callback.onInputBufferAvailable()                         
    │                                                                   
    ▼                                                                   
queueInputBuffer(encodedData) ──▶ VPU Driver (Kernel)                
    │                                                                   
    │ (async, VPU processes)                                          
    ▼                                                                   
MediaCodec.Callback.onOutputBufferAvailable()                         
    │                                                                   
    ▼                                                                   
releaseOutputBuffer(render=true) ──▶ BufferQueue (gralloc)           
    │                                                                   
    │ (fence signaled when VPU done)                                  
    ▼                                                                   
SurfaceFlinger acquires buffer                                        
    │                                                                   
    ├──▶ HWC Overlay Path (zero-copy) ──▶ Display                     
    │                                                                   
    └──▶ GPU Composition ──▶ RenderThread ──▶ SurfaceFlinger ──▶ Display
                                                                      
                                                                      
INPUT THREAD                                                          
────────────────────────────────────────────────────────────────────   
                                                                      
Gamepad/Touch ──▶ Input Encoding ──▶ sendto() ──▶ Network           
    │                                                                   
    ▼                                                                   
Capture Timestamp (critical for input latency)                        
```

## Contention Analysis

### 1. UI Thread vs Decoder Callbacks
```java
// MediaCodec callbacks run on binder thread → posted to UI thread handler
// Risk: UI thread busy → callback delayed → decoder starves
// Mitigation: 
// - Use MediaCodec async callbacks (API 21+) - run on codec's internal thread
// - Or: Dedicated decoder thread with Handler + Looper
```

### 2. TextureView GC Pressure
```java
// TextureView.onFrameAvailable() runs on arbitrary thread
// WebRTC: Attaches JNI thread → creates java.lang.Thread object
// At 60fps: 60 Thread objects/sec → GC pressure → UI jank
// Solution: SurfaceView (callbacks on UI thread via SurfaceHolder)
```

### 3. Binder Transaction Latency
```
App → MediaServer (MediaCodec): ~100-500μs per call
QueueInputBuffer/ReleaseOutputBuffer: 2 calls/frame = 200-1000μs
At 60fps: 12-60ms/sec in binder overhead
Mitigation: Batch where possible, async callbacks
```

### 4. CPU Frequency Scaling (big.LITTLE)
```kotlin
// Decoder thread should run on big cores
// Android 11+: setThreadAffinity() or cpuset
// Thermal throttling moves threads to LITTLE cores → decode latency spikes
// Monitoring: Perfetto CPU Frequency track
```

### 5. VSYNC Alignment Race
```
Scenario: Frame decoded just after VSYNC
    ↓
Misses current composition cycle
    ↓
Waits full frame (16.67ms @ 60Hz) for next VSYNC
    ↓
Extra frame latency

Mitigation: 
- Low-latency decode (reduce decode time)
- FrameRate API: surface.setFrameRate(60, FIXED_SOURCE)
- Present early (target previous VSYNC deadline)
```

## Thread Priority Recommendations

| Thread | Priority (nice) | Sched Policy | CPU Affinity |
|--------|----------------|--------------|--------------|
| UI Thread | -8 (URGENT_DISPLAY) | CFS | Any |
| RenderThread | -8 (URGENT_DISPLAY) | CFS | Big preferred |
| WebRTC Network | -16 (URGENT_AUDIO) | CFS | Big |
| WebRTC Decoder | -12 (custom high) | CFS | Big (critical) |
| WebRTC Input | -12 (custom high) | CFS | Big |
| SurfaceFlinger | -8 / RT | RT/FIFO | Dedicated big |
| HWC/Display | RT | RT/FIFO | Dedicated |

```java
// Setting thread priority
Thread decoderThread = new Thread(decoderRunnable);
decoderThread.setPriority(Thread.MAX_PRIORITY);  // -20 to 19, MAX=10
// Better: Use Process.setThreadPriority()
android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

// CPU Affinity (requires root or privileged)
try {
    Os.sched_setaffinity(0, cpuMask);  // Big core mask
} catch (ErrnoException e) { ... }
```

## Scheduling Interaction with Thermal

### Thermal Throttling Impact
```
Normal: Big cores @ 2.8GHz, LITTLE @ 1.8GHz
    ↓
Thermal limit reached (skin temp > 45°C)
    ↓
Big cores capped @ 1.5GHz or offlined
    ↓
Decoder thread migrated to LITTLE core
    ↓
Decode time: 4ms → 12ms
    ↓
Frame deadline missed → jank/dropped frames
    ↓
BufferQueue backs up → decoder blocks on input
    ↓
Cascading latency increase
```

### Mitigation Strategies
1. **Monitor Thermal State**: `PowerManager.getCurrentThermalStatus()`
2. **Adaptive Quality**: Reduce resolution/bitrate/fps when throttling
3. **Core Affinity**: Pin decoder to big cores (if available)
4. **Frame Pacing**: Skip frames proactively vs. queue buildup

## Threading Checklist for OpenNOW Android

- [ ] Use MediaCodec async callbacks (not polling loop)
- [ ] Dedicated decoder thread with high priority + big core affinity
- [ ] WebRTC network thread at `THREAD_PRIORITY_URGENT_AUDIO`
- [ ] Input thread at high priority, minimal processing
- [ ] SurfaceView (not TextureView) to avoid JNI/GC overhead
- [ ] Monitor binder transaction count via Perfetto
- [ ] Implement thermal state listener for adaptive quality
- [ ] Use `surface.setFrameRate()` for VSYNC alignment hints
- [ ] Profile with Perfetto: CPU Scheduling, CPU Frequency tracks
- [ ] Test on big.LITTLE devices (Snapdragon 8 Gen 2/3, Tensor G3)

## References
- [Android Thread Priorities](https://developer.android.com/reference/android/os/Process)
- [Choreographer](https://developer.android.com/reference/android/view/Choreographer)
- [SurfaceFlinger Architecture](https://source.android.com/docs/core/graphics/arch-sf)
- [big.LITTLE Scheduling](https://lwn.net/Articles/706374/)
- [Perfetto CPU Scheduling](https://perfetto.dev/docs/data-sources/cpu-scheduling)
- [MediaCodec Callback API](https://developer.android.com/reference/android/media/MediaCodec.Callback)