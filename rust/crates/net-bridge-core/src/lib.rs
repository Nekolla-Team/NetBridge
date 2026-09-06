//! net-bridge-core: Pure Rust transport core for QUIC and KCP.
#![forbid(unsafe_code)]

pub mod context;
pub mod error;
pub mod event;
pub mod socket_util;
pub mod transport;

#[cfg(test)]
mod tests;

pub use context::NativeContext;
pub use error::BridgeError;
pub use event::{EventSink, NoopEventSink};
pub use transport::TransportKind;

use std::any::Any;
use std::collections::VecDeque;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use bytes::Bytes;
use tokio::sync::mpsc;

/// 发往传输写任务的通用控制命令。
#[derive(Debug)]
pub enum Command {
    Write(Bytes),
    Close,
}

/// 内部连接状态常量（core 内部值；对外暴露时经 `abi_connection_state` 映射为 ABI 值 1..4）。
pub const STATE_CONNECTING: u32 = 0;
pub const STATE_CONNECTED: u32 = 1;
pub const STATE_CLOSED: u32 = 2;
pub const STATE_FAILED: u32 = 3;

/// 默认出站/入站单连接字节预算上限 (4 MiB)
pub const DEFAULT_MAX_BUFFERED_BYTES: usize = 4 * 1024 * 1024;
/// 单次 I/O chunk 最大上限 (64 KiB)
pub const MAX_IO_CHUNK: usize = 64 * 1024;

/// 单条连接的句柄。
pub struct ConnHandle {
    pub state: Arc<AtomicU32>,
    /// Java 读侧 chunk 队列 + 未取走的残留块。Bytes 共享视图切分零拷贝；
    /// Arc 化：读路径克隆后即可释放 DashMap guard。
    pub to_java: Arc<Mutex<(mpsc::Receiver<Bytes>, VecDeque<Bytes>)>>,
    pub to_transport: mpsc::Sender<Command>,
    pub cancel_tx: tokio::sync::watch::Sender<bool>,
    pub server_id: Option<u64>,
    /// 服务端连接的每实例活跃计数（客户端连接为 None）；remove 时递减。
    pub server_count: Option<Arc<AtomicUsize>>,
    /// 连接期即可写入：KCP 客户端握手未完成时允许写入（命令先入 channel，
    /// 握手完成后立即下发；kcp-rs 内建握手，不依赖首帧判定）。QUIC 客户端为 false。
    pub early_write: bool,
    /// 写队列满 / byte budget 耗尽 → 传输任务消费出空间后清零并 edge-trigger 发 WRITABLE。
    pub write_blocked: Arc<AtomicBool>,
    /// 在途出站字节数（FFI write 增加，传输任务消费后减少）。
    pub outbound_bytes: Arc<AtomicUsize>,
    /// 在途入站字节数（传输 reader 读入增加，Java read 消费后减少）。
    pub inbound_bytes: Arc<AtomicUsize>,
    /// 终态事件（FAILED/CLOSED）只发一次的闸。
    pub terminal_sent: AtomicBool,
    /// 连接真实对端地址（Java 侧 ban/限速等 IP 管控）。
    pub remote_addr: Option<SocketAddr>,
}

impl ConnHandle {
    /// 构造句柄：读侧 channel 与空残留队列包装进共享锁。
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        state: Arc<AtomicU32>,
        to_java_rx: mpsc::Receiver<Bytes>,
        to_transport: mpsc::Sender<Command>,
        cancel_tx: tokio::sync::watch::Sender<bool>,
        server_id: Option<u64>,
        server_count: Option<Arc<AtomicUsize>>,
        early_write: bool,
        remote_addr: Option<SocketAddr>,
    ) -> Self {
        Self {
            state,
            to_java: Arc::new(Mutex::new((to_java_rx, VecDeque::new()))),
            to_transport,
            cancel_tx,
            server_id,
            server_count,
            early_write,
            write_blocked: Arc::new(AtomicBool::new(false)),
            outbound_bytes: Arc::new(AtomicUsize::new(0)),
            inbound_bytes: Arc::new(AtomicUsize::new(0)),
            terminal_sent: AtomicBool::new(false),
            remote_addr,
        }
    }
}

/// 注册表中的服务端句柄。endpoint 为多态传输端点；计数与上限为本实例私有。
pub struct ServerHandle {
    pub endpoint: TransportEndpoint,
    pub port: u16,
    /// 本实例活跃连接上限（accept 阶段超限即丢弃）。
    pub max_connections: usize,
    /// 本实例活跃连接数（独立于其他 server 实例）。
    pub conn_count: Arc<AtomicUsize>,
    /// 服务端运行状态：true 表示正在接收连接，stop_server 时设为 false。
    pub is_running: Arc<AtomicBool>,
}

/// 传输端点：`stop_server` 按此分支关闭。
pub enum TransportEndpoint {
    Quic(tokio::sync::mpsc::Sender<()>),
    /// KCP 停止触发器：发送即令 accept 任务退出并 Drop listener
    /// （中止任务、关闭 socket）。listener 本体留在 accept 任务内。
    Kcp(tokio::sync::mpsc::Sender<()>),
}

/// 错误即时上报：stderr 由 Minecraft 启动器重定向进 logs/latest.log。
pub fn report_error(msg: String) {
    eprintln!("[net-bridge-native] error: {msg}");
}

/// 尝试把一条新连接计入服务端实例活跃数；超限回滚并拒绝。
pub(crate) fn try_admit(count: &AtomicUsize, max: usize) -> bool {
    let prev = count.fetch_add(1, Ordering::Relaxed);
    if prev >= max {
        count.fetch_sub(1, Ordering::Relaxed);
        return false;
    }
    true
}

/// 提取 panic payload 的可读信息；非字符串 payload 记占位。
fn describe_panic(payload: &Box<dyn Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic payload".to_string()
    }
}
