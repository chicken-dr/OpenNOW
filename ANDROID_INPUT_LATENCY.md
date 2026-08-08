# Android Input Latency Research for OpenNOW Cloud Gaming

## Overview
Analysis of the input pipeline from physical input device through Android framework to network transmission for cloud gaming. Goal: minimize input latency and understand where delays are introduced.

## Input Pipeline Architecture

```
PHYSICAL INPUT → KERNEL → ANDROID FRAMEWORK → APP → NETWORK → CLOUD
     │              │           │              │       │         │
  Controller    evdev      EventHub        ViewRootImpl   UDP     Game
  Touchscreen   driver     InputReader     InputStage     sendto  Server
  Keyboard      (IRQ)      InputDispatcher Choreographer        (decode
  Mouse                                         (render)         render)
```

## Detailed Stage Analysis

### 1. Physical Input Device

| Device Type | Transport | Typical Latency | Polling Rate | Notes |
|-------------|-----------|-----------------|--------------|-------|
| **Touchscreen** | I2C/SPI | 1-4ms | 120-240Hz | Controller dependent |
| **Bluetooth Gamepad** | BT HID | 8-20ms | 125-1000Hz | BT stack + HID parsing |
| **Bluetooth 5.0+ Gamepad** | BT LE | 4-12ms | 1000Hz | Lower latency modes |
| **USB Gamepad** | USB HID | 1-2ms | 1000Hz | Direct, lowest latency |
| **Keyboard/Mouse** | USB/BT | 1-10ms | 1000Hz | Varies by transport |

### 2. Kernel Input Subsystem (evdev)

```c
// Path: drivers/input/evdev.c
// Hardware interrupt → input_event → evdev queue → userspace read()

// Timestamp: CLOCK_MONOTONIC at interrupt time
struct input_event {
    struct timeval time;   // Kernel timestamp
    __u16 type;            // EV_KEY, EV_ABS, EV_REL, EV_SYN
    __u16 code;            // KEY_A, ABS_X, REL_X, SYN_REPORT
    __s32 value;           // Press/release, coordinate, delta
};

// SYN_REPORT marks end of logical event (multi-touch sync)
```

**Latency**: Interrupt → evdev queue: <0.1ms
**Buffering**: evdev buffers events until `SYN_REPORT`

### 3. EventHub (Native)

```cpp
// frameworks/native/services/inputflinger/EventHub.cpp
// Opens /dev/input/event* devices
// epoll_wait() for events
// Reads raw input_event structs
// Device configuration: key layouts, touch calibration, VID/PID mapping
```

**Work**: 
- Device discovery (udev monitoring)
- Key layout mapping (Linux code → Android keycode)
- Touch calibration (raw → display coordinates)
- **Thread**: `EventHub` thread (looper)

### 4. InputReader (Native)

```cpp
// frameworks/native/services/inputflinger/InputReader.cpp
// Thread: InputReaderThread
// Cooks raw events → typed Android events (KeyEvent, MotionEvent)

// Key processing:
// - Key maps (Linux → Android keycodes)
// - Keyboard layouts (language dependent)
//
// Touch processing:
// - MultiTouchInputMapper
// - Pointer tracking (slots)
// - Gesture detection (tap, swipe, pinch)
// - Tool type (finger, stylus, mouse, eraser)
//
// Joystick/Gamepad:
// - JoystickInputMapper
// - Axis mapping (AXIS_X, AXIS_Y, AXIS_Z, AXIS_RZ, AXIS_HAT_X/Y)
// - Button mapping (BUTTON_A, BUTTON_B, BUTTON_START, etc.)
// - Virtual key map for gamepad navigation
```

**Latency**: EventHub → InputReader: <1ms (same process, looper)
**Timestamp**: Preserves kernel `eventTime` (CLOCK_MONOTONIC)

### 5. InputDispatcher (Native)

```cpp
// frameworks/native/services/inputflinger/InputDispatcher.cpp
// Thread: InputDispatcherThread
// Dispatches MotionEvent/KeyEvent to target Window

// Key logic:
// 1. Find target window (focused, touchable region)
// 2. Check ANR timeout (5s default)
// 3. Batch touch events (streaming)
// 4. Inject to app via InputChannel (socketpair)

// Critical timeouts:
const nsecs_t APP_SWITCH_TIMEOUT = 500ms;
const nsecs_t STALE_EVENT_TIMEOUT = 10s;
const nsecs_t ANR_TIMEOUT = 5s;  // For input dispatch

// Touch streaming: allows queue-ahead of touch events
// Can add 1 frame latency if app busy
```

**Latency**: InputReader → InputDispatcher: <1ms
**Contention**: App not responding → events queued → latency increases

### 6. InputChannel / Binder IPC

```java
// App side: ViewRootImpl.WindowInputEventReceiver
// Native: InputChannel (socketpair) → Java InputEventReceiver
// Looper callback → InputEventReceiver.onInputEvent()

// Path:
// InputDispatcher → InputChannel (socket write)
//   → Kernel socket → App process socket read
//   → Looper callback → InputEventReceiver
//   → ViewRootImpl.enqueueInputEvent()
```

