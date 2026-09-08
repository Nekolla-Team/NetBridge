//! KCP 参数预设：balanced / aggressive 二档，不支持自定义。

use std::time::Duration;

use kcp::{KcpConfig, KcpNoDelayConfig};

/// 传输预设：平衡（默认）与激进。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum KcpProfile {
    /// nodelay=0/interval=40/resend=0/nc=0：标准 KCP，带宽友好。
    #[default]
    Balanced,
    /// nodelay=1/interval=10/resend=2/nc=1：极速模式，高丢包链路换延迟。
    Aggressive,
}

impl KcpProfile {
    /// 解析配置中的 `profile` 字段；非法值返回 None（上层告警回退默认）。
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

/// 构建两端共用的 KCP 配置。参数为两端锁定的预设值，不支持自定义。
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
        assert!(!balanced.stream, "Stream should disabled");
        assert_eq!((balanced.snd_wnd, balanced.rcv_wnd), (256, 256));
        assert!(balanced.nodelay.nodelay, "MC 延迟敏感：nodelay 开启");
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
