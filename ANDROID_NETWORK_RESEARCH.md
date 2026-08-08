# Android Network Research for OpenNOW Cloud Gaming

## Overview
Analysis of the network stack for cloud gaming on Android, from packet reception through WebRTC processing to MediaCodec input. OpenNOW currently uses WebRTC (Chrome's implementation) via Electron on desktop. This document establishes the Android network architecture.

## Network Pipeline

```
Wi-Fi / Cellular Modem
    ↓ Linux Kernel (mac80211 / IP stack)
    ↓ UDP Socket (WebRTC NetEq)
    ↓ WebRTC RTP Receiver
    ↓ RTP Depacketization
    ↓ Jitter Buffer (NetEq)
    ↓ Frame Buffer (decoded frames ready for MediaCodec)
    ↓ MediaCodec Input Buffer
```

## Android Network Stack Specifics

### 1. Socket Layer
- **API**: `DatagramChannel` / `DatagramSocket` (Java) or raw socket (NDK)
- **WebRTC**: Uses `NetworkDispatcher` + `AsyncSocket` (C++) via JNI
- **Thread**: Dedicated network thread (not UI thread)
- **Buffer Sizes**: 
  - `SO_RCVBUF`: Default ~256KB, can increase via `setReceiveBufferSize()`
  - Critical for burst handling at high bitrates (50+ Mbps)

### 2. Wi-Fi vs Cellular

| Aspect | Wi-Fi (6/6E/7) | Cellular (5G/4G) |
|--------|----------------|------------------|
| **Latency** | 2-10ms (local) | 10-50ms (varies) |
| **Jitter** | Low (stable AP) | Higher (handoffs, scheduling) |
| **Throughput** | 100Mbps-1Gbps+ | 50Mbps-2Gbps (mmWave) |
| **Power** | Moderate | High (modem power) |
| **Reliability** | Good (fixed) | Variable (mobility) |

**Cloud Gaming Implications**:
- Wi-Fi preferred for latency stability
- 5G mmWave can match Wi-Fi but inconsistent
- Wi-Fi 6/6E/7: OFDMA, MU-MIMO, lower latency modes
- **Power Save Mode (PSM)**: Must disable for gaming (adds 10-50ms latency)

### 3. WebRTC on Android

#### Current OpenNOW Desktop Architecture
- Electron embeds Chrome → uses Chrome's WebRTC stack
- C++ WebRTC → JavaScript bindings → React renderer

#### Android Options

| Approach | Description | Pros | Cons |
|----------|-------------|------|------|
| **WebRTC Android SDK (Google)** | Official AAR: `org.webrtc:google-webrtc` | Maintained, hardware accelerated, native | Large binary (~20MB), JNI overhead |
| **libwebrtc built from source** | Compile WebRTC for Android | Full control, optimize size | Complex build, maintenance burden |
| **Chromium Embedded (WebView)** | Use System WebView WebRTC | Small app size | Version fragmentation, limited control |
| **Custom UDP + MediaCodec** | Bypass WebRTC, raw RTP | Minimal, controlled | Reinventing congestion control, FEC, NACK |

**Recommendation**: Use official WebRTC Android SDK (`org.webrtc:google-webrtc:1.0.x`). It's what Chrome/Android apps use, includes hardware codec integration.

### 4. WebRTC Components Relevant to Latency

#### NetEq (Jitter Buffer & Concealment)
- **Target delay**: Configurable (default ~100ms)
- **Minimum delay**: Can go as low as 10-20ms for gaming
- **Algorithm**: Adaptive based on network jitter
- **Key settings for gaming**:
  ```java
  // PeerConnectionFactory options
  PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
  options.networkIgnoreMask = 0;  // Use all interfaces
  
  // Audio/Video receive parameters
  RtpParameters params = new RtpParameters();
  params.degradationPreference = DegradationPreference.MAINTAIN_FRAMERATE;
  params.encodings[0].maxFramerate = 60;
  params.encodings[0].maxBitrateBps = 50_000_000;
  ```

#### Congestion Control (GCC - Google Congestion Control)
- **BWE (Bandwidth Estimation)**: REMB + Transport-wide CC
- **Reaction time**: ~1-2 RTT
- **Gaming tuning**: More aggressive, lower queue target

#### NACK / FEC / RTX
- **NACK**: Negative ACK for retransmission (PLI for keyframes)
- **FEC**: Forward Error Correction (ULPFEC / FlexFEC)
- **RTX**: Retransmission (RTX SSRC)
- **For gaming**: Enable NACK + RTX, consider FEC for high loss

### 5. RTP Depacketization

WebRTC handles:
- H.264: RFC 6184 (STAP-A, FU-A)
- H.265: RFC 7798 (AP, FU)
- VP9: RFC draft (non-reference frames)
- AV1: RFC draft (OBU-based)

**Output**: Complete frames (NAL units / OBUs) → MediaCodec input buffers

### 6. Frame Timing Through Network Stack

```
T0: Packet arrives at NIC (hardware timestamp)
T1: Kernel processes UDP, socket readable (epoll)
T2: WebRTC network thread reads socket
T3: RTP parsing, jitter buffer insertion
T4: Frame complete → NetEq delivers to decoder callback
T5: MediaCodec.dequeueInputBuffer() 
T6: Copy RTP payload to MediaCodec input buffer
T7: MediaCodec.queueInputBuffer()
```

**Typical Latencies**:
- T0→T2: 0.1-1ms (kernel + thread wakeup)
- T2→T4: 0.5-2ms (RTP + jitter buffer logic)
- T4→T7: 0.5-2ms (JNI + buffer copy + queue)

**Total network→decode queue**: 1-5ms (plus jitter buffer holding time)

## Android-Specific Optimizations

### 1. Socket Buffer Tuning
```java
// Increase receive buffer for high-bitrate streams
DatagramChannel channel = DatagramChannel.open();
channel.socket().setReceiveBufferSize(2 * 1024 * 1024);  // 2MB
channel.socket().setPerformancePreferences(0, 2, 1);  // Latency priority
```

### 2. Network Thread Priority
```java
// WebRTC network thread - set high priority
Thread networkThread = new Thread(networkRunnable);
networkThread.setPriority(Thread.MAX_PRIORITY);  // or THREAD_PRIORITY_URGENT_AUDIO
```

### 3. Wi-Fi Lock
```java
// Prevent Wi-Fi radio sleep during gaming
WifiManager wifiManager = (WifiManager) context.getSystemService(WIFI_SERVICE);
WifiManager.WifiLock wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OpenNOW-Gaming");
wifiLock.acquire();
// Release when session ends
```

### 4. Traffic Class / QoS
```java
// Set DSCP marking for gaming traffic (EF = Expedited Forwarding)
channel.socket().setTrafficClass(0xB8);  // DSCP 46 (EF)
// Or for lower priority background: 0x20 (CS0)
```

### 5. Multipath / Interface Selection
- Use `ConnectivityManager.getNetworkCapabilities()` to detect best interface
- Prefer Wi-Fi 6/6E/7 (low latency) over cellular
- Bind socket to specific network: `ConnectivityManager.bindProcessToNetwork()`

## WebRTC Integration with MediaCodec

### Hardware Decoder Selection
```java
// WebRTC VideoDecoderFactory using MediaCodec
VideoDecoderFactory factory = new MediaCodecVideoDecoderFactory(
    eglContext,  // for texture output if needed
    true,        // enable H.264
    true,        // enable H.265
    true,        // enable VP9
    true         // enable AV1
);

// Low-latency configuration
MediaCodecVideoDecoder.setLowLatencyMode(true);  // Custom extension
```

### Surface Input to WebRTC
```java
// For hardware decoder output directly to Surface
VideoSink sink = new VideoSink() {
    @Override
    public void onFrame(VideoFrame frame) {
        // Frame has texture ID or buffer - render to Surface
    }
};
```

## Packet Loss & Recovery Impact on Latency

| Loss Rate | NACK RTT | Recovery Latency | Frame Impact |
|-----------|----------|------------------|--------------|
| 0.1% | 20ms | 40ms | Minimal |
| 1% | 20ms | 40ms | Occasional freeze |
| 5% | 20ms | 40-80ms | Frequent artifacts |
| >10% | 20ms | 100ms+ | Unplayable |

**Mitigation**: 
- Server-side: Redundant encoding (SVC, simulcast)
- Client-side: Larger jitter buffer (trade latency for stability)
- Network: QoS, 5GHz Wi-Fi, wired where possible

## Android 14+ Network Improvements

- **Wi-Fi 7 (802.11be)**: MLO (Multi-Link Operation) - simultaneous 2.4/5/6GHz
- **Low Latency Mode**: `WifiManager.WIFI_MODE_FULL_LOW_LATENCY`
- **Network Scoring**: Better automatic network selection
- **Satellite connectivity**: Emergency fallback (not for gaming)

## Testing Network Latency

### Tools
- `adb shell dumpsys netstats` - per-UID network stats
- `adb shell cat /proc/net/udp` - socket state
- Perfetto: `atrace` categories `net`, `gfx`, `view`
- WebRTC internals: `webrtc-internals` (chrome://webrtc-internals)

### Key Metrics to Track
| Metric | Target | Measurement |
|--------|--------|-------------|
| End-to-end latency | <80ms | Server PTS → display |
| Jitter buffer delay | <30ms | NetEq stats |
| Packet loss | <0.5% | RTCP RR |
| RTT | <30ms | STUN/RTCP |
| Bandwidth estimation accuracy | ±10% | GCC vs actual |

## Integration Checklist for OpenNOW Android

- [ ] Integrate `org.webrtc:google-webrtc` AAR
- [ ] Configure PeerConnectionFactory with gaming-optimized options
- [ ] Set up MediaCodecVideoDecoderFactory with low-latency mode
- [ ] Implement Wi-Fi lock (WIFI_MODE_FULL_HIGH_PERF)
- [ ] Configure socket buffers (2MB+ receive)
- [ ] Set DSCP EF (0xB8) on WebRTC sockets
- [ ] Bind to best network (Wi-Fi 6/6E/7 preferred)
- [ ] Enable NACK + RTX, consider FEC
- [ ] Tune NetEq minimum delay to 20ms
- [ ] Add Perfetto trace markers for network→decode path
- [ ] Implement bandwidth estimation telemetry
- [ ] Test on Wi-Fi 6/6E/7 and 5G mmWave

## References
- [WebRTC Android SDK](https://webrtc.org/getting-started/android)
- [Android Wi-Fi Lock](https://developer.android.com/reference/android/net/wifi/WifiManager.WifiLock)
- [WebRTC NetEq](https://webrtc.github.io/webrtc-api/nettest/)
- [GCC Congestion Control](https://tools.ietf.org/html/draft-ietf-rmcat-gcc-02)
- [Android Traffic Class](https://developer.android.com/reference/java/net/Socket#setTrafficClass(int))
- [ConnectivityManager.bindProcessToNetwork](https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network))