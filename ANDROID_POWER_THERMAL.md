# Android Power/Thermal Research for OpenNOW Cloud Gaming

## Overview
Analysis of sustained cloud gaming impact on Android device power consumption, thermal behavior, and throttling. Critical for maintaining stable low-latency playback over extended sessions.

## Power Consumption Breakdown

### Cloud Gaming Client Energy Distribution (Research Data)

| Component | Wi-Fi % | Cellular % | Notes |
|-----------|---------|------------|-------|
| **Video Decoder** | 73% | 65-70% | Dominant consumer (hardware VPU) |
| **Network (Modem/RF)** | 13% | 20-25% | Higher on cellular, especially mmWave |
| **Display** | 14% | 10-15% | Panel + display controller |
| **CPU (App/OS)** | 5-8% | 5-8% | Network stack, input, UI |
| **GPU (Composition)** | 2-5% | 2-5% | Only if no hardware overlay |
| **Audio** | <1% | <1% | Negligible |

**Source**: "End-to-end Characterization of Game Streaming" (NSDI/academic), measured Destiny 2 at 4K/20Mbps.

### Decoder Power by Codec/Resolution

| Configuration | Relative Power | Notes |
|--------------|----------------|-------|
| H.264 1080p@60 | 1.0x (baseline) | Most efficient widely supported |
| H.264 720p@60 | 0.6x | Lower resolution = less memory bandwidth |
| HEVC 1080p@60 | 1.1-1.2x | Similar to H.264, slightly more complex |
| HEVC 4K@60 | 1.8-2.2x | 4× pixels, more reference frames |
| VP9 1080p@60 | 1.2-1.4x | More complex entropy coding |
| AV1 1080p@60 | 1.3-1.6x (HW) | Newer, less optimized HW |
| AV1 1080p@60 | 3-4x (SW) | **Avoid software decode** |

### Frame Rate Impact
| FPS | Decoder Power | Display Power | Total Impact |
|-----|--------------|---------------|--------------|
| 30 | 0.6x | 0.5x (vs 60Hz) | Baseline |
| 60 | 1.0x | 1.0x | Standard |
| 120 | 1.8-2.0x | 1.8-2.0x | **2× power** - avoid unless necessary |

### Bitrate Impact
| Bitrate | Decoder Power | Network Power |
|---------|--------------|---------------|
| 10 Mbps | 0.8x | 0.7x |
| 25 Mbps | 1.0x | 1.0x |
| 50 Mbps | 1.3x | 1.4x |
| 100 Mbps | 1.6x | 1.8x |

**Key Insight**: Decoder power scales super-linearly with resolution/framerate due to memory bandwidth and VPU frequency scaling.

## Thermal Behavior

### Throttling Stages (Typical Android)

| Stage | Trigger | Action | Gaming Impact |
|-------|---------|--------|---------------|
| **Normal** | Skin < 40°C | Full performance | Optimal |
| **Warm** | Skin 40-45°C | Big core freq cap -10% | Minor frame time increase |
| **Hot** | Skin 45-50°C | Big cores capped 50%, some offlined | Decode latency 2-3×, dropped frames |
| **Critical** | Skin > 50°C | All cores throttled, display dimmed | Unplayable, session should pause |

### SoC-Specific Thermal Behavior

#### Qualcomm Snapdragon
- **Thermal Zones**: Multiple (CPU, GPU, VPU, Modem, Skin)
- **Mitigation**: 
  - VPU has independent thermal management
  - `thermald` daemon coordinates
  - Can throttle VPU separately from CPU
- **Snapdragon 8 Gen 3**: Better sustained perf via larger vapor chambers

#### MediaTek Dimensity
- **Thermal Zones**: Combined CPU/GPU/VPU often
- **Issue**: Android 15 HEVC regression may increase VPU utilization → more heat
- **Mitigation**: Less documented, varies by OEM thermal solution

#### Samsung Exynos
- **Thermal**: Integrated CPU/GPU thermal management
- **Exynos 2400**: Improved vapor chamber, better sustained

#### Google Tensor
- **Thermal**: Custom TPU/VPU thermal zones
- **Tensor G3**: Good sustained with Pixel 8 vapor chamber

## Wi-Fi vs Cellular Power

### Wi-Fi (6/6E/7)
- **Active TX/RX**: 800-1500mW
- **Power Save (PSM)**: 50-100mW (but adds latency!)
- **Gaming Mode**: Disable PSM, use `WIFI_MODE_FULL_HIGH_PERF`
- **Wi-Fi 6E/7 6GHz**: Higher throughput, similar power, less interference

### Cellular (5G)
| Band | Power | Latency | Gaming Suitability |
|------|-------|---------|-------------------|
| Sub-6 5G | 1.5-2.5W | 15-30ms | Good |
| mmWave 5G | 3-5W | 5-15ms | Best latency, **high power/heat** |
| 4G LTE | 1-2W | 30-50ms | Fallback only |

**Recommendation**: Prefer Wi-Fi 6/6E/7 for sustained gaming. Cellular only for mobility.

## Display Power

