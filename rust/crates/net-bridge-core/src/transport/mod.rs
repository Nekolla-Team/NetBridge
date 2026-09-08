//! Transport categories and implementation modules.

pub mod kcp;
pub mod quic;

use crate::error::Transport;

/// Transport implementation category.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportKind {
    /// Plaintext QUIC via quinn-plaintext.
    Quic,
    /// KCP via kcp-rs + FEC + smux multiplexing and flow control.
    Kcp,
}

impl TransportKind {
    /// Parses integer tags: 0 = QUIC, 1 = KCP; all other values are invalid and return None.
    pub fn from_jint(value: i32) -> Option<Self> {
        match value {
            0 => Some(Self::Quic),
            1 => Some(Self::Kcp),
            _ => None,
        }
    }

    /// Transport label used in error messages, corresponding to [`BridgeError`] Transport.
    pub fn label(self) -> Transport {
        match self {
            Self::Quic => Transport::Quic,
            Self::Kcp => Transport::Kcp,
        }
    }
}
