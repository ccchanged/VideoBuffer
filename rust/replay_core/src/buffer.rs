use std::collections::VecDeque;

use crate::config::Config;
use crate::segment::{EncodedFrame, Segment};

/// Recording engine state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum BufferState {
    /// Buffer created but no frames received yet.
    Idle = 0,
    /// Actively receiving encoded frames.
    Recording = 1,
    /// An export is in progress (recording continues).
    Exporting = 2,
    /// An error occurred; recording stopped.
    Error = 3,
}

/// Status snapshot returned to Kotlin.
pub struct BufferStatus {
    pub state: BufferState,
    pub duration_ms: i64,
    pub segment_count: usize,
    pub total_bytes: usize,
}

/// The core rolling buffer that stores recent encoded video segments and audio frames.
pub struct RollingBuffer {
    /// Configuration (max duration, etc.).
    config: Config,
    /// Sealed segments, oldest first.
    segments: VecDeque<Segment>,
    /// Segment currently accumulating frames.
    current_segment: Option<Segment>,
    /// Video codec-specific configuration data (SPS/PPS for H.264).
    codec_config: Option<Vec<u8>>,
    /// Audio frames in the rolling buffer, oldest first.
    audio_frames: VecDeque<EncodedFrame>,
    /// Audio codec-specific configuration data (AAC AudioSpecificConfig / csd-0).
    audio_codec_config: Option<Vec<u8>>,
    /// Current state.
    state: BufferState,
    /// Total encoded bytes across all sealed video segments.
    sealed_bytes: usize,
    /// Total encoded bytes of all stored audio frames.
    audio_bytes: usize,
    /// Timestamp of the last frame pushed (for liveness checking).
    last_frame_time_us: i64,
}

impl RollingBuffer {
    /// Create a new rolling buffer with the given configuration.
    pub fn new(config: Config) -> Self {
        Self {
            config,
            segments: VecDeque::new(),
            current_segment: None,
            codec_config: None,
            audio_frames: VecDeque::new(),
            audio_codec_config: None,
            state: BufferState::Idle,
            sealed_bytes: 0,
            audio_bytes: 0,
            last_frame_time_us: 0,
        }
    }

    /// Push an encoded video frame into the buffer.
    ///
    /// Codec config frames (SPS/PPS) are stored separately and not added to segments.
    /// Keyframes seal the current segment and start a new one.
    pub fn push_frame(&mut self, data: Vec<u8>, pts_us: i64, flags: u32) {
        let frame = EncodedFrame::new(data, pts_us, flags);

        // Codec config (SPS/PPS) — store separately, don't add to segments.
        if frame.is_codec_config() {
            self.codec_config = Some(frame.data);
            return;
        }

        // End of stream — ignore (we manage our own lifecycle).
        if frame.is_end_of_stream() {
            return;
        }

        if frame.is_keyframe() {
            // Seal the current segment and start a new one.
            self.seal_current_segment();
            self.current_segment = Some(Segment::new(frame));
            self.state = BufferState::Recording;
            self.last_frame_time_us = pts_us;
            self.prune();
        } else if let Some(seg) = self.current_segment.as_mut() {
            // Append to current segment.
            seg.push_frame(frame);
            self.state = BufferState::Recording;
            self.last_frame_time_us = pts_us;
        } else {
            // No current segment and frame is not a keyframe — we need a keyframe
            // to start. Drop this frame (happens at the very beginning before the
            // first keyframe arrives). Don't update state to Recording.
            log::trace!("Dropping non-keyframe before first keyframe");
        }
    }

    /// Push an encoded audio frame (AAC) into the rolling buffer.
    pub fn push_audio_frame(&mut self, data: Vec<u8>, pts_us: i64, flags: u32) {
        let frame = EncodedFrame::new(data, pts_us, flags);

        // Codec config (AAC AudioSpecificConfig) — store separately.
        if frame.is_codec_config() {
            self.audio_codec_config = Some(frame.data);
            return;
        }

        if frame.is_end_of_stream() {
            return;
        }

        self.audio_bytes += frame.byte_size();
        self.audio_frames.push_back(frame);

        self.prune_audio();
    }

