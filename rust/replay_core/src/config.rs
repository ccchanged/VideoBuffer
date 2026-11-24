/// Replay recording configuration.
#[derive(Debug, Clone)]
pub struct Config {
    /// Maximum replay duration in microseconds.
    pub max_duration_us: i64,
    /// Keyframe interval hint in seconds (for informational purposes).
    pub keyframe_interval_sec: i32,
}

impl Config {
    pub fn new(max_duration_sec: i32) -> Self {
        Self {
            max_duration_us: max_duration_sec as i64 * 1_000_000,
            keyframe_interval_sec: 2,
        }
    }

    pub fn max_duration_ms(&self) -> i64 {
        self.max_duration_us / 1_000
    }

    pub fn update_duration(&mut self, max_duration_sec: i32) {
        self.max_duration_us = max_duration_sec as i64 * 1_000_000;
    }
}

impl Default for Config {
    fn default() -> Self {
        Self::new(60)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_config() {
        let cfg = Config::default();
        assert_eq!(cfg.max_duration_us, 60_000_000);
        assert_eq!(cfg.max_duration_ms(), 60_000);
    }

    #[test]
    fn test_update_duration() {
        let mut cfg = Config::new(30);
        assert_eq!(cfg.max_duration_us, 30_000_000);
        cfg.update_duration(90);
        assert_eq!(cfg.max_duration_us, 90_000_000);
    }
}
