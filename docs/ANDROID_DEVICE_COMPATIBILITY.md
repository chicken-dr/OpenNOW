# Android Device Compatibility Matrix for OpenNOW

**Status**: Research Phase - Based on publicly available documentation, vendor briefs, AOSP sources, and community reports  
**Last Updated**: 2026  
**Note**: All capabilities marked based on verified public evidence. Items marked **UNKNOWN** lack reliable public documentation.

---

## Overview

This matrix covers hardware video decoder capabilities across major Android SoC families for cloud gaming workloads. OpenNOW targets Android 11+ (API 30) for low-latency MediaCodec features.

---

## Qualcomm Snapdragon (Adreno / Venus VPU)

| SoC Generation | H.264 | H.265/HEVC | VP9 | AV1 | Max Resolution | HDR Support | Low-Latency | Surface Output | Notes |
|----------------|-------|------------|-----|-----|----------------|-------------|-------------|----------------|-------|
| **Snapdragon 8 Gen 3** (SM8650) | ✓ HW | ✓ HW | ✓ HW | ✓ HW | 8K@60 / 4K@120 | HDR10, HDR10+, HLG, DV | ✓ (Venus VPU) | ✓ | Flagship 2024; Venus VPU; QIM SDK |
| **Snapdragon 8 Gen 2** (SM8550) | ✓ HW | ✓ HW | ✓ HW | ✗ | 8K@60 / 4K@120 | HDR10, HDR10+, HLG, DV | ✓ (Venus VPU) | ✓ | Flagship 2023; Venus VPU |
| **Snapdragon 8 Gen 1** (SM8450) | ✓ HW | ✓ HW | ✓ HW | ✗ | 8K@30 / 4K@120 | HDR10, HDR10+, HLG | ✓ | ✓ | First Gen; thermal throttling common |
| **Snapdragon 7+ Gen 2** (SM7475) | ✓ HW | ✓ HW | ✓ HW | Partial | 4K@60 | HDR10, HDR10+ | ✓ | ✓ | Mid-high 2023; AV1 partial |
| **Snapdragon 7 Gen 1** (SM7450) | ✓ HW | ✓ HW | ✓ HW | ✗ | 4K@60 | HDR10 | ✓ | ✓ | Mid 2022 |
| **Snapdragon 7s Gen 2** (SM7635) | ✓ HW | ✓ HW | ✓ HW | ✗ | 4K@60 | HDR10 | ✓ | ✓ | Mid 2023 |
| **Snapdragon 6 Gen 1** (SM6450) | ✓ HW | ✓ HW | ✓ HW | ✗ | 4K@60 | HDR10 | ✓ | ✓ | Entry 2022 |
| **Snapdragon 6s Gen 3** | ✓ HW | ✓ HW | ✓ HW | ✗ | 4K@60 | HDR10 | UNKNOWN | ✓ | Entry 2024 |

### Qualcomm Key Technical Details

| Aspect | Details |
|--------|---------|
| **VPU Architecture** | Venus (V4L2 driver: `VIDEO_QCOM_VENUS`) |
| **Vendor Extensions** | MediaCodec vendor keys for LTR (Long-Term Reference) frames |
| **Color Formats** | NV12, P010 (10-bit), flexible YUV 4:2:0 (COLOR_FormatSurface) |
| **Profile Support** | H.264: Baseline/Main/High up to L5.2; HEVC: Main/Main10 up to L6.0; VP9: Profile 0/2; AV1: Main (Gen 3+) |
| **Conflicting Specs** | Vendor briefs claim 8K@60 but technical docs list max 4096×2160@60 |
| **Low-Latency Support** | Well supported on Gen 2+ via vendor keys; requires constrained baseline profile for best effect |
| **Secure Decode** | Widevine L1 supported; `c2.qcom.*.decoder.secure` |

### Qualcomm Known Issues
- Vendor MediaCodec keys required for some low-latency features (not standard API)
- Secure vs non-secure surface behavior varies by OEM implementation
- Thermal throttling on sustained 4K/120fps without active cooling
- Snapdragon 8 Gen 1 has significant thermal throttling

---

## MediaTek Dimensity / Helio

