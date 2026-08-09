# Contributing to CloseNOW

Thanks for contributing to CloseNOW - the Android-first cloud gaming client for GeForce NOW.

## Project Layout

- Android application: `app/` (Kotlin + Gradle)
- Documentation: `docs/` (Android architecture and research)

## Local Setup

```bash
git clone https://github.com/chicken-dr/OpenNOW.git
cd OpenNOW
./gradlew assembleDebug
```

## Build and Checks

```bash
# Debug build
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Run lint
./gradlew lintDebug

# Release build
./gradlew assembleRelease
```

## Pull Requests

1. Create a feature branch
2. Keep commits focused and clear
3. Ensure `testDebugUnitTest` and `lintDebug` pass locally
4. Open a PR with a concise summary
