//! KCP parameter presets: balanced / aggressive; custom values are not supported.

use std::time::Duration;

use kcp::{KcpConfig, KcpNoDelayConfig};

/// Transport presets: balanced (default) and aggressive.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum KcpProfile {
    /// nodelay=0/interval=40/resend=0/nc=0: standard KCP, bandwidth-friendly.
    #[default]
    Balanced,
    /// nodelay=1/interval=10/resend=2/nc=1: aggressive mode, trading bandwidth for latency on lossy
    /// paths.
    Aggressive,
}

impl KcpProfile {
    /// Parses the configuration `profile` field; invalid values return None so the upper layer can warn
    /// and fall back to the default.
    pub fn parse(value: &str) -> Option<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "balance" | "balanced" => Some(Self::Balanced),
            "aggressive" => Some(Self::Aggressive),
            _ => None,
        }
    }

    fn no_delay(self) -> KcpNoDelayConfig {
        let mut cfg = KcpNoDelayConfig {
            nodelay: true,
            interval: 10,
            nc: true,
            ..KcpNoDelayConfig::default()
        };
        match self {
            Self::Balanced => cfg.resend = 2,
            Self::Aggressive => cfg.resend = 1,
        };
        cfg
    }
}

/// Builds the KCP configuration shared by both endpoints. Parameters are fixed presets on both sides;
/// custom values are not supported.
pub fn build_config(profile: KcpProfile) -> KcpConfig {
    KcpConfig {
        mtu: 1400,
        nodelay: profile.no_delay(),
        snd_wnd: 256,
        rcv_wnd: 256,
        stream: true,
        connect_timeout: Duration::from_secs(8),
        ..KcpConfig::default()
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    #[test]
    fn parse_profiles() {
        assert_eq!(KcpProfile::parse("balanced"), Some(KcpProfile::Balanced));
        assert_eq!(
            KcpProfile::parse(" Aggressive "),
            Some(KcpProfile::Aggressive)
        );
        assert_eq!(KcpProfile::parse("turbo"), None);
    }

    #[test]
    fn presets_match_adr() {
        let balanced = build_config(KcpProfile::Balanced);
        assert_eq!(balanced.mtu, 1400);
        assert!(balanced.stream, "Stream should be enabled");
        assert_eq!((balanced.snd_wnd, balanced.rcv_wnd), (256, 256));
        assert!(
            balanced.nodelay.nodelay,
            "MC is latency-sensitive: nodelay should be enabled"
        );
        assert_eq!(balanced.nodelay.interval, 10);
        assert_eq!(balanced.nodelay.resend, 2);
        assert!(balanced.nodelay.nc);

        let aggressive = build_config(KcpProfile::Aggressive);
        assert!(aggressive.nodelay.nodelay);
        assert_eq!(aggressive.nodelay.interval, 10);
        assert_eq!(aggressive.nodelay.resend, 1);
        assert!(aggressive.nodelay.nc);
    }
}
