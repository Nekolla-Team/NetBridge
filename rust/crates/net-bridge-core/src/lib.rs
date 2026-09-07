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
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use bytes::Bytes;
use tokio::sync::mpsc;

/// Generic control command sent to a transport write task.
#[derive(Debug)]
pub enum Command {
    Write(Bytes),
    Close,
}

/// Internal connection-state constants; core values are mapped to ABI values 1..4 through
/// `abi_connection_state` when exposed.
pub const STATE_CONNECTING: u32 = 0;
pub const STATE_CONNECTED: u32 = 1;
pub const STATE_CLOSED: u32 = 2;
pub const STATE_FAILED: u32 = 3;

/// Internal server-state constants.
pub const SERVER_STATE_RUNNING: u8 = 1;
pub const SERVER_STATE_STOPPED: u8 = 2;
pub const SERVER_STATE_FAILED: u8 = 3;

/// Default per-connection outbound/inbound byte-budget limit (4 MiB)
pub const DEFAULT_MAX_BUFFERED_BYTES: usize = 4 * 1024 * 1024;
/// Maximum size of a single I/O chunk (64 KiB)
pub const MAX_IO_CHUNK: usize = 64 * 1024;

/// Handle for a single connection.
pub struct ConnHandle {
    pub state: Arc<AtomicU32>,
    /// Java read-side chunk queue plus an unconsumed remainder. Bytes shared views are sliced zero-copy;
    /// stored behind Arc so the read path can clone it and release the DashMap guard immediately.
    pub to_java: Arc<Mutex<(mpsc::Receiver<Bytes>, VecDeque<Bytes>)>>,
    pub to_transport: mpsc::Sender<Command>,
    pub cancel_tx: tokio::sync::watch::Sender<bool>,
    /// Notification that inbound capacity was released, waking the read data plane.
    pub read_waker: Arc<tokio::sync::Notify>,
    pub server_id: Option<u64>,
    /// Per-instance active count for server connections (None for client connections); decremented on
    /// removal.
    pub server_count: Option<Arc<AtomicUsize>>,
    /// Writes are allowed during connection establishment: a KCP client may write before its handshake
    /// completes (the command enters the channel first, then is sent immediately after the handshake;
    /// kcp-rs has a built-in handshake and does not depend on first-frame detection). False for QUIC
    /// clients.
    pub early_write: bool,
    /// Write queue full / byte budget exhausted: clear the blocked state after the transport task frees
    /// capacity and edge-trigger WRITABLE.
    pub write_blocked: Arc<AtomicBool>,
    /// In-flight outbound byte count, increased by FFI write and decreased when the transport task
    /// consumes data.
    pub outbound_bytes: Arc<AtomicUsize>,
    /// In-flight inbound byte count, increased by the transport reader and decreased by Java reads.
    pub inbound_bytes: Arc<AtomicUsize>,
    /// Gate that ensures terminal events (FAILED/CLOSED) are emitted only once.
    pub terminal_sent: AtomicBool,
    /// Actual peer address for the connection, used by Java-side IP controls such as bans and rate
    /// limits.
    pub remote_addr: std::sync::RwLock<Option<SocketAddr>>,
    /// Serializes connection events to ensure no non-terminal event can occur after a terminal event.
    event_lock: Mutex<()>,
}

impl ConnHandle {
    /// Constructs the handle by wrapping the read-side channel and empty remainder queue in a shared
    /// lock.
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

    /// Attempts to emit a non-terminal event (DATA_AVAILABLE / WRITABLE). Silently drop it if terminal
    /// state is marked or already emitted.
    ///
    /// Important: invoke the foreign callback only after releasing the lock (INV-4).
    pub fn emit_non_terminal(
        &self,
        sink: &dyn EventSink,
        conn_id: u64,
        kind: u32,
        arg0: i64,
        arg1: i64,
    ) -> bool {
        let should_emit = {
            let _guard = self.event_lock.lock().unwrap_or_else(|p| p.into_inner());
            !self.terminal_sent.load(Ordering::SeqCst)
        };
        if should_emit {
            sink.on_event(kind, conn_id, arg0, arg1);
            true
        } else {
            false
        }
    }

