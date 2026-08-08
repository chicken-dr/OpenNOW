# Android Diagnostics Research for OpenNOW Cloud Gaming

## Overview
Tools and methodologies for measuring, profiling, and debugging OpenNOW Android cloud gaming performance. Measurement-driven optimization is essential.

## Diagnostic Tool Ecosystem

### 1. Perfetto (Primary System Profiler)
**Platform**: Android 9+ (default on 11+), web-based UI at `ui.perfetto.dev`

#### Key Data Sources for Cloud Gaming
| Data Source | Category | Cloud Gaming Relevance |
|-------------|----------|------------------------|
| `linux.ftrace` | Kernel scheduling | CPU migration, frequency, wakeups |
| `android.atrace` | Framework | MediaCodec, SurfaceFlinger, Choreographer |
| `android.gpu` | GPU | Composition, render time, memory |
| `android.frame_timeline` | Frame lifecycle | Per-frame deadline tracking (API 33+) |
| `android.cpu` | CPU counters | Utilization per core/cluster |
| `android.memory` | Memory | RSS, PSS, GPU memory |
| `android.power` | Power/thermal | Rail voltage/current, thermal zones |
| `android.battery` | Battery | Per-UID consumption |
| `android.network` | Network | Socket stats, packet counts |

#### Cloud Gaming Trace Config
```protobuf
# perfetto_config.pbtx
buffers: { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources: {
  config { name: "linux.ftrace" }
  ftrace_config {
    ftrace_events: "sched_switch"
    ftrace_events: "sched_waking"
    ftrace_events: "cpu_frequency"
    ftrace_events: "cpu_idle"
    atrace_categories: "gfx"
    atrace_categories: "view"
    atrace_categories: "dalvik"
    atrace_categories: "camera"
    atrace_categories: "video"
    atrace_categories: "audio"
    atrace_categories: "binder_driver"
    atrace_categories: "hal"
  }
}
data_sources: { config { name: "android.frame_timeline" } }
data_sources: { config { name: "android.gpu" } }
data_sources: { config { name: "android.cpu" } }
data_sources: { config { name: "android.power" } }
duration_ms: 30000
```

#### Key Perfetto Queries for Cloud Gaming
```sql
-- Frame deadline misses
SELECT ts, dur, name FROM slice 
WHERE name GLOB '*Frame*' AND dur > 16666666  -- >16.67ms at 60fps

-- CPU scheduling latency for decoder thread
SELECT cpu, ts, dur, utid FROM sched 
WHERE utid = (SELECT utid FROM thread WHERE name LIKE '%Decoder%')
ORDER BY ts;

-- SurfaceFlinger composition type (HWC vs GPU)
SELECT ts, name, arg_value FROM slice 
WHERE name = 'HWC' OR name = 'GPU Composition';

-- BufferQueue queue/dequeue latency
SELECT ts, dur FROM slice 
WHERE name GLOB '*queueBuffer*' OR name GLOB '*dequeueBuffer*';

-- Thermal throttling events
SELECT ts, name FROM counter 
WHERE name GLOB '*thermal*' OR name GLOB '*throttl*';
```

### 2. ATrace (Framework Tracing)
**API**: `android.os.Trace` (Java), `ATrace_beginSection()` (NDK)

#### OpenNOW Custom Trace Points
```java
// Network receive
Trace.beginSection("WebRTC.recvfrom");
packet = socket.receive();
Trace.endSection();

// RTP processing
Trace.beginSection("WebRTC.rtpParse");
frame = rtpDepacketizer.parse(packet);
Trace.endSection();

// MediaCodec input
Trace.beginSection("MediaCodec.dequeueInputBuffer");
index = codec.dequeueInputBuffer(TIMEOUT_US);
Trace.endSection();

Trace.beginSection("MediaCodec.queueInputBuffer");
codec.queueInputBuffer(index, ...);
Trace.endSection();

// MediaCodec output
Trace.beginSection("MediaCodec.dequeueOutputBuffer");
outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
Trace.endSection();

Trace.beginSection("MediaCodec.releaseOutputBuffer");
codec.releaseOutputBuffer(outIndex, true);
Trace.endSection();

// Input processing
Trace.beginSection("Input.processTouch");
inputProcessor.processTouch(event);
Trace.endSection();

Trace.beginSection("Input.sendPacket");
networkSender.send(packet);
Trace.endSection();
```

#### Command Line Recording
```bash
# Start trace (30s, 64MB buffer)
adb shell atrace -z -b 65536 gfx view dalvik video audio binder_driver hal freq idle sched 30

# Or with Perfetto (recommended)
adb shell perfetto -c /data/misc/perfetto-config.pbtx -o /data/misc/trace.pb
```

