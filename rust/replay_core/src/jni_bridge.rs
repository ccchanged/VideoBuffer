use std::sync::Mutex;

use jni::objects::{JByteArray, JClass, JLongArray};
use jni::sys::{jbyteArray, jint, jintArray, jlong};
use jni::JNIEnv;

use crate::buffer::{ExportSnapshot, RollingBuffer};
use crate::config::Config;

/// Global state: the rolling buffer and active export snapshot.
///
/// Using a static Mutex is the simplest correct approach for JNI. The Kotlin side
/// ensures that calls are properly sequenced (init before use, destroy at end).
static BUFFER: Mutex<Option<RollingBuffer>> = Mutex::new(None);
static EXPORT: Mutex<Option<ExportSnapshot>> = Mutex::new(None);

// ---------------------------------------------------------------------------
// Helper
// ---------------------------------------------------------------------------

fn with_buffer<F, R>(f: F) -> R
where
    F: FnOnce(&mut RollingBuffer) -> R,
    R: Default,
{
    let mut guard = BUFFER.lock().unwrap();
    match guard.as_mut() {
        Some(buf) => f(buf),
        None => {
            log::error!("RollingBuffer not initialized");
            R::default()
        }
    }
}

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------

/// Initialize the native rolling buffer.
///
/// Must be called once before any other native method.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeInit(
    _env: JNIEnv,
    _class: JClass,
    max_duration_sec: jint,
) {
    // Initialize Android logger on first call.
    #[cfg(target_os = "android")]
    {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("ReplayCore"),
        );
    }

    let config = Config::new(max_duration_sec);
    log::info!(
        "Initializing RollingBuffer: max_duration={}s",
        max_duration_sec
    );
    let mut guard = BUFFER.lock().unwrap();
    *guard = Some(RollingBuffer::new(config));
}

/// Push an encoded video frame into the rolling buffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativePushFrame(
    env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    presentation_time_us: jlong,
    flags: jint,
) {
    // Convert Java byte array to Rust Vec<u8>.
    let data_vec = match env.convert_byte_array(&data) {
        Ok(v) => v,
        Err(e) => {
            log::error!("Failed to convert byte array: {}", e);
            return;
        }
    };

    with_buffer(|buf| {
        buf.push_frame(data_vec, presentation_time_us, flags as u32);
    });
}

/// Push an encoded audio frame (AAC) into the rolling buffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativePushAudioFrame(
    env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    presentation_time_us: jlong,
    flags: jint,
) {
    let data_vec = match env.convert_byte_array(&data) {
        Ok(v) => v,
        Err(e) => {
            log::error!("Failed to convert audio byte array: {}", e);
            return;
        }
    };

    with_buffer(|buf| {
        buf.push_audio_frame(data_vec, presentation_time_us, flags as u32);
    });
}

/// Get the current buffer duration in milliseconds.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeGetBufferDurationMs(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    with_buffer(|buf| buf.buffer_duration_ms())
}

/// Get buffer status as an int array: [state, durationMs, segmentCount, totalBytes].
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeGetBufferStatus(
    env: JNIEnv,
    _class: JClass,
) -> jintArray {
    let status = with_buffer(|buf| {
        let s = buf.status();
        [
            s.state as i32,
            s.duration_ms as i32,
            s.segment_count as i32,
            s.total_bytes.min(i32::MAX as usize) as i32,
        ]
    });

    let result = match env.new_int_array(4) {
        Ok(arr) => arr,
        Err(e) => {
            log::error!("Failed to create int array: {}", e);
            return std::ptr::null_mut();
        }
    };
    if let Err(e) = env.set_int_array_region(&result, 0, &status) {
        log::error!("Failed to set int array region: {}", e);
        return std::ptr::null_mut();
    }
    result.into_raw()
}

/// Begin an export: snapshot the buffer and return the number of video frames.
///
/// Returns -1 if the buffer is empty or not initialized.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeBeginExport(
    _env: JNIEnv,
    _class: JClass,
    duration_sec: jint,
) -> jint {
    let mut guard = BUFFER.lock().unwrap();
    let buf = match guard.as_mut() {
        Some(b) => b,
        None => {
            log::error!("RollingBuffer not initialized");
            return -1;
        }
    };

    let snapshot = buf.snapshot_for_export(duration_sec);
    let frame_count = snapshot.total_frames();
    if frame_count == 0 {
        log::warn!("Export requested but buffer has no video frames");
        return -1;
    }

    log::info!(
        "Export started: {} video frames, {} audio frames",
        frame_count,
        snapshot.total_audio_frames()
    );
    let mut export_guard = EXPORT.lock().unwrap();
    *export_guard = Some(snapshot);

    frame_count as jint
}