    /// Seal the current segment and push it to the sealed queue.
    fn seal_current_segment(&mut self) {
        if let Some(seg) = self.current_segment.take() {
            if !seg.frames.is_empty() {
                self.sealed_bytes += seg.total_bytes;
                self.segments.push_back(seg);
            }
        }
    }

    /// Remove oldest sealed segments until total duration is within the limit,
    /// and prune outdated audio frames.
    fn prune(&mut self) {
        while self.sealed_duration_us() > self.config.max_duration_us {
            if let Some(removed) = self.segments.pop_front() {
                self.sealed_bytes -= removed.total_bytes;
            } else {
                break;
            }
        }
        self.prune_audio();
    }

    /// Discard audio frames older than the oldest buffered video segment.
    fn prune_audio(&mut self) {
        let cutoff = if let Some(first_seg) = self.segments.front() {
            first_seg.start_pts_us
        } else if let Some(cur) = &self.current_segment {
            cur.start_pts_us
        } else if let Some(last_audio) = self.audio_frames.back() {
            last_audio.pts_us - self.config.max_duration_us
        } else {
            0
        };

        while let Some(front) = self.audio_frames.front() {
            if front.pts_us < cutoff {
                if let Some(removed) = self.audio_frames.pop_front() {
                    self.audio_bytes -= removed.byte_size();
                }
            } else {
                break;
            }
        }
    }

    /// Total duration of all sealed segments in microseconds.
    fn sealed_duration_us(&self) -> i64 {
        if self.segments.is_empty() {
            return 0;
        }
        let first_start = self.segments.front().unwrap().start_pts_us;
        let last_end = self.segments.back().unwrap().end_pts_us;
        last_end - first_start
    }

    /// Total buffered duration including the current (unsealed) segment, in milliseconds.
    pub fn buffer_duration_ms(&self) -> i64 {
        let sealed = self.sealed_duration_us();
        let current = self
            .current_segment
            .as_ref()
            .map(|s| s.duration_us())
            .unwrap_or(0);

        let total = if !self.segments.is_empty() {
            if let Some(cur) = &self.current_segment {
                let first_start = self.segments.front().unwrap().start_pts_us;
                cur.end_pts_us - first_start
            } else {
                sealed
            }
        } else {
            current
        };

        total / 1_000
    }

    /// Get the current buffer status.
    pub fn status(&self) -> BufferStatus {
        let seg_count = self.segments.len()
            + if self.current_segment.is_some() { 1 } else { 0 };
        let total_bytes = self.sealed_bytes
            + self
                .current_segment
                .as_ref()
                .map(|s| s.total_bytes)
                .unwrap_or(0)
            + self.audio_bytes;
        BufferStatus {
            state: self.state,
            duration_ms: self.buffer_duration_ms(),
            segment_count: seg_count,
            total_bytes,
        }
    }

    /// Get the video codec config data (SPS/PPS).
    pub fn codec_config(&self) -> Option<&[u8]> {
        self.codec_config.as_deref()
    }

    /// Get the audio codec config data (AAC AudioSpecificConfig).
    pub fn audio_codec_config(&self) -> Option<&[u8]> {
        self.audio_codec_config.as_deref()
    }

    /// Get the current state.
    pub fn state(&self) -> BufferState {
        self.state
    }

    /// Set the state (used to mark errors, etc.).
    pub fn set_state(&mut self, state: BufferState) {
        self.state = state;
    }

    /// Update the max replay duration.
    pub fn update_config(&mut self, max_duration_sec: i32) {
        self.config.update_duration(max_duration_sec);
        self.prune();
    }

