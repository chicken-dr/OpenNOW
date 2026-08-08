# Android Benchmark Plan for OpenNOW

**Status**: Research Phase - Measurement-driven optimization methodology  
**Target**: Android-first cloud gaming client for GeForce NOW  
**Baseline**: Current OpenNOW is desktop-only Electron app (no Android code exists)

---

## Benchmark Philosophy

**Principle**: Every optimization must be validated with measurements. No optimization ships without before/after comparison using the methodology below.

**Measurement Tools**: Perfetto (primary), `dumpsys` (secondary), high-speed camera (latency), WebRTC stats (network)

---

## Metric Categories

### 1. End-to-End Latency Metrics

| Metric | Definition | Target | Measurement Method | P50 | P95 | P99 |
|--------|------------|--------|-------------------|-----|-----|-----|
| **End-to-End Latency** | Server frame PTS → display photon | < 80ms | High-speed camera (1000fps) + LED on server | 80ms | 100ms | 120ms |
| **Network Latency** | Server send → client recv (UDP) | < 30ms | WebRTC stats: `currentRoundTripTime` / 2 | 20ms | 30ms | 50ms |
| **Jitter Buffer Delay** | Time frame spends in NetEq | < 30ms | WebRTC stats: `jitterBufferDelayMs` | 20ms | 30ms | 50ms |
| **Decode Latency** | MediaCodec input queue → output dequeue | < 8ms | MediaCodec callback timestamps | 4ms | 8ms | 12ms |
| **Frame Delivery Latency** | Decode complete → SurfaceFlinger present | < 16ms | FrameTimeline (API 33+) / `dumpsys SurfaceFlinger --latency` | 8ms | 16ms | 24ms |
| **Input-to-Photon Latency** | Physical input → display photon | < 50ms | High-speed camera: LED on controller + screen | 35ms | 50ms | 70ms |

---

### 2. Frame Quality Metrics

| Metric | Definition | Target | Measurement Method | P50 | P95 | P99 |
|--------|------------|--------|-------------------|-----|-----|-----|
| **Frame Drop Rate** | Frames not presented / total | < 0.5% | `dumpsys gfxinfo` / FrameTimeline | 0.1% | 0.5% | 1% |
| **Frame Time Variance** | Std dev of frame intervals | < 2ms | FrameTimeline / `dumpsys gfxinfo` percentiles | 1ms | 2ms | 3ms |
| **Jank Rate** | Frames > 16.67ms (60fps) | < 1% | `dumpsys gfxinfo` jank stats | 0.5% | 1% | 2% |
| **Stutter Events** | Consecutive dropped frames > 2 | 0/hour | FrameTimeline analysis | 0 | 0 | 1 |

---

### 3. Decoder Performance Metrics

| Metric | Definition | Target | Measurement Method | P50 | P95 | P99 |
|--------|------------|--------|-------------------|-----|-----|-----|
| **Decode FPS** | Frames decoded per second | = target FPS ± 1 | WebRTC stats: `framesDecoded` / sec | 60 | 60 | 60 |
| **Decode Time per Frame** | `totalDecodeTime / framesDecoded` | < 8ms | WebRTC stats: `totalDecodeTime` | 4ms | 8ms | 12ms |
| **Decoder Pressure Events** | DecoderPressureController activations | < 1/min | Custom telemetry | 0 | 1 | 3 |
| **Keyframe Requests** | PLI/keyframe requests sent | < 5/min | Custom telemetry / WebRTC stats | 0 | 2 | 5 |
| **Hardware Decoder Usage** | % frames decoded by HW | 100% | `dumpsys media.codec` / MediaCodecList | 100% | 100% | 100% |

---

### 4. Network Metrics

| Metric | Definition | Target | Measurement Method | P50 | P95 | P99 |
|--------|------------|--------|-------------------|-----|-----|-----|
| **RTT** | Round-trip time | < 30ms | WebRTC: `currentRoundTripTime` | 15ms | 30ms | 50ms |
| **Packet Loss** | RTP packets lost / total | < 0.5% | WebRTC: `packetsLost` / `packetsReceived` | 0.1% | 0.5% | 1% |
| **Bandwidth Estimation Accuracy** | Estimated / actual | ±10% | GCC vs actual throughput | ±5% | ±10% | ±15% |
| **NACK Rate** | NACK packets sent / total | < 5% | WebRTC stats: `nackCount` | 1% | 5% | 10% |
| **RTX Rate** | RTX packets sent / total | < 10% | WebRTC stats: `rtxPacketsSent` | 2% | 10% | 20% |
| **Throughput** | Sustained bitrate | ≥ target bitrate | WebRTC stats: `bytesReceived` / sec | 100% | 95% | 90% |

