//! Bridge-layer error types wrapped with thiserror.

use std::io;

use thiserror::Error;

/// Transport type identifier used in error messages.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Transport {
    Quic,
    Kcp,
}

impl std::fmt::Display for Transport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Quic => "quic",
            Self::Kcp => "kcp",
        })
    }
}

/// Unified bridge-layer error type.
#[derive(Debug, Error)]
pub enum BridgeError {
    #[error("tokio runtime unavailable")]
    RuntimeUnavailable,

    /// Port bind failed. If dual-stack fallback also fails, `source` contains both v6 and v4 causes.
    #[error("{transport} bind udp/{port}: {source}")]
    Bind {
        transport: Transport,
        port: u16,
        source: io::Error,
    },

    /// Staged setup failure such as listener construction, from_std, or local_addr.
    #[error("{transport} {stage}: {source}")]
    Setup {
        transport: Transport,
        stage: &'static str,
        source: io::Error,
    },

    /// Client DNS resolution failed.
    #[error("dns resolve failed: {host}:{port}: {source}")]
    Dns {
        host: String,
        port: u16,
        source: io::Error,
    },

    /// Connection establishment failed, such as handshake failure or unreachable peer. The source is
    /// boxed to support error types from different transport libraries, including types such as quinn
    /// ConnectError that do not convert to io::Error.
    #[error("{transport} connect to {addr}: {source}")]
    Connect {
        transport: Transport,
        addr: std::net::SocketAddr,
        source: Box<dyn std::error::Error + Send + Sync>,
    },

    /// Connection does not exist or is already closed.
    #[error("no such connection")]
    NoSuchConnection,

    /// Connection is closed, so the write is rejected.
    #[error("connection closed")]
    ConnectionClosed,

    /// Operation/startup timeout, such as the KCP listener startup window.
    #[error("operation timed out")]
    Timeout,

    /// Protocol/stream/smux/FEC data-plane setup or transport error.
    #[error("protocol error: {0}")]
    Protocol(String),

    /// Connection establishment was cancelled before completion.
    #[error("connection cancelled")]
    Cancelled,

    /// Internal error or panic.
    #[error("internal error: {0}")]
    Internal(String),

    /// The ID allocator is about to wrap; the context must fail and must never reuse 0.
    #[error("object id space exhausted")]
    IdOverflow,

    /// Invalid argument.
    #[error("invalid argument: {0}")]
    InvalidArgument(&'static str),
}

impl BridgeError {
    /// String form used at logging boundaries.
    pub fn message(&self) -> String {
        self.to_string()
    }

    /// Converts to the reason code for ABI CONNECTION_STATE FAILED.
    pub fn reason_code(&self) -> i64 {
        match self {
            Self::Dns { .. } => 1,
            Self::Bind { .. } | Self::Setup { .. } => 2,
            Self::Connect { .. } => 3,
            Self::Timeout => 4,
            Self::Protocol(_) => 5,
            Self::Cancelled | Self::ConnectionClosed | Self::NoSuchConnection => 6,
            Self::Internal(_)
            | Self::IdOverflow
            | Self::RuntimeUnavailable
            | Self::InvalidArgument(_) => 7,
        }
    }
}
