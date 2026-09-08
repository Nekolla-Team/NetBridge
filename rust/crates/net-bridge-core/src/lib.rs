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
use tokio_util::sync::CancellationToken;
pub use transport::TransportKind;

use std::any::Any;
use std::collections::VecDeque;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicU32, AtomicUsize, Ordering};
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

/// 内部服务端状态常量。
pub const SERVER_STATE_RUNNING: u8 = 1;
pub const SERVER_STATE_STOPPED: u8 = 2;
pub const SERVER_STATE_FAILED: u8 = 3;

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
    pub cts: CancellationToken,
    /// 入站容量释放通知，唤醒读数据面。
    pub read_waker: Arc<tokio::sync::Notify>,
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
    pub remote_addr: std::sync::RwLock<Option<SocketAddr>>,
    /// 串行化连接事件以保证终态事件之后绝无非终态事件。
    event_lock: Mutex<()>,
}

impl ConnHandle {
    /// 构造句柄：读侧 channel 与空残留队列包装进共享锁。
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        state: Arc<AtomicU32>,
        to_java_rx: mpsc::Receiver<Bytes>,
        to_transport: mpsc::Sender<Command>,
        cts: CancellationToken,
        server_id: Option<u64>,
        server_count: Option<Arc<AtomicUsize>>,
        early_write: bool,
        remote_addr: Option<SocketAddr>,
    ) -> Self {
        Self {
            state,
            to_java: Arc::new(Mutex::new((to_java_rx, VecDeque::new()))),
            to_transport,
            cts,
            read_waker: Arc::new(tokio::sync::Notify::new()),
            server_id,
            server_count,
            early_write,
            write_blocked: Arc::new(AtomicBool::new(false)),
            outbound_bytes: Arc::new(AtomicUsize::new(0)),
            inbound_bytes: Arc::new(AtomicUsize::new(0)),
            terminal_sent: AtomicBool::new(false),
            remote_addr: std::sync::RwLock::new(remote_addr),
            event_lock: Mutex::new(()),
        }
    }

    /// 尝试发送非终态事件（DATA_AVAILABLE / WRITABLE）。如果终态已标记或已发出，则静默丢弃。
    /// 关键：释放锁后才调用 foreign callback（INV-4）。
    pub fn emit_non_terminal(
        &self,
        sink: &dyn EventSink,
        conn_id: u64,
        kind: u32,
        arg0: i64,
        arg1: i64,
    ) -> bool {
        let should_emit = {
            let _guard = match self.event_lock.lock() {
                Ok(g) => g,
                Err(p) => p.into_inner(),
            };
            !self.terminal_sent.load(Ordering::SeqCst)
        };
        if should_emit {
            sink.on_event(kind, conn_id, arg0, arg1);
            true
        } else {
            false
        }
    }

    /// 原子触发连接成功状态事件（CONNECTED）。如果终态已标记则静默丢弃。
    /// 关键：释放锁后才调用 foreign callback（INV-4）。
    pub fn emit_connected(&self, sink: &dyn EventSink, conn_id: u64) -> bool {
        let should_emit = {
            let _guard = match self.event_lock.lock() {
                Ok(g) => g,
                Err(p) => p.into_inner(),
            };
            if self.terminal_sent.load(Ordering::SeqCst) {
                false
            } else {
                self.state.store(STATE_CONNECTED, Ordering::SeqCst);
                true
            }
        };
        if should_emit {
            sink.on_event(
                crate::event::NB_EVENT_CONNECTION_STATE,
                conn_id,
                crate::event::abi_connection_state(STATE_CONNECTED) as i64,
                0,
            );
            true
        } else {
            false
        }
    }

    /// 原子触发终态事件（FAILED/CLOSED）恰好一次，并设立硬性事件屏障（杜绝任何后续事件）。
    /// 关键：释放锁后才调用 foreign callback（INV-4）。
    pub fn emit_terminal(
        &self,
        sink: &dyn EventSink,
        conn_id: u64,
        internal_state: u32,
        reason_code: i64,
    ) -> bool {
        let should_emit = {
            let _guard = match self.event_lock.lock() {
                Ok(g) => g,
                Err(p) => p.into_inner(),
            };
            if self.terminal_sent.swap(true, Ordering::SeqCst) {
                false
            } else {
                self.state.store(internal_state, Ordering::SeqCst);
                true
            }
        };
        if should_emit {
            sink.on_event(
                crate::event::NB_EVENT_CONNECTION_STATE,
                conn_id,
                crate::event::abi_connection_state(internal_state) as i64,
                reason_code,
            );
            true
        } else {
            false
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
    /// 服务端运行状态：RUNNING(1), STOPPED(2), FAILED(3)。
    pub state: Arc<AtomicU8>,
    /// 串行化 accept commit 与 stop 线性化点。
    pub commit_lock: Arc<Mutex<()>>,
    /// 停止完成同步通知。
    pub stopped_pair: Arc<(Mutex<bool>, std::sync::Condvar)>,
}

impl ServerHandle {
    pub fn is_running(&self) -> bool {
        self.state.load(Ordering::SeqCst) == SERVER_STATE_RUNNING
    }
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
