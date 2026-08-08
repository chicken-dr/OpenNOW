# Android Hardware Decoder Research for OpenNOW

## Overview
Analysis of hardware video decoder capabilities across major Android SoC families for cloud gaming workloads (H.264, H.265/HEVC, AV1, VP9).

## SoC Family Analysis

### Qualcomm Snapdragon (Adreno / Venus VPU)

| SoC Generation | H.264 | H.265/HEVC | VP9 | AV1 | Max Resolution | HDR Support | Notes |
|----------------|-------|------------|-----|-----|----------------|-------------|-------|
| Snapdragon 8 Gen 3 | ✓ | ✓ | ✓ | ✓ | 8K@60 / 4K@120 | HDR10, HDR10+, HLG, DV | Venus VPU, QIM SDK |
| Snapdragon 8 Gen 2 | ✓ | ✓ | ✓ | ✗ | 8K@60 / 4K@120 | HDR10, HDR10+, HLG, DV | Venus VPU |
| Snapdragon 7+ Gen 2 | ✓ | ✓ | ✓ | Partial | 4K@60 | HDR10, HDR10+ | Mid-range |
| Snapdragon 7 Gen 1 | ✓ | ✓ | ✓ | ✗ | 4K@60 | HDR10 | |
| Snapdragon 6 Gen 1 | ✓ | ✓ | ✓ | ✗ | 4K@60 | HDR10 | Entry |

**Key Technical Details**:
- **VPU Architecture**: Venus (V4L2 driver: `VIDEO_QCOM_VENUS`)
- **Vendor Extensions**: MediaCodec vendor keys for LTR (Long-Term Reference) frames
- **Color Formats**: NV12, P010 (10-bit), flexible YUV 4:2:0
- **Profile Support**: 
  - H.264: Baseline, Main, High (up to Level 5.1/5.2)
  - HEVC: Main, Main10 (up to Level 5.1/6.0)
  - VP9: Profile 0, 2 (10-bit)
  - AV1: Main (Gen 3+)
- **Conflicting Claims**: Vendor briefs claim 8K@60 but technical docs list max 4096×2160@60

**Known Issues**:
- Vendor MediaCodec keys required for some low-latency features
- Secure vs non-secure surface behavior varies by OEM implementation

### MediaTek Dimensity / Helio

| SoC Generation | H.264 | H.265/HEVC | VP9 | AV1 | Max Resolution | Notes |
|----------------|-------|------------|-----|-----|----------------|-------|
| Dimensity 9300 | ✓ | ✓ | ✓ | ✓ | 4K@60 | Flagship |
| Dimensity 9200 | ✓ | ✓ | ✓ | ✓ | 4K@60 | |
| Dimensity 9000 | ✓ | ✓ | ✓ | ✓ | 4K@60 | |
| Dimensity 1080 | ✓ | ✓ | ✓ | ✗ | 4K@60 | Android 15 HEVC regression |
| Dimensity 1000 | ✓ | ✓ | ✓ | ✓ | 4K@60 | First mobile AV1 HW decoder |
| Dimensity 700/900 | ✓ | ✓ | ✓ | ✗ | 1080p@60 | Android 15 HEVC black screen issue |