| Technology | 1080p@60 | 1080p@120 | 4K@60 | Notes |
|------------|----------|-----------|-------|-------|
| OLED | 400-600mW | 700-900mW | 800-1200mW | Per-pixel, black=off |
| LCD | 600-900mW | 1000-1400mW | 1200-1800mW | Backlight always on |
| LTPO OLED | Variable | Variable | Variable | Dynamic refresh saves power |

**VRR (Variable Refresh Rate)**: Can save 20-30% display power at low fps content.

## Battery Life Estimation

### Typical Gaming Session Power Draw
| Device Class | Total Power | Battery (mAh) | Estimated Hours |
|--------------|-------------|---------------|-----------------|
| Flagship (5000mAh) | 4-6W | 5000 | 1.5-2.5h |
| Mid-range (4500mAh) | 3-5W | 4500 | 1.5-2h |
| Gaming Phone (6000mAh) | 5-8W | 6000 | 1.5-2h |

**Note**: Gaming phones have active cooling → sustain higher power longer.

## Thermal Mitigation Strategies

### 1. Adaptive Quality (Client-Side)
```java
// Monitor thermal state
PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
pm.addThermalStatusListener(executor, status -> {
    switch (status) {
        case PowerManager.THERMAL_STATUS_LIGHT:
            // Reduce bitrate 10%
            break;
        case PowerManager.THERMAL_STATUS_MODERATE:
            // Reduce resolution 720p, bitrate 30%
            break;
        case PowerManager.THERMAL_STATUS_SEVERE:
            // Reduce to 30fps, 720p, minimum bitrate
            break;
        case PowerManager.THERMAL_STATUS_CRITICAL:
            // Pause session, show warning
            break;
    }
});
```

### 2. Server-Side Rate Adaptation
- Client reports: thermal state, decode latency, frame drops
- Server adjusts: resolution, bitrate, fps, codec profile
- **REMB/GCC** integration for bandwidth
- **Custom RTCP** for thermal feedback

### 3. Codec Selection for Thermal
| Thermal State | Preferred Codec | Reason |
|---------------|----------------|--------|
| Normal | AV1/HEVC 10-bit | Best quality/bitrate |
| Warm | HEVC Main10 | Good efficiency, mature HW |
| Hot | H.264 High | Lowest decode complexity |
| Critical | H.264 Baseline | Minimum VPU load |

### 4. Frame Rate Reduction
- 60fps → 30fps: **~40% decoder power reduction**
- Use `Surface.setFrameRate(30, FIXED_SOURCE)` to hint display
- Maintain input sampling at 60/120Hz for responsiveness

### 5. Hardware Overlay Enforcement
```java
// Ensure hardware overlay used (bypasses GPU)
// Saves 2-5% total power, reduces GPU heat
surfaceView.setZOrderMediaOverlay(true);
surfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);  // Or opaque format
// Verify in dumpsys SurfaceFlinger: look for "HWC" composition type
```

### 6. Session Management
- **Pause on background**: Release MediaCodec, close sockets
- **Thermal cooldown**: 30-60s reduced quality before restore
- **User notification**: Non-intrusive thermal indicator

## Power Measurement & Profiling

### Tools
```bash
# Battery stats
adb shell dumpsys batterystats --charged <package>

# Power profile (requires root on some devices)
adb shell dumpsys power

# Per-process power (estimated)
adb shell dumpsys batterystats <package> | grep -A 20 "Uid"

# Thermal status
adb shell dumpsys thermal

# CPU frequency (thermal throttling indicator)
adb shell cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq
```

### Perfetto Power Tracking
- **Data Sources**: `android.power`, `android.battery`
- **Counters**: 
  - `rail_voltage` / `rail_current` (if supported)
  - `cpu_freq` per cluster
  - `gpu_freq`
  - `thermal_zone_temp`

## Lowest-Power Architecture for Stable Playback

### Priority Order (Best → Worst)
1. **H.264 720p@60 + Wi-Fi 6 + Hardware Overlay** - Minimum sustainable
2. **H.264 1080p@60 + Wi-Fi 6 + Hardware Overlay** - Balanced
3. **HEVC 1080p@60 + Wi-Fi 6E + Hardware Overlay** - Better quality
4. **AV1 1080p@60 + Wi-Fi 7 + Hardware Overlay** - Best quality (needs HW)
5. **Any 4K/120fps** - High power, thermal risk
6. **Software decode / TextureView / Cellular** - Avoid

### Configuration Checklist for Minimum Power
- [ ] Hardware decoder (MediaCodec Surface path)
- [ ] SurfaceView with hardware overlay
- [ ] Wi-Fi lock: `WIFI_MODE_FULL_HIGH_PERF` (not `LOW_LATENCY` - higher power)
- [ ] DSCP EF on sockets (QoS reduces retries)
- [ ] Adaptive bitrate/resolution/fps based on thermal
- [ ] FrameRate API: `surface.setFrameRate(fps, FIXED_SOURCE)`
- [ ] Release resources on background/pause
- [ ] Monitor: decoder stats, thermal, battery, frame drops

## References
- "To Cloud or Not to Cloud: Measuring the Performance of Mobile Gaming" (MobiGames 2015)
- "End-to-end Characterization of Game Streaming" (NSDI/academic)
- Android PowerManager Thermal API
- Qualcomm Snapdragon Thermal Management docs
- Perfetto Power Data Sources
- Wi-Fi Power Save Mode vs Gaming analysis