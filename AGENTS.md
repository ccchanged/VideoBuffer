Project: Instant Replay — Minimal Android Replay Buffer

Build a completely new native Android application from scratch.

The application's sole purpose is to continuously record the Android screen and internal system audio into a rolling media buffer and allow the user to save the last 60 seconds of footage.

The project must prioritize simplicity, reliability, low resource consumption, and clean implementation.

1. Technology Stack

- Rust as the primary programming language.
- Rust stable edition 2024.
- Android NDK for native Android integration.
- Cargo for Rust dependency management.
- JNI for communication between Rust and Android.
- Kotlin only when absolutely necessary for Android framework integration.
- Gradle for Android project building.

Keep as much application logic as possible in Rust.

Do not implement the recording engine in Kotlin or Java.

Avoid unnecessary third-party dependencies.

2. Android Integration

Use Android's native recording APIs:

- MediaProjection for screen capture and user authorization.
- VirtualDisplay for providing screen frames.
- AudioPlaybackCaptureConfiguration and AudioRecord for internal system audio capture.
- MediaCodec for hardware-accelerated video encoding and audio encoding.
- MediaMuxer for MP4 generation.
- Foreground Service for maintaining recording while the application is in the background.

Android framework APIs should be accessed through a minimal Kotlin/JNI integration layer.

Do not implement a software video encoder.

3. Core Functionality

The application must:

- Continuously record the screen and internal system audio.
- Maintain a rolling buffer of the most recent 60 seconds.
- Provide a single "Save Replay" button.
- Save the previous 60 seconds when the button is pressed.
- Continue recording while a replay is being exported.
- Automatically discard outdated video and audio segments.
- Save completed replays as MP4 files with synchronized audio and video tracks.

The default replay duration must be 60 seconds.

4. Recording Settings

Provide only these configurable settings:

- Resolution.
- FPS.
- Video bitrate.
- Replay duration.

Use sensible defaults.

Automatically detect supported resolutions, frame rates, and hardware encoders.

Never offer unsupported configurations.

5. Recording Engine

Implement the recording engine in Rust.

Keep the architecture straightforward:

- Screen capture integration.
- System audio capture integration.
- Video and audio encoding integration.
- Rolling buffer management.
- Segment management.
- Timestamp and A/V synchronization management.
- Replay exporting.

Use a segment-based rolling buffer rather than storing individual frames as image files.

The buffer must retain enough encoded video and audio data to produce the requested replay duration.

Handle keyframes, codec configuration, timestamps, A/V sync alignment, and segment boundaries correctly.

Avoid unnecessary copying of encoded video and audio data.

6. User Interface

Keep the interface extremely minimal.

Main screen:

- Recording status.
- Current buffer duration.
- One large "Save Replay" button.
- Settings access.

Settings screen:

- Resolution.
- FPS.
- Bitrate.
- Replay duration.

No accounts, advertisements, analytics, cloud services, social features, or unnecessary menus.

7. Reliability

Pay particular attention to:

- MediaProjection lifecycle.
- Android foreground service requirements.
- Screen rotation and display-size changes.
- Recording interruptions.
- Encoder failures.
- Insufficient storage.
- Correct audio/video timestamps and monotonic clock synchronization.
- Apps blocking audio playback capture (gracefully handling silent/empty audio buffers without breaking sync).
- Corrupted or incomplete MP4 files.
- Exporting while recording continues.
- Memory and storage management.

Never report that recording is active if the recording engine has failed.

8. Performance

The application should:

- Prefer hardware video encoding.
- Minimize CPU and RAM consumption.
- Avoid unnecessary allocations.
- Avoid excessive disk writes.
- Minimize battery consumption.
- Avoid blocking the UI thread.
- Handle low-storage situations gracefully.

9. Development Instructions

Before writing code:

1. Examine the Android APIs required for this application.
2. Design the minimal architecture.
3. Explain the Kotlin/Rust boundary.
4. Explain how the rolling buffer will work.
5. Identify potential Android lifecycle limitations.

Then implement incrementally.

After each major component:

- Build the application.
- Check for compilation errors.
- Test the implementation.
- Fix problems before continuing.

Do not generate the entire application in one enormous implementation step.

Do not introduce unnecessary abstractions, frameworks, design patterns, or features.

Do not rewrite working components without a good reason.

10. Final Objective

Create a lightweight, native Android replay buffer written primarily in Rust.

It should do exactly one thing:

Continuously record the screen and system audio and save the last 60 seconds whenever the user presses a button.

Nothing more. Nothing less.

Reminder: GitHub workflow will do the compiling. i will not compile it myself. Make an YML file for the GitHub workflow to compile an app.