**Latency**: Process boundary crossing: 50-200μs (socketpair)

### 7. ViewRootImpl Input Pipeline (Java)

```java
// frameworks/base/core/java/android/view/ViewRootImpl.java

// Stage 1: InputStage (early processing)
ViewPostImeInputStage → NativePreImeInputStage → EarlyPostImeInputStage

// Stage 2: IME/Intercept
ImeInputStage → ViewPostImeInputStage

// Stage 3: Dispatch to View
// View.dispatchTouchEvent() / dispatchKeyEvent()
// ViewGroup.dispatchTouchEvent() → children

// Stage 4: Choreographer callback (if animation/input)
// Runs on UI thread at next VSYNC
```

**Key Latency Points**:
- **UI Thread Busy**: If `performTraversals()` running, input waits
- **Choreographer**: Touch events may align to VSYNC (0-16ms)
- **View Hierarchy Depth**: Deep hierarchy = more dispatch overhead

### 8. App Input Handling (OpenNOW)

```kotlin
// OpenNOW Game Activity
override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    // Capture timestamp immediately
    val captureTime = SystemClock.uptimeMillis()  // or event.eventTime
    // Convert to game coordinates
    // Encode input packet
    // Send via WebRTC data channel / UDP
    return true
}

// Gamepad:
override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
        // Process axes/buttons
        // Timestamp: event.eventTime (kernel monotonic)
    }
}
```

**Timestamp Propagation**:
```java
// event.eventTime = kernel CLOCK_MONOTONIC (ns)
// SystemClock.uptimeMillis() = same clock base
// For network: convert to microseconds since session start
long captureTimestampUs = (event.getEventTime() - sessionStartTime) * 1000;
```

## Input Latency Budget

| Stage | Typical Latency | Variance | Optimization |
|-------|-----------------|----------|--------------|
| Physical Device | 1-20ms | High (BT) | Prefer USB/BT 5.0+ |
| Kernel evdev | <0.1ms | Negligible | - |
| EventHub | <1ms | Low | - |
| InputReader | <1ms | Low | - |
| InputDispatcher | <1ms + queue | Medium | Keep UI responsive |
| IPC to App | 50-200μs | Low | - |
| ViewRootImpl Dispatch | 0-16ms | High (VSYNC) | Minimize UI work |
| App Processing | 0-2ms | Low | Offload encoding |
| Network Send | 0.1-1ms | Low | High priority socket |
| **Total (Touch)** | **4-40ms** | | |
| **Total (USB Gamepad)** | **3-25ms** | | |
| **Total (BT Gamepad)** | **10-50ms** | | |

## Critical Optimizations

### 1. Minimize ViewRootImpl Dispatch Latency
```kotlin
// Use SurfaceView for game - input directly on SurfaceView
surfaceView.setOnTouchListener { _, event ->
    // Direct handling, no View hierarchy traversal
    inputProcessor.processTouch(event)
    true
}

// For gamepad - override onGenericMotionEvent at Activity level
// Avoid View hierarchy entirely
```

### 2. Timestamp Accuracy
```java
// Use event.getEventTime() - kernel timestamp
// NOT System.currentTimeMillis() (wall clock, can jump)
// NOT System.nanoTime() (different clock domain)

// For network transmission:
long kernelTimeNs = event.getEventTime() * 1_000_000L;  // eventTime is ms
long sessionRelativeUs = kernelTimeNs - sessionStartKernelNs;
```

### 3. Input Encoding Efficiency
```kotlin
// WebRTC Input Protocol v3 (OpenNOW uses this)
// Minimal packet size, partial reliability

// Packet structure:
// [Header: 4B] [Timestamp: 8B] [InputData: variable]
// Timestamp = capture time in microseconds (monotonic)

// Send immediately - don't batch (adds latency)
datagramChannel.send(packet, remoteAddress)
```

### 4. Bluetooth Controller Optimization
```kotlin
// Bluetooth HID latency sources:
// 1. HID report interval (default 8ms = 125Hz)
// 2. BT link layer scheduling
// 3. Android BT stack processing

// Mitigations:
// - Use BT 5.0+ controllers (2M PHY, lower latency)
// - Controller firmware: reduce report interval to 1ms (1000Hz)
// - Android: prioritize BT HID traffic (vendor specific)
// - Consider USB OTG for competitive gaming
```

### 5. Touch Sampling Rate
```kotlin
// High touch sampling = lower latency
// Gaming phones: 240Hz, 360Hz, 480Hz touch sampling
// Standard: 120Hz

// Check capability:
val touchSamplingRate = display.getRefreshRate()  // May not reflect touch rate
// Better: InputDevice.getMotionRanges() for touchscreen
```

## Input-Output Latency Correlation

### End-to-End Measurement
```
Physical Press (high-speed camera LED)
    ↓
Kernel timestamp (eventTime)
    ↓
App capture timestamp
    ↓
Network send timestamp
    ↓
Server receive
    ↓
Game simulation
    ↓
Frame encode
    ↓
Network return
    ↓
Client decode
    ↓
Display photon (high-speed camera)
```

