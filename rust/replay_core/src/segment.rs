/// MediaCodec buffer flags (mirrored from Android SDK).
pub mod flags {
    pub const BUFFER_FLAG_KEY_FRAME: u32 = 1;
    pub const BUFFER_FLAG_CODEC_CONFIG: u32 = 2;
    pub const BUFFER_FLAG_END_OF_STREAM: u32 = 4;
}

/// A single encoded video frame from MediaCodec.
#[derive(Clone)]
pub struct EncodedFrame {
    /// Raw encoded data (H.264 NAL units).
    pub data: Vec<u8>,
    /// Presentation timestamp in microseconds.
    pub pts_us: i64,
    /// MediaCodec buffer flags.
    pub flags: u32,
}

impl EncodedFrame {
    pub fn new(data: Vec<u8>, pts_us: i64, flags: u32) -> Self {
        Self { data, pts_us, flags }
    }

    pub fn is_keyframe(&self) -> bool {
        self.flags & flags::BUFFER_FLAG_KEY_FRAME != 0
    }

    pub fn is_codec_config(&self) -> bool {
        self.flags & flags::BUFFER_FLAG_CODEC_CONFIG != 0
    }

    pub fn is_end_of_stream(&self) -> bool {
        self.flags & flags::BUFFER_FLAG_END_OF_STREAM != 0
    }

    /// Size in bytes of the encoded data.
    pub fn byte_size(&self) -> usize {
        self.data.len()
    }
}

/// A group of consecutive encoded frames starting with a keyframe.
///
/// Segments are the unit of the rolling buffer. Each segment begins at an IDR
/// frame and contains all subsequent frames until the next IDR (exclusive).
#[derive(Clone)]
pub struct Segment {
    /// Frames in this segment. The first frame is always a keyframe.
    pub frames: Vec<EncodedFrame>,
    /// PTS of the first frame (keyframe).
    pub start_pts_us: i64,
    /// PTS of the last frame.
    pub end_pts_us: i64,
    /// Total encoded bytes in this segment.
    pub total_bytes: usize,
}

impl Segment {
    /// Create a new segment starting with the given keyframe.
    pub fn new(keyframe: EncodedFrame) -> Self {
        let pts = keyframe.pts_us;
        let bytes = keyframe.byte_size();
        Self {
            frames: vec![keyframe],
            start_pts_us: pts,
            end_pts_us: pts,
            total_bytes: bytes,
        }
    }

    /// Append a frame to this segment.
    pub fn push_frame(&mut self, frame: EncodedFrame) {
        self.end_pts_us = frame.pts_us;
        self.total_bytes += frame.byte_size();
        self.frames.push(frame);
    }

    /// Duration of this segment in microseconds.
    pub fn duration_us(&self) -> i64 {
        if self.frames.len() <= 1 {
            return 0;
        }
        self.end_pts_us - self.start_pts_us
    }

    /// Number of frames in this segment.
    pub fn frame_count(&self) -> usize {
        self.frames.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_frame(pts_us: i64, flags: u32, size: usize) -> EncodedFrame {
        EncodedFrame::new(vec![0u8; size], pts_us, flags)
    }

    #[test]
    fn test_frame_flags() {
        let kf = make_frame(0, flags::BUFFER_FLAG_KEY_FRAME, 100);
        assert!(kf.is_keyframe());
        assert!(!kf.is_codec_config());

        let cfg = make_frame(0, flags::BUFFER_FLAG_CODEC_CONFIG, 50);
        assert!(cfg.is_codec_config());
        assert!(!cfg.is_keyframe());
    }

    #[test]
    fn test_segment_creation() {
        let kf = make_frame(1_000_000, flags::BUFFER_FLAG_KEY_FRAME, 5000);
        let seg = Segment::new(kf);
        assert_eq!(seg.frame_count(), 1);
        assert_eq!(seg.start_pts_us, 1_000_000);
        assert_eq!(seg.duration_us(), 0);
        assert_eq!(seg.total_bytes, 5000);
    }

    #[test]
    fn test_segment_accumulation() {
        let kf = make_frame(0, flags::BUFFER_FLAG_KEY_FRAME, 5000);
        let mut seg = Segment::new(kf);

        for i in 1..30 {
            let f = make_frame(i * 33_333, 0, 1000); // ~30fps
            seg.push_frame(f);
        }

        assert_eq!(seg.frame_count(), 30);
        assert_eq!(seg.start_pts_us, 0);
        assert_eq!(seg.end_pts_us, 29 * 33_333);
        assert_eq!(seg.total_bytes, 5000 + 29 * 1000);
        assert!(seg.duration_us() > 900_000); // ~0.97s
    }
}