### 3. dumpsys Diagnostics

#### SurfaceFlinger
```bash
# Latency data (last 128 frames)
adb shell dumpsys SurfaceFlinger --latency <layer-name>

# Layer list with composition type
adb shell dumpsys SurfaceFlinger --list

# Full state dump
adb shell dumpsys SurfaceFlinger

# Key output fields:
# - Composition type: HWC (overlay) vs GPU
# - Queue depth
# - Frame timestamps (latch, acquire, present)
# - VSYNC timing
```

#### Graphics Info (GFX)
```bash
# Frame timing stats (jank, 90th/95th/99th percentile)
adb shell dumpsys gfxinfo <package>

# Reset and collect fresh
adb shell dumpsys gfxinfo <package> reset
adb shell dumpsys gfxinfo <package>

# Key metrics:
# - Total frames rendered
# - Janky frames (>16.67ms)
# - 50th/90th/95th/99th percentile frame times
# - GPU completion time
```

#### Media Codec
```bash
# Codec stats (if MediaMetrics enabled)
adb shell dumpsys media.codec

# Codec list with capabilities
adb shell dumpsys media.codec --list

# Key info:
# - Active codec instances
# - Input/output buffer counts
# - Codec name (vendor vs software)
# - Error counts
```

#### Input
```bash
# Input dispatcher state
adb shell dumpsys inputflinger

# Input latency
adb shell dumpsys inputflinger --latency

# Key info:
# - Event dispatch latency
# - ANR history
# - Focused window
# - Touch/key event counts
```

#### Power/Thermal
```bash
# Battery stats (per-UID)
adb shell dumpsys batterystats <package>

# Thermal status
adb shell dumpsys thermal

# Power manager
adb shell dumpsys power

# CPU frequency (check throttling)
adb shell cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq
adb shell cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq
```

### 4. FrameTimeline (API 33+)
**Programmatic Access**: `FrameTimeline` class

```java
// Per-frame detailed timing
FrameTimeline frameTimeline = FrameTimeline.getInstance();
frameTimeline.addObserver(new FrameTimeline.FrameTimelineObserver() {
    @Override
    public void onFrameTimelineUpdated(
            long frameId, 
            @NonNull FrameTimeline.PerFrameData data) {
        // data.getFrameInfo().getVsyncTimestamp()
        // data.getFrameInfo().getInputTimestamp()
        // data.getFrameInfo().getAnimationTimestamp()
        // data.getFrameInfo().getPerformTraversalsTimestamp()
        // data.getFrameInfo().getDrawTimestamp()
        // data.getFrameInfo().getSyncStartTimestamp()
        // data.getFrameInfo().getSyncQueuedTimestamp()
        // data.getFrameInfo().getPresentedTimestamp()
        
        // Calculate stage latencies
        long inputToPresent = data.getFrameInfo().getPresentedTimestamp() 
                            - data.getFrameInfo().getInputTimestamp();
    }
});
```

### 5. GPU Profiling (Vendor Tools)

#### Qualcomm Adreno
- **Snapdragon Profiler**: Windows/Linux GUI, detailed GPU counters
- **Metrics**: ALU utilization, texture fetch, memory bandwidth, vertex/texel throughput
- **Chrome**: `chrome://gpu` for WebGL/Vulkan info

#### ARM Mali
- **Streamline**: Eclipse-based profiler
- **Metrics**: Shader cycles, texture bandwidth, job manager, tiler

#### Google Tensor
- **Android GPU Inspector (AGI)**: Best for Vulkan/OpenGL ES
- **Supports**: Frame capture, shader analysis, GPU counters

### 6. CPU Scheduling Analysis

#### Perfetto CPU Scheduling Track
- **Shows**: Which thread on which CPU at each timestamp
- **Key Patterns**:
  - Decoder thread migration (big ↔ LITTLE)
  - UI thread preemption
  - Binder thread contention
  - Wakeup latency (sched_waking → sched_switch)

#### Systrace (Legacy)
```bash
# Python script (deprecated, use Perfetto)
python systrace.py -o trace.html -t 30 gfx view sched freq idle
```

### 7. Thermal APIs

#### PowerManager Thermal Status
```java
PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
int status = pm.getCurrentThermalStatus();
// THERMAL_STATUS_NONE (0)
// THERMAL_STATUS_LIGHT (1)
// THERMAL_STATUS_MODERATE (2)  
// THERMAL_STATUS_SEVERE (3)
// THERMAL_STATUS_CRITICAL (4)

pm.addThermalStatusListener(executor, status -> {
    // Adaptive quality logic
});
```

