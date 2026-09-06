//! 桥接层错误类型（thiserror 封装）。

use std::io;

use thiserror::Error;

/// 传输类型标识（错误信息用）。
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

/// 桥接层统一错误。
#[derive(Debug, Error)]
pub enum BridgeError {
    #[error("tokio runtime unavailable")]
    RuntimeUnavailable,

    /// 端口绑定失败。双栈回退后仍失败时，`source` 信息含 v6/v4 双原因。
    #[error("{transport} bind udp/{port}: {source}")]
    Bind {
        transport: Transport,
        port: u16,
        source: io::Error,
    },

    /// 分阶段建立失败（listener 构造、from_std、local_addr 等）。
    #[error("{transport} {stage}: {source}")]
    Setup {
        transport: Transport,
        stage: &'static str,
        source: io::Error,
    },

    /// 客户端 DNS 解析失败。
    #[error("dns resolve failed: {host}:{port}: {source}")]
    Dns {
        host: String,
        port: u16,
        source: io::Error,
    },

    /// 连接建立失败（握手/对端不可达等）。source 装箱以兼容各传输
    /// 库的错误类型（quinn ConnectError 等无 io::Error 转换）。
    #[error("{transport} connect to {addr}: {source}")]
    Connect {
        transport: Transport,
        addr: std::net::SocketAddr,
        source: Box<dyn std::error::Error + Send + Sync>,
    },

    /// 连接不存在或已关闭。
    #[error("no such connection")]
    NoSuchConnection,

    /// 连接已关闭，写入被拒绝。
    #[error("connection closed")]
    ConnectionClosed,

    /// 操作/启动超时（如 KCP listener 启动窗口）。
    #[error("operation timed out")]
    Timeout,

    /// 协议/流/smux/fec 数据面建立或传输错误。
    #[error("protocol error: {0}")]
    Protocol(String),

    /// 连接建立在完成前被取消。
    #[error("connection cancelled")]
    Cancelled,

    /// 内部错误或 panic。
    #[error("internal error: {0}")]
    Internal(String),

    /// id 分配器即将回绕：context 必须失败，禁止复用 0。
    #[error("object id space exhausted")]
    IdOverflow,

    /// 参数非法。
    #[error("invalid argument: {0}")]
    InvalidArgument(&'static str),
}

impl BridgeError {
    /// 日志边界的字符串形态。
    pub fn message(&self) -> String {
        self.to_string()
    }

    /// 转换为 ABI CONNECTION_STATE FAILED 的原因码。
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