---

### 5. Input Latency Metrics

| Metric | Definition | Target | Measurement Method | P50 | P95 | P99 |
|--------|------------|--------|-------------------|-----|-----|-----|
| **Touch-to-Photon** | Touch down → display update | < 40ms | High-speed camera: touch LED + screen | 25ms | 40ms | 55ms |
| **Gamepad-to-Photon** | Button press → display update | < 35ms (BT 5.0) / < 20ms (USB) | High-speed camera: button LED + screen | 20ms / 12ms | 35ms / 20ms | 50ms / 30ms |
| **Input Dispatch Latency** | Kernel event → app callback | < 5ms | Perfetto: `input.dispatch` | 1ms | 5ms | 10ms |
| **Input Encode + Send** | App callback → UDP sent | < 1ms | Perfetto: custom trace `Input.sendPacket` | 0.2ms | 1ms | 2ms |

---

### 6. System Resource Metrics

| Metric | Definition | Target | Measurement Method | P50 | P95 | P99 |
|--------|------------|--------|-------------------|-----|-----|-----|
| **CPU Utilization (App)** | App process CPU % | < 30% | Perfetto: `cpu` track / `top` | 15% | 30% | 40% |
| **CPU Utilization (System)** | Total CPU % | < 60% | Perfetto: `cpu` track | 40% | 60% | 75% |
| **Big Core Usage** | % time on big cores | > 80% (decoder thread) | Perfetto: CPU Scheduling track | 90% | 80% | 70% |
| **GPU Utilization** | GPU % (composition) | < 10% (overlay) / < 30% (GPU comp) | Perfetto: `android.gpu` | 5% | 10% | 20% |
| **Memory (RSS)** | Resident set size | < 500MB | `dumpsys meminfo` / Perfetto `memory` | 200MB | 500MB | 800MB |
| **Memory (GPU)** | GPU memory | < 200MB | `dumpsys gfxinfo` / vendor tools | 50MB | 200MB | 300MB |
| **Allocation Rate** | KB/s allocated | < 1000 KB/s | Perfetto: `dalvik` / `malloc` | 100 KB/s | 1000 KB/s | 5000 KB/s |
| **GC Count** | GC events per minute | < 5/min | Perfetto: `dalvik` | 0 | 5 | 20 |

---

### 7. Thermal & Power Metrics

| Metric | Definition | Target | Measurement Method | P50 | P95 | P99 |
|--------|------------|--------|-------------------|-----|-----|-----|
| **Skin Temperature** | External case temp | < 45°C | `dumpsys thermal` / IR thermometer | 40°C | 45°C | 50°C |
| **Thermal Throttling Events** | CPU/GPU freq reduction | 0/hour (sustained) | Perfetto: `cpu_freq` + `thermal` | 0 | 0 | 2 |
| **Session Duration Before Throttle** | Time until first throttle | > 30min | Thermal log + session timer | 45min | 30min | 15min |
| **Power Draw** | Total system power | < 6W (flagship) | `dumpsys batterystats` / external meter | 4W | 6W | 8W |
| **Decoder Power** | VPU power estimate | < 3W | `dumpsys batterystats` (per-UID) | 1.5W | 3W | 4W |
| **Battery Life** | Gaming session duration | > 1.5h (5000mAh) | `dumpsys batterystats` + stopwatch | 2h | 1.5h | 1h |

---

### 8. Surface & Composition Metrics

| Metric | Definition | Target | Measurement Method | P50 | P95 | P99 |
|--------|------------|--------|-------------------|-----|-----|-----|
| **Hardware Overlay Rate** | % frames via HWC | 100% | `dumpsys SurfaceFlinger --list` | 100% | 100% | 100% |
| **BufferQueue Depth** | Frames queued to SurfaceFlinger | ≤ 2 | `dumpsys SurfaceFlinger` / Perfetto | 1 | 2 | 3 |
| **Acquire Fence Wait** | Time waiting for decode fence | < 1ms | Perfetto: `fence` wait | 0.2ms | 1ms | 2ms |
| **VSYNC Alignment** | Frames presented on target VSYNC | > 95% | FrameTimeline: `presentedTimestamp` | 98% | 95% | 90% |