    /// Timestamp of the last frame pushed (microseconds).
    pub fn last_frame_time_us(&self) -> i64 {
        self.last_frame_time_us
    }

    /// Create a snapshot of the buffer for export.
    ///
    /// This clones all video segments and synchronized audio frames covering at most
    /// `duration_sec` seconds of the most recent footage. The buffer continues to accept new frames.
    pub fn snapshot_for_export(&mut self, duration_sec: i32) -> ExportSnapshot {
        // Seal the current segment so its frames are included in the snapshot.
        self.seal_current_segment();

        let duration_us = duration_sec as i64 * 1_000_000;
        let codec_config = self.codec_config.clone();
        let audio_codec_config = self.audio_codec_config.clone();

        if self.segments.is_empty() {
            return ExportSnapshot {
                segments: Vec::new(),
                codec_config,
                audio_frames: Vec::new(),
                audio_codec_config,
            };
        }

        // Find the cutoff: we want segments whose end_pts >= (latest_end - duration).
        let latest_end = self.segments.back().unwrap().end_pts_us;
        let cutoff = latest_end - duration_us;

        let segments: Vec<Segment> = self
            .segments
            .iter()
            .filter(|seg| seg.end_pts_us >= cutoff)
            .cloned()
            .collect();

        // Audio frames: align with the start of the first included video segment.
        let base_pts = segments.first().map(|s| s.start_pts_us).unwrap_or(0);
        let audio_frames: Vec<EncodedFrame> = self
            .audio_frames
            .iter()
            .filter(|frame| frame.pts_us >= base_pts && frame.pts_us <= latest_end)
            .cloned()
            .collect();

        if self.state == BufferState::Recording {
            self.state = BufferState::Exporting;
        }

        ExportSnapshot {
            segments,
            codec_config,
            audio_frames,
            audio_codec_config,
        }
    }

    /// Mark export as finished.
    pub fn finish_export(&mut self) {
        if self.state == BufferState::Exporting {
            self.state = BufferState::Recording;
        }
    }

    /// Clear the buffer entirely.
    pub fn clear(&mut self) {
        self.segments.clear();
        self.current_segment = None;
        self.sealed_bytes = 0;
        self.last_frame_time_us = 0;
        self.audio_frames.clear();
        self.audio_bytes = 0;
    }
}

/// A snapshot of buffer segments and audio frames for export.
pub struct ExportSnapshot {
    pub segments: Vec<Segment>,
    pub codec_config: Option<Vec<u8>>,
    pub audio_frames: Vec<EncodedFrame>,
    pub audio_codec_config: Option<Vec<u8>>,
}

impl ExportSnapshot {
    /// Total number of video frames across all segments.
    pub fn total_frames(&self) -> usize {
        self.segments.iter().map(|s| s.frame_count()).sum()
    }

    /// Total number of audio frames in the snapshot.
    pub fn total_audio_frames(&self) -> usize {
        self.audio_frames.len()
    }

    /// Get a video frame by flat index across all segments.
    ///
    /// Returns (data, adjusted_pts_us, flags) where pts is adjusted to start from 0.
    pub fn get_frame(&self, index: usize) -> Option<(&[u8], i64, u32)> {
        let base_pts = self
            .segments
            .first()
            .map(|s| s.start_pts_us)
            .unwrap_or(0);

        let mut remaining = index;
        for seg in &self.segments {
            if remaining < seg.frames.len() {
                let frame = &seg.frames[remaining];
                let adjusted_pts = frame.pts_us - base_pts;
                return Some((&frame.data, adjusted_pts, frame.flags));
            }
            remaining -= seg.frames.len();
        }
        None
    }