| SoC Generation | H.264 | H.265/HEVC | VP9 | AV1 | Max Resolution | Low-Latency | Surface Output | Notes |
|----------------|-------|------------|-----|-----|----------------|-------------|----------------|-------|
| **Dimensity 9300** | ✓ HW | ✓ HW | ✓ HW | ✓ HW | 4K@60 | UNKNOWN | ✓ | Flagship 2024 |
| **Dimensity 9200** | ✓ HW | ✓ HW | ✓ HW | ✓ HW | 4K@60 | UNKNOWN | ✓ | Flagship 2023 |
| **Dimensity 9000** | ✓ HW | ✓ HW | ✓ HW | ✓ HW | 4K@60 | UNKNOWN | ✓ | Flagship 2022; First 4nm |
| **Dimensity 8300** | ✓ HW | ✓ HW | ✓ HW | ✓ HW | 4K@60 | UNKNOWN | ✓ | Upper-mid 2024 |
| **Dimensity 8200** | ✓ HW | ✓ HW | ✓ HW | ✓ HW | 4K@60 | UNKNOWN | ✓ | Upper-mid 2023 |
| **Dimensity 8100** | ✓ HW | ✓ HW | ✓ HW | ✓ HW | 4K@60 | UNKNOWN | ✓ | Mid 2022 |
| **Dimensity 1080** | ✓ HW | ✓ HW | ✓ HW | ✗ | 4K@60 | UNKNOWN | ✓ | Mid 2022 |
| **Dimensity 1000** | ✓ HW | ✓ HW | ✓ HW | ✓ HW | 4K@60 | UNKNOWN | ✓ | **First mobile AV1 HW** (2019) |
| **Dimensity 900** | ✓ HW | ✓ HW | ✓ HW | ✗ | 1080p@60 | UNKNOWN | ✓ | Budget 2021 |
| **Dimensity 700** | ✓ HW | ✓ HW | ✓ HW | ✗ | 1080p@60 | UNKNOWN | ✓ | Budget 2020 |
| **Helio G99** | ✓ HW | ✓ HW | ✓ HW | ✗ | 1080p@60 | UNKNOWN | ✓ | 4G only |

### MediaTek Key Technical Details

| Aspect | Details |
|--------|---------|
| **Vendor Codec Names** | `c2.mtk.avc.decoder`, `c2.mtk.hevc.decoder`, `c2.mtk.vp9.decoder`, `c2.mtk.av1.decoder` |
| **Secure Decode** | `c2.mtk.avc.decoder.secure`, `c2.mtk.hevc.decoder.secure` |
| **Color Formats** | NV12, P010 (10-bit on flagship) |
| **Low-Latency Support** | Limited documentation; test per device |

### MediaTek Critical Known Issues