---

## Benchmark Execution Protocol

### Test Environment Setup

```bash
# Device preparation
adb root                    # If available (for perfetto, cpu affinity)
adb shell settings put global stay_on_while_plugged_in 3
adb shell settings put system screen_off_timeout 2147483647
adb shell am compat disable PACKAGE_NAME CHANGE_WIFI_STATE  # Prevent Wi-Fi sleep

# Disable battery optimization for app
adb shell dumpsys deviceidle whitelist +PACKAGE_NAME

# Set fixed CPU governors (if rooted)
for cpu in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
    echo performance > $cpu
done

# Fixed GPU frequency (vendor-specific, if available)
# e.g., Adreno: echo 1 > /sys/class/kgsl/kgsl-3d0/devfreq/governor
```

### Session Test Matrix

| Test Case | Duration | Network | Resolution | FPS | Codec | Thermal | Purpose |
|-----------|----------|---------|------------|-----|-------|---------|---------|
| **Baseline** | 10min | Wi-Fi 6 | 1080p | 60 | H.264 | Cool | Establish baseline |
| **Long Session** | 60min | Wi-Fi 6 | 1080p | 60 | H.264 | Warm | Thermal stability |
| **High Load** | 30min | Wi-Fi 6 | 1080p | 60 | HEVC | Warm | HEVC decode perf |
| **AV1 Test** | 20min | Wi-Fi 6E | 1080p | 60 | AV1 | Cool | AV1 HW support |
| **4K Test** | 15min | Wi-Fi 7 | 4K | 60 | HEVC | Cool | 4K capability |
| **120fps Test** | 10min | Wi-Fi 7 | 1080p | 120 | H.264 | Cool | High FPS |
| **Cellular Test** | 15min | 5G mmWave | 1080p | 60 | H.264 | Cool | Cellular perf |
| **Lossy Network** | 20min | Wi-Fi + 2% loss | 1080p | 60 | H.264 | Cool | Loss recovery |
| **Input Latency** | 5min | Wi-Fi 6 | 1080p | 60 | H.264 | Cool | Input measurement |
| **Background/Foreground** | 10min | Wi-Fi 6 | 1080p | 60 | H.264 | Cool | Lifecycle |

### Automated Benchmark Script

```python
# benchmark_runner.py
import subprocess
import json
import time
from datetime import datetime

def run_benchmark(test_config):
    """Execute a single benchmark test and collect metrics."""
    results = {
        "test_name": test_config["name"],
        "timestamp": datetime.now().isoformat(),
        "device": get_device_info(),
        "config": test_config,
        "metrics": {}
    }
    
    # Start Perfetto trace
    trace_file = f"/data/local/tmp/trace_{test_config['name']}.pb"
    perfetto_proc = subprocess.Popen([
        "adb", "shell", "perfetto", 
        "-c", "/data/misc/perfetto_config.pbtx",
        "-o", trace_file,
        "-t", str(test_config["duration_sec"])
    ])
    
    # Start app session
    start_session(test_config)
    
    # Wait for session to stabilize
    time.sleep(10)
    
    # Collect periodic metrics during session
    for i in range(test_config["duration_sec"] // 10):
        collect_periodic_metrics(results["metrics"])
        time.sleep(10)
    
    # End session
    end_session()
    
    # Wait for trace
    perfetto_proc.wait()
    
    # Pull trace
    subprocess.run(["adb", "pull", trace_file, f"./traces/{test_config['name']}.pb"])
    
    # Collect dumpsys data
    results["metrics"]["dumpsys"] = collect_dumpsys()
    
    # Analyze trace
    results["metrics"]["perfetto"] = analyze_perfetto_trace(trace_file)
    
    return results

def collect_dumpsys():
    return {
        "gfxinfo": adb("shell dumpsys gfxinfo PACKAGE_NAME"),
        "surfaceflinger": adb("shell dumpsys SurfaceFlinger --list"),
        "surfaceflinger_latency": adb("shell dumpsys SurfaceFlinger --latency"),
        "media_codec": adb("shell dumpsys media.codec"),
        "thermal": adb("shell dumpsys thermal"),
        "batterystats": adb("shell dumpsys batterystats PACKAGE_NAME"),
        "wifi": adb("shell dumpsys wifi"),
        "netstats": adb("shell dumpsys netstats detail -u UID"),
    }

def analyze_perfetto_trace(trace_file):
    # Run SQL queries against trace
    queries = {
        "frame_latency": "SELECT quantile(dur, 0.5), quantile(dur, 0.95), quantile(dur, 0.99) FROM slice WHERE name GLOB '*Frame*'",
        "decode_latency": "SELECT quantile(dur, 0.5), quantile(dur, 0.95), quantile(dur, 0.99) FROM slice WHERE name = 'MediaCodec.dequeueOutputBuffer'",
        "cpu_scheduling": "SELECT cpu, count() FROM sched WHERE utid IN (SELECT utid FROM thread WHERE name LIKE '%Decoder%') GROUP BY cpu",
        "thermal_throttle": "SELECT ts, value FROM counter WHERE name GLOB '*throttl*'",
        "overlay_rate": "SELECT count() FILTER (WHERE arg_value = 'HWC') * 100.0 / count() FROM slice WHERE name = 'Composition'",
    }
    return {name: run_sql_query(trace_file, sql) for name, sql in queries.items()}
```

