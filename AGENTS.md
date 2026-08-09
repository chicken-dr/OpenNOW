# AGENTS.md

## Core Priorities

1. Performance first.
2. Reliability first.
3. Keep behavior predictable under load and during failures (session restarts, reconnects, partial streams).

If a tradeoff is required, choose correctness and robustness over short-term convenience.

## Repository Layout

- `app/` is the Android application module. Main source in `app/src/main/java/com/closenow/`, resources in `app/src/main/res/`, manifest in `app/src/main/AndroidManifest.xml`.
- `docs/` contains Android architecture and research documentation.
- `.github/` contains workflows and templates.

## Platform Layout (multi-provider ready)

Cloud streaming providers live under `app/src/main/java/com/closenow/platforms/<id>/`. GeForce NOW (`gfn`) is the first provider:

- Network: `app/src/main/java/com/closenow/network/`
- Device: `app/src/main/java/com/closenow/device/`
- Decode: `app/src/main/java/com/closenow/decode/`
- Render: `app/src/main/java/com/closenow/render/`
- Session: `app/src/main/java/com/closenow/session/`
- Thermal: `app/src/main/java/com/closenow/thermal/`
- Input: `app/src/main/java/com/closenow/input/`
- Diagnostics: `app/src/main/java/com/closenow/diagnostics/`
- Threading: `app/src/main/java/com/closenow/threading/`

When adding another provider, create mirrors under `platforms/<id>/` — do not sprinkle provider protocol details into app shell or unrelated modules.

## Module Boundaries

- Shared GFN protocol details belong under `app/src/main/java/com/closenow/network/`. Prefer focused modules with one owner per concern (`SignalingClient` for WebSocket signaling, `WebRTCNetworkManager` for WebRTC, `WebRTCConfig` for WebRTC configuration, `NetworkOptimizer` for network tuning).
- Device/capability DTOs/helpers are split by concern under `app/src/main/java/com/closenow/device/` and `app/src/main/java/com/closenow/decode/`. Import from focused submodules when that keeps ownership clearer.
- Do not duplicate codec selection logic, device detection, thermal handling, or decoder configuration across feature files. Add to or extract a focused shared module first, then consume it from features.
- Keep feature files responsible for product flow and payload shape, not for re-declaring shared transport or device details.
- Preserve vendor-specific optimizer behavior, decoder fallback chains, and `QualityController` hysteresis semantics when refactoring.

## Android Process Boundaries

- Main thread (UI): Activity, SurfaceView, input event dispatch
- Network thread: WebRTC, signaling, UDP receive
- Decoder thread: MediaCodec async callbacks
- Render thread: Frame pacing, Choreographer callbacks
- Thermal thread: PowerManager thermal callbacks
- Diagnostics thread: Telemetry, Perfetto, dumpsys collection

## Shared Contracts

- `app/src/main/java/com/closenow/` is the contract boundary. Keep public interfaces stable unless the task explicitly requires a contract change.
- When changing shared types, update every caller in the same change and run type checks.
- Avoid using `any` or platform-specific types in shared contracts. Prefer serializable DTOs and explicit unions.

## Maintainability

Long term maintainability is a core priority. If you add new functionality, first check if there is shared logic that can be extracted to a separate module. Duplicate logic across multiple files is a code smell and should be avoided. Don't be afraid to change existing code. Don't take shortcuts by just adding local logic to solve a problem.

- Refactors should reduce ownership ambiguity: name the new owner module, move duplicated logic there, and keep behavior equivalent unless a behavior change is requested.
- Prefer small, typed helpers over broad `utils` modules. If a helper needs knowledge of one protocol or product area, keep it beside that area.
- Keep compatibility with existing persisted state unless a migration is explicitly part of the task.

## Localization

Edit only `app/src/main/res/values/strings.xml` as the source language file.

## Checks

- For Android/Kotlin changes, run the narrowest relevant check first, then `./gradlew lintDebug` and `./gradlew testDebugUnitTest` before finishing when practical.
- For decoder/session changes, also run `./gradlew connectedAndroidTest` if a device/emulator is available.
- Do not claim completion if the relevant acceptance check fails. Report the failing command and failure point.

## Build instructions

- All commands run from repository root.
- Dependencies managed with Gradle (`gradle.properties`, `settings.gradle.kts`, `app/build.gradle.kts`).
- Standard scripts: `./gradlew assembleDebug`, `./gradlew testDebugUnitTest`, `./gradlew lintDebug`.
- Android SDK 34, NDK r26+, Kotlin 1.9.22, AGP 8.4.0 required.