**Known Issues (Critical)**:
1. **Android 15 HEVC Regression**: Black screen with audio continuing on Dimensity 700/900/1080 (AndroidX Media #2711)
2. **Widevine-DASH Seek Failures**: `c2.mtk.avc.decoder.secure` decoder errors on seek (AndroidX Media #997)
3. **Vendor Codec Names**: `c2.mtk.avc.decoder`, `c2.mtk.hevc.decoder`, `c2.mtk.vp9.decoder`, `c2.mtk.av1.decoder`

### Samsung Exynos

| SoC Generation | H.264 | H.265/HEVC | VP9 | AV1 | Max Resolution | Notes |
|----------------|-------|------------|-----|-----|----------------|-------|
| Exynos 2400 | ✓ | ✓ | ✓ | ✓ | 8K@60 / 4K@120 | Xclipse 940 GPU |
| Exynos 2200 | ✓ | ✓ | ✓ | Partial | 8K@60 / 4K@120 | Xclipse 920, AV1 Main profile 4K@60 may fall back to SW |
| Exynos 2100 | ✓ | ✓ | ✓ | ✗ | 8K@60 / 4K@120 | |

**Known Issues**:
- Limited vendor documentation available
- Community reports: Exynos 2200 may use software fallback for AV1 Main profile 4K@60
- HEVC 4K@60 shows high CPU usage in some tests

### Google Tensor

| SoC Generation | H.264 | H.265/HEVC | VP9 | AV1 | Max Resolution | Notes |
|----------------|-------|------------|-----|-----|----------------|-------|
| Tensor G4 | ✓ | ✓ | ✓ | ✓ | 4K@60 | Pixel 9 series |
| Tensor G3 | ✓ | ✓ | ✓ | ✓ | 4K@60 | Pixel 8 series |
| Tensor G2 | ✓ | ✓ | ✓ | ✗ | 4K@60 | Pixel 7 series |
| Tensor G1 | ✓ | ✓ | ✓ | ✗ | 4K@60 | Pixel 6 series |

**Notes**: Google controls both SoC and Android - typically good MediaCodec integration. AV1 hardware decode from Tensor G3 (Pixel 8).

### HiSilicon Kirin

| SoC Generation | H.264 | H.265/HEVC | VP9 | AV1 | Max Resolution | Notes |
|----------------|-------|------------|-----|-----|----------------|-------|
| Kirin 9000 series | ✓ | ✓ | ✓ | Limited | 4K@60 | Huawei devices, limited recent data |
| Kirin 990 | ✓ | ✓ | ✓ | ✗ | 4K@60 | |

**Status**: Limited recent vendor documentation due to trade restrictions. Treat as evidence gap.

## Cross-Codec Capability Matrix

| Codec | Profile/Level | 8-bit | 10-bit | Low-Latency | Adaptive Playback | Secure Decode |
|-------|--------------|-------|--------|-------------|-------------------|---------------|
| H.264/AVC | Baseline/Main/High up to L5.2 | ✓ | Limited | ✓ (widely) | ✓ | ✓ (Widevine) |
| H.265/HEVC | Main/Main10 up to L6.0 | ✓ | ✓ | ✓ (API 30+) | ✓ | ✓ (Widevine) |
| VP9 | Profile 0/2 | ✓ | ✓ (Profile 2) | ✓ (API 30+) | ✓ | ✓ (Widevine) |
| AV1 | Main | Limited | ✓ | Partial | Partial | Emerging |

## Capability Detection Strategy

```java
// Enumerate and filter hardware decoders
MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
    if (!codecInfo.isEncoder()) {
        String name = codecInfo.getName();
        boolean isHardware = codecInfo.isHardwareAccelerated();  // API 29+
        boolean isVendor = codecInfo.isVendor();  // API 29+
        boolean isSoftwareOnly = codecInfo.isSoftwareOnly();  // API 29+
        
        CodecCapabilities caps = codecInfo.getCapabilitiesForType(mimeType);
        boolean supportsLowLatency = caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency);
        boolean supportsAdaptivePlayback = caps.isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback);
        boolean supportsTunneledPlayback = caps.isFeatureSupported(CodecCapabilities.FEATURE_TunneledPlayback);
        
        // Color formats
        int[] colorFormats = caps.getColorFormats();
        
        // Profile/level
        MediaCodecInfo.CodecProfileLevel[] profiles = caps.getProfileLevels();
    }
}
```

## Recommendations for OpenNOW Android

### Priority Codec Support Order
1. **H.264 High Profile** - Universal hardware support, lowest latency baseline
2. **H.265/HEVC Main/Main10** - Better compression, wide HW support, 10-bit for HDR
3. **VP9 Profile 0/2** - Good fallback, Google ecosystem standard
4. **AV1 Main** - Best compression, but limited HW support (Gen 3 Snapdragon, Tensor G3, Dimensity 9000+)

### Device-Specific Handling
- **MediaTek Dimensity 700/900/1080 on Android 15**: Disable HEVC hardware decode, force H.264 or software HEVC
- **Qualcomm Snapdragon**: Use vendor MediaCodec keys for LTR frame control if low-latency needed
- **Exynos 2200**: Test AV1 hardware decode path; fallback to HEVC if unstable
- **Tensor G3+**: Preferred AV1 hardware decode path

### Encoder Configuration Hints (Server-Side)
- Prefer constrained baseline for H.264 (no B-frames = lower latency)
- Limit reference frames to 2-3 for lower decoder buffer requirements
- Use HEVC Main10 for HDR content with 10-bit surface
- For AV1: target devices with confirmed HW support only

## Evidence Gaps
- Exact MediaCodec profile/level strings per SoC model
- Per-codec pixel format enumeration (NV12, P010, P210, 4:2:2, 4:4:4)
- Secure vs non-secure surface behavior differences
- Measured decode latency numbers per codec/SoC combination
- Maximum reference frame counts per decoder
- Tunneled playback implementation status

## References
- Qualcomm Product Briefs (Snapdragon 8 Gen 2/3, 7 Gen 1, 6 Gen 1)
- Qualcomm Venus VPU Documentation (QIM SDK, V4L2 driver)
- MediaTek Press Releases (Dimensity 1000 AV1, Dimensity 9000/9200/9300)
- AndroidX Media Issues (#2711, #997)
- Moonlight-Android decoder-errata.txt
- AOSP MediaCodecInfo source