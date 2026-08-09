<h1 align="center">CloseNOW</h1>

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="CloseNOW logo" width="180" />
</p>

<p align="center">
  <strong>An Android-first cloud gaming client for GeForce NOW.</strong>
</p>

<p align="center">
  <strong>Built for minimal latency, optimal power efficiency, and sustained thermal performance.</strong>
</p>

<p align="center">
  <a href="https://github.com/chicken-dr/OpenNOW/releases">
    <img src="https://img.shields.io/github/v/tag/chicken-dr/OpenNOW?style=for-the-badge&label=Download&color=brightgreen" alt="Download">
  </a>
  <a href="https://github.com/chicken-dr/OpenNOW/actions/workflows/auto-build.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/chicken-dr/OpenNOW/auto-build.yml?style=for-the-badge&label=Auto%20Build" alt="Auto Build">
  </a>
  <a href="https://discord.gg/8EJYaJcNfD">
    <img src="https://img.shields.io/badge/Discord-Join%20Us-7289da?style=for-the-badge&logo=discord&logoColor=white" alt="Discord">
  </a>
</p>

<p align="center">
  <img src="app/src/main/res/drawable/banner.png" alt="CloseNOW application preview" />
</p>

> [!IMPORTANT]
> CloseNOW is an independent community project and is not affiliated with, endorsed by, or sponsored by NVIDIA. NVIDIA and GeForce NOW are trademarks of NVIDIA Corporation. You must use your own GeForce NOW account.

## Overview

CloseNOW is an Android-first cloud gaming client for GeForce NOW, built from the ground up for minimal latency streaming on mobile devices. The implementation targets sub-80ms end-to-end latency through:

- **Direct MediaCodec → Surface rendering** (zero-copy path via BufferQueue)
- **Hardware decoder prioritization** with vendor-specific optimizations (Qualcomm, MediaTek, Exynos, Tensor)
- **Choreographer-synchronized frame pacing** for VSYNC-aligned presentation
- **Thermal-aware adaptive quality** with hysteresis and cooldown logic
- **High-priority thread architecture** with CPU affinity hints
- **Perfetto/FrameTimeline integration** for measurement-driven optimization

## Downloads

Grab the latest Android build from [GitHub Releases](https://github.com/chicken-dr/OpenNOW/releases).

## Documentation

### Android Architecture & Research
- [Android Architecture](docs/ANDROID_OPENNOW_ARCHITECTURE.md)
- [Media Pipeline](docs/ANDROID_MEDIA_PIPELINE.md)
- [Decoder Research](docs/ANDROID_DECODER_RESEARCH.md)
- [Rendering Research](docs/ANDROID_RENDERING_RESEARCH.md)
- [Network Research](docs/ANDROID_NETWORK_RESEARCH.md)
- [Input Latency](docs/ANDROID_INPUT_LATENCY.md)
- [Power & Thermal](docs/ANDROID_POWER_THERMAL.md)
- [Memory Copy Analysis](docs/ANDROID_MEMORY_COPY_ANALYSIS.md)
- [Threading](docs/ANDROID_THREADING.md)
- [Diagnostics](docs/ANDROID_DIAGNOSTICS.md)
- [Low Latency Decoding](docs/ANDROID_LOW_LATENCY_DECODING.md)

### Implementation Guides
- [Architecture Review](docs/ANDROID_ARCHITECTURE_REVIEW.md)
- [Benchmark Plan](docs/ANDROID_BENCHMARK_PLAN.md)
- [Device Compatibility](docs/ANDROID_DEVICE_COMPATIBILITY.md)
- [Optimization Master Plan](docs/ANDROID_OPTIMIZATION_MASTER_PLAN.md)
- [Optimization Roadmap](docs/ANDROID_OPTIMIZATION_ROADMAP.md)

## Repository Layout

```text
.
├── app/                      Android application module
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/closenow/  Kotlin source code
│   │   │   ├── res/                Resources (layouts, strings, themes)
│   │   │   └── AndroidManifest.xml
│   │   └── test/                   Unit tests
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── lint-baseline.xml
├── gradle/                   Gradle wrapper
├── docs/                     Android documentation
├── .github/                  Workflows and templates
├── AGENTS.md                 Repository instructions
├── LICENSE                   Project license
├── README.md                 This file
├── settings.gradle.kts       Gradle settings
├── gradle.properties         Gradle configuration
└── gradlew                   Gradle wrapper script
```

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run unit tests
./gradlew testDebugUnitTest

# Run lint
./gradlew lintDebug
```

## Architecture Highlights

### Media Pipeline
- `DecoderSelector` - Hardware decoder discovery and selection
- `MediaCodecDecoder` - Async MediaCodec with Surface output
- `GameSurfaceView` - SurfaceView for zero-copy rendering
- `FramePacer` - Choreographer VSYNC synchronization

### Network & WebRTC
- `WebRTCNetworkManager` - Pexip WebRTC integration
- `SignalingClient` - GFN WebSocket signaling
- `NetworkOptimizer` - Wi-Fi lock, traffic shaping

### Device Optimization
- `DeviceCapabilityDetector` - SoC vendor detection
- Vendor-specific optimizers (Qualcomm, MediaTek, Exynos, Tensor)
- `ThermalManager` - PowerManager thermal API integration
- `QualityController` - Adaptive quality with hysteresis

### Diagnostics
- `TelemetryCollector` - Rolling percentiles (P50/P95/P99)
- `PerfettoTrace` - Trace sections for all pipeline stages
- `DumpsysCollector` - Post-session state analysis

## Contributing

Contributions are welcome. Read the [contributing guide](.github/CONTRIBUTING.md), keep changes focused, and explain user-facing impact clearly.

## License

CloseNOW is licensed under the [MIT License](LICENSE).
