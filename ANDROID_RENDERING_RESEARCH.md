# Android Rendering Path Research for OpenNOW

## Overview
Comparison of Android video rendering architectures for cloud gaming. Goal: identify the path with lowest theoretical latency for a cloud-gaming video surface.

## Rendering Path Comparison

### Path 1: MediaCodec → SurfaceView (RECOMMENDED)

```
MediaCodec.configure(format, surfaceView.getHolder().getSurface(), ...)
    ↓
SurfaceView Surface (dedicated layer)
    ↓ BufferQueue
SurfaceFlinger (separate composition layer)
    ↓ Hardware Composer (HWC) Overlay
Display
```

**Characteristics**:
- **Separate window layer** managed by SurfaceFlinger
- **Hardware overlay** support: can bypass GPU composition entirely
- **No View hierarchy integration** - cannot transform/animate
- **Independent rendering thread** - not tied to UI thread
- **Lowest latency** of all standard paths

**Latency Contributors**:
- BufferQueue: 1-2 frames queued
- SurfaceFlinger: 0-1 frame (if overlay, near-zero)
- Display scanout: VSYNC aligned

**Pros**:
- Best theoretical latency
- Hardware overlay potential (zero GPU composition)
- Decoupled from UI thread jank
- Supports HDR/secure content properly

**Cons**:
- Cannot animate/transform (fixed position/size)
- Z-ordering limitations (always above or below window)
- Surface lifecycle tied to visibility (destroyed when hidden)

### Path 2: MediaCodec → TextureView

```
MediaCodec.configure(format, textureView.getSurfaceTexture(), ...)
    ↓
TextureView SurfaceTexture (GPU texture)
    ↓ GPU copy/composition
View Hierarchy (RenderThread)
    ↓ GPU composition
SurfaceFlinger (single layer with UI)
    ↓ GPU composition
Display
```

**Characteristics**:
- **Integrated into View hierarchy** - can transform, animate, alpha blend
- **GPU texture backing** - rendered via RenderThread
- **Extra GPU copy** every frame
- **Tied to UI thread/RenderThread scheduling**

**Latency Contributors**:
- BufferQueue: 1-2 frames
- GPU texture upload: 1 frame
- RenderThread composition: 1-2 frames
- SurfaceFlinger: 1 frame
- **Total: 3-5 frames extra vs SurfaceView**

**Pros**:
- Full View hierarchy integration
- Animations, transforms, alpha
- Easier UI integration

**Cons**:
- **1-3 frames additional latency** (Chrome team measured)
- GC pressure from `AttachCurrentThread`/`DetachCurrentThread` on `onFrameAvailable`
- GPU memory bandwidth higher
- Not suitable for cloud gaming

**Evidence**: Chromium switched from TextureView to SurfaceView citing "1-3 extra frames of latency" (Google Groups graphics-dev, 2014). ExoPlayer issue #7376 documents GC pressure from JNI thread attachment.

### Path 3: MediaCodec → SurfaceTexture (Custom Rendering)

```
MediaCodec.configure(format, surfaceTexture, ...)
    ↓
SurfaceTexture (BufferQueue consumer)
    ↓ onFrameAvailable → updateTexImage()
OpenGL/Vulkan Custom Rendering
    ↓ GPU composition
SurfaceFlinger
    ↓ Display
```

**Characteristics**:
- **Maximum control** over frame timing
- **Custom frame pacing** possible
- **External OES texture** sampling required
- **Most complex** - easy to introduce bugs/extra frames

**Latency Contributors**:
- BufferQueue: 1-2 frames
- `updateTexImage()`: sync with producer
- Custom render: 1 frame (if not careful)
- SurfaceFlinger: 1 frame
- **Can match SurfaceView if done perfectly, but risky**

**Pros**:
- Frame-accurate presentation control
- Can implement custom VRR/frame pacing
- Post-processing shaders possible

**Cons**:
- High implementation complexity
- `samplerExternalOES` required (not `sampler2D`)
- Easy to accidentally add frames
- Must manage EGL/Vulkan context, fences manually

### Path 4: MediaCodec → SurfaceControl (API 29+)

```
MediaCodec.configure(format, surfaceControl.getSurface(), ...)
    ↓
SurfaceControl (direct layer)
    ↓ SurfaceFlinger (direct layer transaction)
    ↓ HWC Overlay
Display
```

**Characteristics**:
- **Direct SurfaceFlinger layer control** via `SurfaceControl.Transaction`
- **Lowest possible latency** - bypasses View system entirely
- **Programmatic Z-order, transform, alpha**
- **Requires `android.permission.INTERNAL_SYSTEM_WINDOW` or similar** (system privileges)

**Latency**: Theoretical minimum - direct to SurfaceFlinger

**Cons**: Not available to regular apps (requires platform signature or special permissions)

## Latency Comparison Summary

| Path | Theoretical Min Latency | Typical Extra Frames | Hardware Overlay | UI Integration | Complexity |
|------|------------------------|---------------------|------------------|----------------|------------|
| SurfaceView | Best | Baseline (0) | ✓ Yes | ✗ None | Low |
| TextureView | +3-5 frames | +3-5 | ✗ No | ✓ Full | Low |
| SurfaceTexture + Custom | ~SurfaceView | 0 to +2 | Possible | ✓ Custom | High |
| SurfaceControl | Best | 0 | ✓ Yes | ✓ Programmatic | High (privileged) |