/// Get a single export video frame by index.
///
/// `out_info` is a long array of length 2: [presentationTimeUs, flags].
/// Returns the frame data as a byte array, or null if index is out of bounds.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeGetExportFrame(
    env: JNIEnv,
    _class: JClass,
    index: jint,
    out_info: JLongArray,
) -> jbyteArray {
    let export_guard = EXPORT.lock().unwrap();
    let snapshot = match export_guard.as_ref() {
        Some(s) => s,
        None => {
            log::error!("No active export");
            return std::ptr::null_mut();
        }
    };

    match snapshot.get_frame(index as usize) {
        Some((data, pts_us, flags)) => {
            let info = [pts_us, flags as i64];
            if let Err(e) = env.set_long_array_region(&out_info, 0, &info) {
                log::error!("Failed to write out_info: {}", e);
                return std::ptr::null_mut();
            }

            match env.byte_array_from_slice(data) {
                Ok(arr) => arr.into_raw(),
                Err(e) => {
                    log::error!("Failed to create byte array: {}", e);
                    std::ptr::null_mut()
                }
            }
        }
        None => {
            log::warn!("Export frame index {} out of bounds", index);
            std::ptr::null_mut()
        }
    }
}

/// Get the video codec config data (SPS/PPS) for the export.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeGetExportCodecConfig(
    env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    let export_guard = EXPORT.lock().unwrap();
    let snapshot = match export_guard.as_ref() {
        Some(s) => s,
        None => {
            log::error!("No active export");
            return std::ptr::null_mut();
        }
    };

    match snapshot.codec_config() {
        Some(data) => match env.byte_array_from_slice(data) {
            Ok(arr) => arr.into_raw(),
            Err(e) => {
                log::error!("Failed to create codec config byte array: {}", e);
                std::ptr::null_mut()
            }
        },
        None => {
            log::warn!("No codec config available");
            std::ptr::null_mut()
        }
    }
}

/// Get the number of audio frames in the active export snapshot.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeGetExportAudioFrameCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let export_guard = EXPORT.lock().unwrap();
    match export_guard.as_ref() {
        Some(s) => s.total_audio_frames() as jint,
        None => 0,
    }
}

/// Get a single export audio frame by index.
///
/// `out_info` is a long array of length 2: [presentationTimeUs, flags].
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeGetExportAudioFrame(
    env: JNIEnv,
    _class: JClass,
    index: jint,
    out_info: JLongArray,
) -> jbyteArray {
    let export_guard = EXPORT.lock().unwrap();
    let snapshot = match export_guard.as_ref() {
        Some(s) => s,
        None => {
            log::error!("No active export");
            return std::ptr::null_mut();
        }
    };

    match snapshot.get_audio_frame(index as usize) {
        Some((data, pts_us, flags)) => {
            let info = [pts_us, flags as i64];
            if let Err(e) = env.set_long_array_region(&out_info, 0, &info) {
                log::error!("Failed to write audio out_info: {}", e);
                return std::ptr::null_mut();
            }

            match env.byte_array_from_slice(data) {
                Ok(arr) => arr.into_raw(),
                Err(e) => {
                    log::error!("Failed to create audio byte array: {}", e);
                    std::ptr::null_mut()
                }
            }
        }
        None => {
            log::warn!("Export audio frame index {} out of bounds", index);
            std::ptr::null_mut()
        }
    }
}

/// Get the audio codec config data (AAC AudioSpecificConfig / csd-0).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeGetExportAudioCodecConfig(
    env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    let export_guard = EXPORT.lock().unwrap();
    let snapshot = match export_guard.as_ref() {
        Some(s) => s,
        None => {
            log::error!("No active export");
            return std::ptr::null_mut();
        }
    };

    match snapshot.audio_codec_config() {
        Some(data) => match env.byte_array_from_slice(data) {
            Ok(arr) => arr.into_raw(),
            Err(e) => {
                log::error!("Failed to create audio codec config byte array: {}", e);
                std::ptr::null_mut()
            }
        },
        None => std::ptr::null_mut(),
    }
}

/// End the current export and free the snapshot.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeEndExport(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut export_guard = EXPORT.lock().unwrap();
    *export_guard = None;

    with_buffer(|buf| {
        buf.finish_export();
    });

    log::info!("Export completed");
}

/// Update the maximum replay duration.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeUpdateConfig(
    _env: JNIEnv,
    _class: JClass,
    max_duration_sec: jint,
) {
    with_buffer(|buf| {
        buf.update_config(max_duration_sec);
    });
    log::info!("Config updated: max_duration={}s", max_duration_sec);
}

/// Destroy the native buffer and free all memory.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut export_guard = EXPORT.lock().unwrap();
    *export_guard = None;
    drop(export_guard);

    let mut guard = BUFFER.lock().unwrap();
    *guard = None;

    log::info!("Native buffer destroyed");
}

/// Set the native log level. 0=Off, 1=Error, 2=Warn, 3=Info, 4=Debug, 5=Trace.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_instantreplay_bridge_NativeBridge_nativeSetLogLevel(
    _env: JNIEnv,
    _class: JClass,
    level: jint,
) {
    let filter = match level {
        0 => log::LevelFilter::Off,
        1 => log::LevelFilter::Error,
        2 => log::LevelFilter::Warn,
        3 => log::LevelFilter::Info,
        4 => log::LevelFilter::Debug,
        _ => log::LevelFilter::Trace,
    };
    log::set_max_level(filter);
    log::info!("Native log level set to: {:?}", filter);
}