### Android-Specific Tools
```bash
# Input event latency
adb shell dumpsys inputflinger --latency

# Touch latency (requires instrumented app)
adb shell setprop debug.inputlatency.enabled 1

# ViewRootImpl dispatch tracing
adb shell setprop debug.viewrootimpl.profile true
```

## Gamepad Input Mapping

### Android Gamepad Axes/Buttons
```java
// Standard mapping (Generic.kl)
AXIS_X          → Left Stick X
AXIS_Y          → Left Stick Y  
AXIS_Z          → Right Stick X
AXIS_RZ         → Right Stick Y
AXIS_LTRIGGER   → Left Trigger
AXIS_RTRIGGER   → Right Trigger
AXIS_HAT_X      → D-Pad X
AXIS_HAT_Y      → D-Pad Y

BUTTON_A (96)   → Cross / A
BUTTON_B (97)   → Circle / B
BUTTON_X (99)   → Square / X
BUTTON_Y (100)  → Triangle / Y
BUTTON_L1 (102) → L1 / LB
BUTTON_R1 (103) → R1 / RB
BUTTON_START (108) → Start / Menu
BUTTON_SELECT (109) → Select / View
BUTTON_THUMBL (106) → L3
BUTTON_THUMBR (107) → R3
```

### Vendor-Specific Mappings
- **Xbox**: Standard mapping works
- **PlayStation (DS4/DS5)**: Standard via BT, touchpad = mouse
- **Nintendo Switch Pro**: Standard via BT
- **8BitDo**: Standard
- **Custom**: May need `InputDevice.getKeyLayout()` override

## Input Thread Architecture for OpenNOW Android

```kotlin
// Dedicated input thread (not UI thread)
class InputProcessor(private val networkSender: NetworkSender) {
    private val inputQueue = ArrayBlockingQueue<InputEvent>(1024)
    private val workerThread = Thread(this::processLoop).apply {
        priority = Thread.MAX_PRIORITY
        name = "OpenNOW-Input"
    }
    
    fun onTouchEvent(event: MotionEvent) {
        // Non-blocking enqueue
        inputQueue.offer(InputEvent.Touch(event))
    }
    
    fun onGamepadEvent(event: MotionEvent) {
        inputQueue.offer(InputEvent.Gamepad(event))
    }
    
    private fun processLoop() {
        while (running) {
            val event = inputQueue.take()
            val timestampUs = event.kernelTimestampUs
            val packet = encodeInputPacket(event, timestampUs)
            networkSender.send(packet)  // Non-blocking UDP send
        }
    }
}
```

## Known Issues & Workarounds

| Issue | Cause | Workaround |
|-------|-------|------------|
| BT gamepad latency spikes | BT coexistence (Wi-Fi + BT) | 5GHz Wi-Fi, disable Wi-Fi scan |
| Touch latency on scroll | View hierarchy processing | SurfaceView + direct touch handling |
| Gamepad not detected | Missing key layout / VID:PID | Add custom `.kl` file in `/vendor/usr/keylayout/` |
| Input drops under load | UI thread blocked | Offload all work from UI thread |
| Timestamp drift | Clock domain confusion | Use `event.eventTime` consistently |

## Testing Input Latency

### High-Speed Camera Method
1. LED on controller button press
2. High-speed camera (1000+ fps) filming screen + LED
3. Count frames between LED on and screen response
4. Each frame @ 1000fps = 1ms

### Software Timestamp Method
```kotlin
// Client sends: captureTimestampUs
// Server echoes: serverReceiveTimestampUs, serverSendTimestampUs  
// Client receives: clientReceiveTimestampUs

// One-way latency = serverReceive - capture
// Round-trip = clientReceive - capture
// Server processing = serverSend - serverReceive
```

## Recommendations for OpenNOW Android

1. **SurfaceView for game area** - Direct touch handling, no View hierarchy
2. **Dedicated input thread** - MAX_PRIORITY, big core affinity
3. **Kernel timestamps** - Use `event.eventTime` throughout
4. **Immediate send** - No batching, UDP with DSCP EF
5. **BT 5.0+ controllers** - Prefer USB for competitive
6. **Monitor input latency** - Telemetry: capture→send→ack round-trip
7. **Gamepad mapping** - Test major controllers, provide mapping UI
8. **Touch sampling** - Query device capability, advertise high rate

## References
- [Android Input Subsystem](https://source.android.com/docs/core/interaction/input)
- [InputReader/InputDispatcher Source](https://android.googlesource.com/platform/frameworks/native/+/master/services/inputflinger/)
- [ViewRootImpl Input Pipeline](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/ViewRootImpl.java)
- [WebRTC Input Protocol](https://github.com/moonlight-stream/moonlight-android)
- [Gamepad Mapping](https://developer.android.com/guide/topics/ui/input/keyboard)
- [High-Speed Camera Latency Testing](https://www.youtube.com/watch?v=eiAJKMkXYC0)