## Frame Timing Analysis

### SurfaceView Frame Lifecycle
```
Frame N decoded by MediaCodec
    ↓ releaseOutputBuffer(index, timestamp)
BufferQueue: queueBuffer() + fence
    ↓ (async, typically <1ms)
SurfaceFlinger: acquireBuffer() + wait on fence
    ↓ (next VSYNC or immediately if overlay)
HWC: Composite via overlay plane
    ↓
Display: Scanout at VSYNC
```

**Key insight**: With hardware overlay, SurfaceFlinger barely touches the buffer - HWC reads directly from decoder-allocated gralloc buffer.

### VSYNC Alignment
- **60Hz**: 16.67ms frame window
- **90Hz**: 11.11ms
- **120Hz**: 8.33ms
- **VRR (Variable Refresh Rate)**: Frame presented at next available refresh

### Choreographer Integration
```java
// For custom frame pacing with SurfaceTexture
surfaceTexture.setOnFrameAvailableListener(surfaceTexture -> {
    // This runs on arbitrary thread - MUST post to Choreographer
    Choreographer.getInstance().postFrameCallback(frameTimeNanos -> {
        // Update texture, render, present
        surfaceTexture.updateTexImage();
        // ... render ...
        // Request next frame
        surfaceTexture.setOnFrameAvailableListener(this);
    });
});
```

## Recommended Architecture for OpenNOW Android

### Primary: SurfaceView
```xml
<!-- Layout -->
<FrameLayout>
    <SurfaceView
        android:id="@+id/gameSurface"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:focusable="true"
        android:focusableInTouchMode="true" />
    <!-- UI overlays on top in separate Views -->
</FrameLayout>
```

```java
// Setup
SurfaceView surfaceView = findViewById(R.id.gameSurface);
SurfaceHolder holder = surfaceView.getHolder();
holder.addCallback(new SurfaceHolder.Callback() {
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Surface surface = holder.getSurface();
        // Configure MediaCodec with this surface
        mediaCodec.configure(format, surface, null, 0);
    }
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        // Handle resolution changes
    }
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Release codec
    }
});
```

### UI Overlay Strategy
- **Game video**: SurfaceView (bottom layer)
- **UI overlays**: Regular Views (top layer) - minimal, translucent
- **Input capture**: Touch/gamepad on SurfaceView or full-window overlay

### Surface Lifecycle Management
```java
// Keep surface alive during session transitions
surfaceView.setZOrderMediaOverlay(true);  // or setZOrderOnTop(true)
// Prevents surface destruction on visibility changes
```

### Frame Rate Hints (API 30+)
```java
// Tell system we're a fixed-framerate video source
surfaceView.getHolder().setFixedSize(videoWidth, videoHeight);
// Or programmatically:
surface.setFrameRate(60.0f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
```

## What NOT to Do

| Anti-Pattern | Why |
|--------------|-----|
| TextureView for game video | 3-5 frame latency penalty |
| SurfaceView + heavy View animations above | Forces GPU composition, loses overlay |
| Frequent surface recreate/destroy | Decoder reinitialization = latency spikes |
| Blocking UI thread on decode callbacks | ANR risk, frame drops |
| `sampler2D` for SurfaceTexture | Black frames (must use `samplerExternalOES`) |

## Testing Latency

### End-to-End Measurement
```
1. Server: Encode frame with known PTS, send via WebRTC
2. Client: Record timestamps at:
   - Network receive (WebRTC onTrack)
   - MediaCodec input queue (dequeueInputBuffer)
   - MediaCodec output dequeue (dequeueOutputBuffer)
   - SurfaceTexture.OnFrameAvailable / SurfaceHolder.Callback
   - Choreographer frame callback
   - Display (high-speed camera or display latency tool)
3. Compare paths
```

### Perfetto Trace Markers
```java
// In decode loop
Trace.beginSection("MediaCodec.dequeueInputBuffer");
int index = codec.dequeueInputBuffer(TIMEOUT_US);
Trace.endSection();

Trace.beginSection("MediaCodec.queueInputBuffer");
codec.queueInputBuffer(index, ...);
Trace.endSection();

Trace.beginSection("MediaCodec.dequeueOutputBuffer");
int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
Trace.endSection();

Trace.beginSection("MediaCodec.releaseOutputBuffer");
codec.releaseOutputBuffer(outIndex, true);  // render=true
Trace.endSection();
```

## References
- [SurfaceView vs TextureView - Android Developers](https://developer.android.com/media/media3/ui/surface)
- [AOSP TextureView Documentation](https://source.android.com/docs/core/graphics/arch-tv)
- [Chromium Graphics-dev: TextureView latency](https://groups.google.com/a/chromium.org/g/graphics-dev/c/Z0yE-PWQXc4)
- [ExoPlayer TextureView GC Issue #7376](https://github.com/google/ExoPlayer/issues/7376)
- [Android Frame Rate API](https://developer.android.com/media/optimize/performance/frame-rate)
- [SurfaceControl API](https://developer.android.com/reference/android/view/SurfaceControl)