---

## Regression Detection

### CI Integration

```yaml
# .github/workflows/android-benchmark.yml
name: Android Benchmark
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  benchmark:
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup Android device
        run: |
          # Connect to device farm or local device
          adb devices
          
      - name: Build debug APK
        run: ./gradlew assembleDebug
      
      - name: Install APK
        run: adb install -r app/build/outputs/apk/debug/app-debug.apk
      
      - name: Run baseline benchmark
        run: python benchmark_runner.py --test baseline --duration 600
      
      - name: Analyze results
        run: python analyze_results.py --baseline baseline_results.json --current current_results.json
      
      - name: Check regression
        run: |
          python check_regression.py \
            --threshold latency_p50=10% \
            --threshold frame_drop_rate=0.5% \
            --threshold decode_latency_p95=8ms
      
      - name: Upload traces
        uses: actions/upload-artifact@v4
        with:
          name: perfetto-traces
          path: traces/
```

### Regression Thresholds

| Metric | Regression Threshold | Action |
|--------|---------------------|--------|
| End-to-end latency P50 | > 10% increase | Block merge |
| Frame drop rate | > 0.5% absolute | Block merge |
| Decode latency P95 | > 8ms absolute | Block merge |
| Hardware overlay rate | < 95% | Warning |
| Thermal throttle events | > 0 in 30min | Block merge |
| Input latency P50 | > 20% increase | Warning |

---

## Visualization & Reporting

### Perfetto Dashboard Queries

```sql
-- Latency waterfall per frame
SELECT 
  ts as frame_start,
  dur as frame_duration,
  (SELECT dur FROM slice WHERE name = 'MediaCodec.dequeueInputBuffer' AND ts >= s.ts AND ts < s.ts + s.dur LIMIT 1) as network_to_decoder,
  (SELECT dur FROM slice WHERE name = 'MediaCodec.dequeueOutputBuffer' AND ts >= s.ts AND ts < s.ts + s.dur LIMIT 1) as decode_time,
  (SELECT dur FROM slice WHERE name = 'MediaCodec.releaseOutputBuffer' AND ts >= s.ts AND ts < s.ts + s.dur LIMIT 1) as present_time
FROM slice s
WHERE s.name = 'Frame' AND s.dur > 0
ORDER BY ts;

-- Thermal vs performance correlation
SELECT 
  c.ts,
  c.value as cpu_freq_mhz,
  t.value as thermal_temp_c,
  f.value as frame_time_ms
FROM counter c
JOIN counter t ON c.ts = t.ts
JOIN counter f ON c.ts = f.ts
WHERE c.name = 'cpu0_freq' AND t.name = 'thermal_zone0' AND f.name = 'frame_time'
ORDER BY c.ts;
```

### Report Template