#### Thermal Zone Temperatures
```bash
# All thermal zones
adb shell cat /sys/class/thermal/thermal_zone*/temp
# Values in millidegrees Celsius

# Key zones:
# - cpu0-7 (per core)
# - gpu
# - vpu/video
# - skin (user-facing)
# - battery
```

### 8. Network Diagnostics

```bash
# Network stats per UID
adb shell dumpsys netstats detail -u <uid>

# Socket info
adb shell cat /proc/net/udp
adb shell cat /proc/<pid>/net/udp

# TCP/UDP buffer sizes
adb shell cat /proc/<pid>/net/udp | awk '{print $1, $2, $3, $4, $5, $6, $7, $8, $9, $10}'

# Packet capture (requires root)
adb shell tcpdump -i any -s 0 -w capture.pcap port 5000
```

### 9. Memory Analysis

```bash
# Process memory
adb shell dumpsys meminfo <package>

# Java heap (requires debuggable)
adb shell am dumpheap <package> /data/local/tmp/heap.hprof

# Native heap (malloc_info)
adb shell cat /proc/<pid>/smaps | head -100

# Gralloc buffers
adb shell dumpsys SurfaceFlinger --list | grep -A 5 "BufferQueue"
```

## OpenNOW-Specific Diagnostic Integration

### 1. In-App Telemetry
```kotlin
class TelemetryCollector {
    private val metrics = mutableMapOf<String, Long>()
    
    fun recordLatency(stage: String, latencyUs: Long) {
        // Rolling percentile calculation
        // Upload periodically
    }
    
    fun recordFrameMetrics(
        frameId: Long,
        networkRecvUs: Long,
        decodeStartUs: Long,
        decodeEndUs: Long,
        presentUs: Long
    ) {
        // End-to-end breakdown
    }
    
    fun recordThermal(status: Int) { ... }
    fun recordBattery(level: Int, temp: Float) { ... }
    fun recordNetwork(rttMs: Int, lossPct: Float, bwMbps: Int) { ... }
}
```

### 2. Perfetto Custom Data Source
```protobuf
# OpenNOW trace events
message OpenNowTraceEvent {
  string event_name = 1;
  int64 timestamp_us = 2;
  map<string, string> args = 3;
}
```

### 3. Automated CI Profiling
```yaml
# .github/workflows/android-profile.yml
- name: Run perfetto trace
  run: |
    adb shell perfetto -c config.pbtx -o /data/local/tmp/trace.pb
    adb pull /data/local/tmp/trace.pb trace.pb
    # Upload to Perfetto UI or analyze with SQL
```

## Diagnostic Checklist for OpenNOW Android

### Session Start
- [ ] Start Perfetto trace (30-60s rolling)
- [ ] Record device info (model, Android version, SoC)
- [ ] Record codec capabilities (MediaCodecList)
- [ ] Record thermal status baseline
- [ ] Record battery level/temp

### During Session (Continuous)
- [ ] Frame latency percentiles (50/90/95/99)
- [ ] Decoder throughput (fps, kbps)
- [ ] BufferQueue depth
- [ ] SurfaceFlinger composition type (HWC vs GPU)
- [ ] CPU frequency per cluster
- [ ] Thermal zone temps
- [ ] Network RTT, loss, bandwidth
- [ ] Input latency (capture→send)

### Session End
- [ ] Stop Perfetto trace, save
- [ ] dumpsys gfxinfo (final frame stats)
- [ ] dumpsys SurfaceFlinger --latency
- [ ] dumpsys media.codec
- [ ] dumpsys batterystats
- [ ] dumpsys thermal
- [ ] Collect telemetry upload

### Analysis Workflow
1. **Open trace in Perfetto UI** (`ui.perfetto.dev`)
2. **Check FrameTimeline track** for deadline misses
3. **CPU Scheduling track** for decoder thread behavior
4. **GPU track** for composition type
5. **Power track** for thermal/throttling correlation
6. **Run SQL queries** for quantitative metrics
7. **Correlate** frame drops with thermal/network/CPU events

## References
- [Perfetto Documentation](https://perfetto.dev/docs/)
- [Android Studio Profiling](https://developer.android.com/studio/profile)
- [FrameTimeline API](https://developer.android.com/reference/android/view/FrameTimeline)
- [SurfaceFlinger Latency](https://source.android.com/docs/core/graphics/arch-sf)
- [Android GPU Inspector](https://developer.android.com/topic/performance/gpu/agi)
- [Snapdragon Profiler](https://developer.qualcomm.com/software/snapdragon-profiler)
- [ARM Streamline](https://developer.arm.com/tools-and-software/graphics-and-gaming/streamline)