| Issue | Affected Devices | Status | Workaround |
|-------|------------------|--------|------------|
| **Android 15 HEVC Black Screen** | Dimensity 700/900/1080 (mt6769, mt6833, mt6853, mt6873) | **CONFIRMED** (AndroidX Media #2711) | Disable HEVC HW; force H.264 or SW HEVC |
| **Widevine-DASH Seek Failure** | Dimensity 700/900/1080 | **CONFIRMED** (AndroidX Media #997) | Test secure decoder path; may need fallback |
| **AV1 on Dimensity 1000** | Dimensity 1000 | Limited to 4K@60 | Verify per device |

---

## Samsung Exynos

| SoC Generation | H.264 | H.265/HEVC | VP9 | AV1 | Max Resolution | Low-Latency | Surface Output | Notes |
|----------------|-------|------------|-----|-----|----------------|-------------|----------------|-------|
| **Exynos 2400** (S5E9945) | ✓ HW | ✓ HW | ✓ HW | ✓ HW | 8K@60 / 4K@120 | UNKNOWN | ✓ | Flagship 2024; Xclipse 940 |
| **Exynos 2200** (S5E9925) | ✓ HW | ✓ HW | ✓ HW | Partial | 8K@60 / 4K@120 | UNKNOWN | ✓ | Flagship 2022; Xclipse 920 |
| **Exynos 2100** (S5E9915) | ✓ HW | ✓ HW | ✓ HW | ✗ | 8K@60 / 4K@120 | UNKNOWN | ✓ | Flagship 2021 |
| **Exynos 1380** (S5E8835) | ✓ HW | ✓ HW | ✓ HW | ✗ | 4K@60 | UNKNOWN | ✓ | Mid 2023 |
| **Exynos 1280** (S5E8825) | ✓ HW | ✓ HW | ✓ HW | ✗ | 4K@60 | UNKNOWN | ✓ | Mid 2022 |

### Samsung Exynos Key Technical Details

| Aspect | Details |
|--------|---------|
| **GPU/VPU** | Xclipse (AMD RDNA) on 2200/2400; Mali on older |
| **Vendor Documentation** | Very limited; no public VPU specs |
| **Codec Names** | `c2.exynos.*.decoder` (pattern assumed) |
| **AV1 Support** | 2400: Full HW; 2200: Main profile 4K@60 may fall back to SW (community reports) |
| **HEVC 4K@60** | High CPU usage reported on 2200 in some tests |

### Samsung Exynos Known Issues
- **Limited vendor documentation** - empirical validation required per device
- Exynos 2200 AV1 Main profile 4K@60 may fall back to software decode
- HEVC 4K@60 shows high CPU usage in some community tests
- Integrated CPU/GPU/VPU thermal management - monitor closely

---

## Google Tensor

| SoC Generation | H.264 | H.265/HEVC | VP9 | AV1 | Max Resolution | Low-Latency | Surface Output | Notes |
|----------------|-------|------------|-----|-----|----------------|-------------|----------------|-------|
| **Tensor G4** (2024) | ✓ HW | ✓ HW | ✓ HW | ✓ HW | 4K@60 | UNKNOWN | ✓ | Pixel 9 series |
| **Tensor G3** (2023) | ✓ HW | ✓ HW | ✓ HW | ✓ HW | 4K@60 | UNKNOWN | ✓ | Pixel 8 series; First AV1 HW |
| **Tensor G2** (2022) | ✓ HW | ✓ HW | ✓ HW | ✗ | 4K@60 | UNKNOWN | ✓ | Pixel 7 series |
| **Tensor G1** (2021) | ✓ HW | ✓ HW | ✓ HW | ✗ | 4K@60 | UNKNOWN | ✓ | Pixel 6 series |

### Google Tensor Key Technical Details

| Aspect | Details |
|--------|---------|
| **VPU** | Custom Google VPU (BigOcean on G3+) |
| **Platform Integration** | Excellent - Google controls both SoC and Android |
| **Thermal** | Custom TPU/VPU thermal zones; Pixel 8+ vapor chamber effective |
| **AV1 Support** | Hardware decode from Tensor G3 (Pixel 8) |
| **Low-Latency** | Expected good support (Google controls platform) |

### Google Tensor Known Issues
- Tensor G1/G2: No AV1 hardware decode (software only)
- Tensor G3+: Best AV1 hardware decode path in Android ecosystem
- Thermal zones well-defined but custom - monitor VPU separately

---

## HiSilicon Kirin (Legacy/Deprecated)

| SoC Generation | H.264 | H.265/HEVC | VP9 | AV1 | Max Resolution | Notes |
|----------------|-------|------------|-----|-----|----------------|-------|
| **Kirin 9000 series** | ✓ HW | ✓ HW | ✓ HW | Limited | 4K@60 | Huawei devices; limited recent data |
| **Kirin 990** | ✓ HW | ✓ HW | ✓ HW | ✗ | 4K@60 | 2019 flagship |

### HiSilicon Status
- **Status**: DEPRECATED for new development
- **Reason**: Trade restrictions; no recent vendor documentation
- **Recommendation**: Treat as evidence gap; test if encountered in field

---

## Cross-Codec Capability Summary

| Codec | Profile/Level | 8-bit | 10-bit | Low-Latency (API 30+) | Adaptive Playback | Secure Decode (Widevine) | OpenNOW Priority |
|-------|--------------|-------|--------|----------------------|-------------------|-------------------------|------------------|
| **H.264/AVC** | Baseline/Main/High up to L5.2 | ✓ All | Limited | ✓ Widely | ✓ All | ✓ All | **1 (Highest)** |
| **H.265/HEVC** | Main/Main10 up to L6.0 | ✓ All | ✓ Flagship | ✓ API 30+ | ✓ All | ✓ All | **2** |
| **VP9** | Profile 0/2 | ✓ All | ✓ Profile 2 | ✓ API 30+ | ✓ All | ✓ All | **3** |
| **AV1** | Main | Limited | ✓ Flagship | Partial | Partial | Emerging | **4** |

---

## OpenNOW Codec Priority Order (Client-Side SDP Preference)

```kotlin
// Priority order for SDP munging / codec selection
val CODEC_PRIORITY = listOf(
    "H264",      // Universal HW, lowest latency baseline, no B-frames possible
    "H265",      // Better compression, 10-bit for HDR, wide HW support
    "VP9",       // Google ecosystem standard, good fallback
    "AV1"        // Best compression, but limited HW (Gen 3+/Tensor G3+/Dimensity 9000+)
)

// Server-side encoding hints for optimal Android decode
val SERVER_ENCODING_HINTS = mapOf(
    "H264" to mapOf(
        "profile" to "constrained_baseline",  // No B-frames = lower latency
        "level" to "4.2",                      // 1080p@60
        "ref_frames" to 2,                     // Minimize decoder buffer
        "keyframe_interval" to 2,              // Fast recovery
        "bitrate_kbps" to 25000
    ),
    "H265" to mapOf(
        "profile" to "main10",                 // 10-bit for HDR
        "level" to "5.1",
        "ref_frames" to 2,
        "keyframe_interval" to 2,
        "bitrate_kbps" to 20000                // ~20% better than H.264
    ),
    "VP9" to mapOf(
        "profile" to "profile_0",              // 8-bit
        "level" to "4.2",
        "ref_frames" to 2,
        "keyframe_interval" to 2,
        "bitrate_kbps" to 20000
    ),
    "AV1" to mapOf(
        "profile" to "main",                   // Main profile
        "level" to "5.1",
        "ref_frames" to 2,
        "keyframe_interval" to 2,
        "bitrate_kbps" to 15000                // ~25% better than HEVC
    )
)
```

---

## Device Tier Classification for Testing

| Tier | SoC Examples | Expected Codecs | Max Quality Target | OpenNOW Test Priority |
|------|--------------|-----------------|-------------------|----------------------|
| **Flagship 2024** | Snapdragon 8 Gen 3, Tensor G4, Dimensity 9300, Exynos 2400 | All HW | 4K@60 AV1 | P0 (Must test) |
| **Flagship 2023** | Snapdragon 8 Gen 2, Tensor G3, Dimensity 9200, Exynos 2200 | All HW | 4K@60 HEVC | P0 (Must test) |
| **High 2023** | Snapdragon 7+ Gen 2, Dimensity 8200/8300 | H.264, HEVC, VP9, AV1 | 1080p@120 | P1 |
| **Mid 2023** | Snapdragon 7 Gen 1, Dimensity 1080, Exynos 1380 | H.264, HEVC, VP9 | 1080p@60 | P1 |
| **Budget** | Snapdragon 6 Gen 1, Dimensity 700/900, Helio G99 | H.264, HEVC | 720p@60 | P2 |

---

## Capability Detection Code (Runtime)

```kotlin
// Run at app startup - cache results per device model
data class DecoderCapability(
    val codecName: String,
    val isHardware: Boolean,
    val isVendor: Boolean,
    val supportsLowLatency: Boolean,
    val colorFormats: IntArray,
    val profiles: Array<MediaCodecInfo.CodecProfileLevel>
)

fun queryAllDecoderCapabilities(): Map<String, DecoderCapability> {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val mimeTypes = listOf("video/avc", "video/hevc", "video/vp9", "video/av01")
    val results = mutableMapOf<String, DecoderCapability>()
    
    for (mime in mimeTypes) {
        for (info in list.codecInfos) {
            if (!info.isEncoder && info.isHardwareAccelerated()) {
                val caps = info.getCapabilitiesForType(mime)
                val capability = DecoderCapability(
                    codecName = info.name,
                    isHardware = info.isHardwareAccelerated(),
                    isVendor = info.isVendor(),
                    supportsLowLatency = caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency),
                    colorFormats = caps.colorFormats,
                    profiles = caps.profileLevels
                )
                // Keep best (prefer vendor > non-vendor)
                val existing = results[mime]
                if (existing == null || (capability.isVendor && !existing.isVendor)) {
                    results[mime] = capability
                }
            }
        }
    }
    return results
}
```

---

## Evidence Gaps (Marked UNKNOWN)

| SoC Family | Unknown Items |
|------------|---------------|
| **Qualcomm** | Exact per-SoC profile/level strings; measured decode latency numbers; secure surface behavior per OEM; max reference frames |
| **MediaTek** | Low-latency support per SoC; exact AV1 profile support on 9000/9200/9300; thermal behavior per device |
| **Samsung Exynos** | AV1 HW on 2200 (community says SW fallback); low-latency support; exact profile/level; VPU thermal independence |
| **Google Tensor** | Low-latency support confirmation; VPU thermal zone details; exact AV1 capabilities on G3/G4 |
| **HiSilicon** | All capabilities (deprecated) |

---

## References

- Qualcomm Product Briefs (Snapdragon 8 Gen 2/3, 7 Gen 1, 6 Gen 1)
- Qualcomm Venus VPU Documentation (QIM SDK, V4L2 driver `VIDEO_QCOM_VENUS`)
- MediaTek Press Releases (Dimensity 1000 AV1, Dimensity 9000/9200/9300)
- AndroidX Media Issues (#2711 HEVC black screen, #997 Widevine seek)
- Moonlight-Android decoder-errata.txt
- AOSP MediaCodecInfo source
- Community reports (XDA, Reddit, GitHub issues)
- Samsung Exynos product pages (limited VPU info)
- Google Tensor product pages (Pixel 8/9)