    /// Get an audio frame by flat index.
    ///
    /// Returns (data, adjusted_pts_us, flags) where pts is adjusted to start from the same base as video.
    pub fn get_audio_frame(&self, index: usize) -> Option<(&[u8], i64, u32)> {
        let base_pts = self
            .segments
            .first()
            .map(|s| s.start_pts_us)
            .unwrap_or(0);

        self.audio_frames.get(index).map(|frame| {
            let adjusted_pts = frame.pts_us - base_pts;
            (&frame.data[..], adjusted_pts, frame.flags)
        })
    }

    /// Get the video codec config data.
    pub fn codec_config(&self) -> Option<&[u8]> {
        self.codec_config.as_deref()
    }

    /// Get the audio codec config data.
    pub fn audio_codec_config(&self) -> Option<&[u8]> {
        self.audio_codec_config.as_deref()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::segment::flags::*;

    fn push_keyframe(buf: &mut RollingBuffer, pts_us: i64, size: usize) {
        buf.push_frame(vec![0xAA; size], pts_us, BUFFER_FLAG_KEY_FRAME);
    }

    fn push_regular(buf: &mut RollingBuffer, pts_us: i64, size: usize) {
        buf.push_frame(vec![0xBB; size], pts_us, 0);
    }

    fn push_audio(buf: &mut RollingBuffer, pts_us: i64, size: usize) {
        buf.push_audio_frame(vec![0xCC; size], pts_us, 0);
    }

    #[test]
    fn test_empty_buffer() {
        let buf = RollingBuffer::new(Config::new(60));
        assert_eq!(buf.buffer_duration_ms(), 0);
        assert_eq!(buf.state(), BufferState::Idle);
    }

    #[test]
    fn test_drops_frames_before_first_keyframe() {
        let mut buf = RollingBuffer::new(Config::new(60));
        push_regular(&mut buf, 0, 1000);
        push_regular(&mut buf, 33_333, 1000);
        assert_eq!(buf.state(), BufferState::Idle);
        assert_eq!(buf.buffer_duration_ms(), 0);
    }

    #[test]
    fn test_basic_recording() {
        let mut buf = RollingBuffer::new(Config::new(60));

        // Push codec config first.
        buf.push_frame(vec![0x00; 32], 0, BUFFER_FLAG_CODEC_CONFIG);
        assert!(buf.codec_config().is_some());

        // First keyframe at t=0.
        push_keyframe(&mut buf, 0, 5000);
        assert_eq!(buf.state(), BufferState::Recording);

        // Regular frames at ~30fps for 1 second.
        for i in 1..30 {
            push_regular(&mut buf, i * 33_333, 1000);
        }

        // Second keyframe at t=1s — seals first segment.
        push_keyframe(&mut buf, 1_000_000, 5000);
        assert_eq!(buf.status().segment_count, 2); // 1 sealed + 1 current
    }

    #[test]
    fn test_pruning() {
        let mut buf = RollingBuffer::new(Config::new(5)); // 5 second buffer

        // Push 10 seconds of keyframes (one per second).
        for i in 0..10 {
            push_keyframe(&mut buf, i * 1_000_000, 5000);
            for j in 1..30 {
                push_regular(&mut buf, i * 1_000_000 + j * 33_333, 1000);
            }
        }
        // One more keyframe to seal the last segment.
        push_keyframe(&mut buf, 10_000_000, 5000);

        // Should have pruned old segments. Duration should be <= 5 seconds.
        let status = buf.status();
        assert!(
            status.duration_ms <= 6000,
            "Duration {}ms exceeds expected max",
            status.duration_ms
        );
    }

    #[test]
    fn test_export_snapshot() {
        let mut buf = RollingBuffer::new(Config::new(60));

        buf.push_frame(vec![0x00; 32], 0, BUFFER_FLAG_CODEC_CONFIG);

        // Push 4 seconds of video: keyframe every second.
        for i in 0..4 {
            push_keyframe(&mut buf, i * 1_000_000, 5000);
            for j in 1..30 {
                push_regular(&mut buf, i * 1_000_000 + j * 33_333, 1000);
            }
        }
        // Seal the last segment.
        push_keyframe(&mut buf, 4_000_000, 5000);

        // Request 60s export (should get everything).
        let snap = buf.snapshot_for_export(60);
        assert!(snap.total_frames() > 0);
        assert!(snap.codec_config().is_some());

        // First frame should have adjusted PTS = 0.
        let (_, pts, flags) = snap.get_frame(0).unwrap();
        assert_eq!(pts, 0);
        assert!(flags & BUFFER_FLAG_KEY_FRAME != 0);

        // Last frame should have positive PTS.
        let last_idx = snap.total_frames() - 1;
        let (_, last_pts, _) = snap.get_frame(last_idx).unwrap();
        assert!(last_pts > 0);
    }

    #[test]
    fn test_export_while_recording() {
        let mut buf = RollingBuffer::new(Config::new(60));

        push_keyframe(&mut buf, 0, 5000);
        for i in 1..30 {
            push_regular(&mut buf, i * 33_333, 1000);
        }
        push_keyframe(&mut buf, 1_000_000, 5000);

        let snap = buf.snapshot_for_export(60);
        assert_eq!(buf.state(), BufferState::Exporting);

        // Continue recording while export is in progress.
        push_keyframe(&mut buf, 2_000_000, 5000);
        for i in 1..30 {
            push_regular(&mut buf, 2_000_000 + i * 33_333, 1000);
        }

        // Export snapshot is independent.
        assert!(snap.total_frames() > 0);

        buf.finish_export();
        assert_eq!(buf.state(), BufferState::Recording);
    }

    #[test]
    fn test_update_config_triggers_prune() {
        let mut buf = RollingBuffer::new(Config::new(60));

        // Push 10 seconds.
        for i in 0..10 {
            push_keyframe(&mut buf, i * 1_000_000, 5000);
        }
        push_keyframe(&mut buf, 10_000_000, 5000);

        // Now reduce to 3 seconds.
        buf.update_config(3);
        let status = buf.status();
        assert!(
            status.duration_ms <= 4000,
            "Duration {}ms exceeds expected max after config update",
            status.duration_ms
        );
    }

    #[test]
    fn test_audio_buffering_and_export() {
        let mut buf = RollingBuffer::new(Config::new(60));

        // Push video & audio codec config
        buf.push_frame(vec![0x00; 32], 0, BUFFER_FLAG_CODEC_CONFIG);
        buf.push_audio_frame(vec![0x12, 0x10], 0, BUFFER_FLAG_CODEC_CONFIG);
        assert!(buf.audio_codec_config().is_some());

        // Video starts at t=1,000,000 (1s)
        push_keyframe(&mut buf, 1_000_000, 5000);
        for i in 1..30 {
            push_regular(&mut buf, 1_000_000 + i * 33_333, 1000);
        }

        // Audio frames: some before video start (should be excluded), some during video
        push_audio(&mut buf, 500_000, 200); // before video start
        push_audio(&mut buf, 1_000_000, 200); // matches video start
        push_audio(&mut buf, 1_050_000, 200);
        push_audio(&mut buf, 1_100_000, 200);

        push_keyframe(&mut buf, 2_000_000, 5000);
        push_audio(&mut buf, 2_000_000, 200);

        let snap = buf.snapshot_for_export(60);
        assert_eq!(snap.total_audio_frames(), 4); // 500_000 is filtered out!
        assert!(snap.audio_codec_config().is_some());

        // Verify audio timestamp is aligned with video base_pts (1_000_000)
        let (_, audio_pts, _) = snap.get_audio_frame(0).unwrap();
        assert_eq!(audio_pts, 0); // 1_000_000 - 1_000_000 = 0

        let (_, audio_pts_1, _) = snap.get_audio_frame(1).unwrap();
        assert_eq!(audio_pts_1, 50_000); // 1_050_000 - 1_000_000 = 50_000
    }
}