    /// Atomically emits the successful connection-state event (CONNECTED). Silently drop it if terminal
    /// state is already marked.
    ///
    /// Important: invoke the foreign callback only after releasing the lock (INV-4).
    pub fn emit_connected(&self, sink: &dyn EventSink, conn_id: u64) -> bool {
        let should_emit = {
            let _guard = self.event_lock.lock().unwrap_or_else(|p| p.into_inner());
            if self.terminal_sent.load(Ordering::SeqCst) {
                false
            } else {
                self.state.store(STATE_CONNECTED, Ordering::SeqCst);
                true
            }
        };
        if should_emit {
            sink.on_event(
                event::NB_EVENT_CONNECTION_STATE,
                conn_id,
                event::abi_connection_state(STATE_CONNECTED) as i64,
                0,
            );
            true
        } else {
            false
        }
    }

    /// Atomically emits a terminal event (FAILED/CLOSED) exactly once and establishes a hard event
    /// barrier preventing all later events.
    ///
    /// Important: invoke the foreign callback only after releasing the lock (INV-4).
    pub fn emit_terminal(
        &self,
        sink: &dyn EventSink,
        conn_id: u64,
        internal_state: u32,
        reason_code: i64,
    ) -> bool {
        let should_emit = {
            let _guard = self.event_lock.lock().unwrap_or_else(|p| p.into_inner());
            if self.terminal_sent.swap(true, Ordering::SeqCst) {
                false
            } else {
                self.state.store(internal_state, Ordering::SeqCst);
                true
            }
        };
        if should_emit {
            sink.on_event(
                event::NB_EVENT_CONNECTION_STATE,
                conn_id,
                event::abi_connection_state(internal_state) as i64,
                reason_code,
            );
            true
        } else {
            false
        }
    }
}

/// Server handle stored in the registry. The endpoint is a polymorphic transport endpoint; counters and
/// limits are private to this instance.
pub struct ServerHandle {
    pub endpoint: TransportEndpoint,
    pub port: u16,
    /// Active-connection limit for this instance; excess accepts are dropped.
    pub max_connections: usize,
    /// Active connection count for this instance, independent of other server instances.
    pub conn_count: Arc<AtomicUsize>,
    /// Server runtime state: RUNNING(1), STOPPED(2), FAILED(3).
    pub state: Arc<AtomicU8>,
    /// Serializes the linearization points for accept commit and stop.
    pub commit_lock: Arc<Mutex<()>>,
    /// Synchronization notification for stop completion.
    pub stopped_pair: Arc<(Mutex<bool>, std::sync::Condvar)>,
}

impl ServerHandle {
    pub fn is_running(&self) -> bool {
        self.state.load(Ordering::SeqCst) == SERVER_STATE_RUNNING
    }
}

/// Transport endpoint; `stop_server` closes it according to this variant.
pub enum TransportEndpoint {
    Quic(mpsc::Sender<()>),
    /// KCP stop trigger: sending it makes the accept task exit and drop the listener (aborting the task
    /// and closing the socket). The listener itself remains owned by the accept task.
    Kcp(mpsc::Sender<()>),
}

/// Report errors immediately to stderr, which the Minecraft launcher redirects to logs/latest.log.
pub fn report_error(msg: String) {
    eprintln!("[net-bridge-native] error: {msg}");
}

/// Attempts to count a new connection against the server instance; rolls back and rejects it if the
/// limit is exceeded.
pub(crate) fn try_admit(count: &AtomicUsize, max: usize) -> bool {
    let prev = count.fetch_add(1, Ordering::Relaxed);
    if prev >= max {
        count.fetch_sub(1, Ordering::Relaxed);
        return false;
    }
    true
}

/// Extracts readable text from a panic payload; non-string payloads use a placeholder.
fn describe_panic(payload: &Box<dyn Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic payload".to_string()
    }
}