```markdown
# Benchmark Report: {{test_name}}

**Date**: {{timestamp}}
**Device**: {{device_model}} ({{soc}})
**Android**: {{android_version}}
**Build**: {{git_sha}}

## Configuration
- Resolution: {{resolution}}
- FPS: {{fps}}
- Codec: {{codec}}
- Network: {{network}}
- Duration: {{duration}}min

## Key Metrics (P50 / P95 / P99)

| Metric | P50 | P95 | P99 | Target | Status |
|--------|-----|-----|-----|--------|--------|
| End-to-End Latency | {{e2e_p50}}ms | {{e2e_p95}}ms | {{e2e_p99}}ms | <80ms | {{e2e_status}} |
| Decode Latency | {{dec_p50}}ms | {{dec_p95}}ms | {{dec_p99}}ms | <8ms | {{dec_status}} |
| Frame Drop Rate | {{fdr_p50}}% | {{fdr_p95}}% | {{fdr_p99}}% | <0.5% | {{fdr_status}} |
| Jitter Buffer | {{jb_p50}}ms | {{jb_p95}}ms | {{jb_p99}}ms | <30ms | {{jb_status}} |
| Input Latency (USB) | {{in_p50}}ms | {{in_p95}}ms | {{in_p99}}ms | <20ms | {{in_status}} |
| Hardware Overlay | {{hw_p50}}% | - | - | 100% | {{hw_status}} |

## Thermal
- Max skin temp: {{max_temp}}°C
- Throttle events: {{throttle_count}}
- Session duration before throttle: {{throttle_time}}min

## Regression vs Baseline
- Latency P50: {{latency_delta}}% (threshold: 10%)
- Frame drops: {{fdr_delta}}pp (threshold: 0.5pp)
- Decode P95: {{dec_delta}}ms (threshold: 8ms)

## Artifacts
- Perfetto trace: `traces/{{test_name}}.pb`
- Dumpsys logs: `dumpsys/{{test_name}}/`
- High-speed camera video: `camera/{{test_name}}.mp4` (if applicable)
```

---

## Device-Specific Benchmark Configs

### Qualcomm Snapdragon 8 Gen 3
```python
TEST_CONFIGS = {
    "target_codecs": ["AV1", "HEVC", "H264"],
    "max_resolution": "4K",
    "max_fps": 120,
    "thermal_expectation": "good_sustained",
    "expected_decode_latency_p95_ms": 5,
    "expected_overlay_rate": 1.0,
}
```

### MediaTek Dimensity 9300
```python
TEST_CONFIGS = {
    "target_codecs": ["AV1", "HEVC", "H264"],
    "max_resolution": "4K",
    "max_fps": 60,
    "thermal_expectation": "moderate",
    "android_15_hevc_workaround": True,
    "expected_decode_latency_p95_ms": 8,
}
```

### Samsung Exynos 2400
```python
TEST_CONFIGS = {
    "target_codecs": ["AV1", "HEVC", "H264"],
    "max_resolution": "4K",
    "max_fps": 120,
    "thermal_expectation": "good",
    "av1_validation_required": True,
    "expected_decode_latency_p95_ms": 8,
}
```

### Google Tensor G3/G4
```python
TEST_CONFIGS = {
    "target_codecs": ["AV1", "HEVC", "H264"],
    "max_resolution": "4K",
    "max_fps": 60,
    "thermal_expectation": "good",
    "preferred_codec": "AV1",
    "expected_decode_latency_p95_ms": 6,
}
```

---

## Success Criteria Summary

| Phase | Must Pass | Should Pass | Nice to Pass |
|-------|-----------|-------------|--------------|
| **Phase 1 (Foundation)** | App builds, runs, shows video, Perfetto traces work, HWC overlay 100% | MediaCodec HW decoder, decode latency < 15ms | Frame drop < 1% |
| **Phase 2 (Core Pipeline)** | E2E latency P50 < 100ms, decode P95 < 8ms, frame drop < 0.5%, jitter buffer < 30ms | NetEq 20ms min, NACK+RTX working, MediaTek workaround | Hardware overlay 100%, input < 25ms |
| **Phase 3 (Threading/Input/Thermal)** | Input < 20ms (USB), 30min no throttle, big core affinity working, thermal adaptation | Frame pacing, FPS reduction on thermal, device-specific codecs | DVFS monitoring, CPU freq correlation |
| **Phase 4 (Production)** | All Phase 3 on 5+ devices, CI regression detection, crash reporting, telemetry | AHardwareBuffer+Vulkan (optional), Wi-Fi 7/5G mmWave | FrameTimeline (API 33+) integration |

---

## References

- [Perfetto Documentation](https://perfetto.dev/docs/)
- [Android FrameTimeline](https://developer.android.com/reference/android/view/FrameTimeline)
- [SurfaceFlinger Latency](https://source.android.com/docs/core/graphics/arch-sf)
- [WebRTC Stats API](https://webrtc.org/getting-started/android)
- [Android GPU Inspector](https://developer.android.com/topic/performance/gpu/agi)
- [High-Speed Camera Latency Testing](https://www.youtube.com/watch?v=eiAJKMkXYC0)