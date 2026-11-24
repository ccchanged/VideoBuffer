# Instant Replay

A lightweight Android screen recorder that continuously captures the screen into a rolling buffer and lets you save the last 60 seconds of footage with a single tap.

## Architecture

```
┌────────────────────────────────────┐
│          Kotlin Layer              │
│  (Android APIs, UI, Service)       │
│                                    │
│  MainActivity ← Jetpack Compose    │
│  RecordingService ← FG Service     │
│  ScreenRecorder ← MediaCodec       │
│  ReplayExporter ← MediaMuxer       │
│           │                        │
│      JNI  │  pushFrame / export    │
├───────────┼────────────────────────┤
│           ▼                        │
│        Rust Layer                  │
│  (Buffer, Segments, Export)        │
│                                    │
│  RollingBuffer ← VecDeque<Segment> │
│  Segment ← Vec<EncodedFrame>      │
│  ExportSnapshot ← cloned segments │
└────────────────────────────────────┘
```

**Kotlin** handles Android framework APIs (MediaProjection, VirtualDisplay, MediaCodec, MediaMuxer, Foreground Service, Compose UI).

**Rust** handles all core logic (rolling buffer management, segment lifecycle, timestamp tracking, export snapshots).

## Building

Building is handled by GitHub Actions. Push to `main` to trigger a build.

The workflow:
1. Runs Rust unit tests
2. Cross-compiles the Rust library for `arm64-v8a` using `cargo-ndk`
3. Builds the Android APK with Gradle
4. Uploads debug and release APKs as artifacts

## Settings

| Setting | Default | Options |
|---------|---------|---------|
| Resolution | 1280×720 | 640×360, 1280×720, 1920×1080 |
| Frame Rate | 24 fps | 15, 24, 30, 60 |
| Bitrate | 8 Mbps | 2, 4, 8, 12, 16 Mbps |
| Replay Duration | 60s | 15, 30, 60, 120, 300s |

## Requirements

- Android 10+ (API 29)
- ARM64 device

## Tech Stack

- **Rust 2024 edition** — recording engine & buffer management
- **Kotlin** — Android integration layer
- **Jetpack Compose** — UI
- **MediaCodec** — hardware H.264 encoding
- **MediaProjection** — screen capture
