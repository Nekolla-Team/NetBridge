//! net-bridge-core: Pure Rust transport core for QUIC and KCP.
#![forbid(unsafe_code)]

pub mod context;
pub mod error;
pub mod event;
pub mod shared_io;
pub mod socket_util;
pub mod transport;

#[cfg(test)]
mod tests;

pub use context::NativeContext;
pub use error::BridgeError;
pub use event::{EventSink, NoopEventSink};
pub use shared_io::{SharedConnectionIo, SharedIoDriver};
pub use transport::TransportKind;

use std::any::Any;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use tokio::sync::mpsc;

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

/// Maximum size of a single I/O chunk (64 KiB)
pub const MAX_IO_CHUNK: usize = 64 * 1024;

/// The connection's data-plane access mode has not been claimed yet.
pub const ACCESS_MODE_UNCLAIMED: u8 = 0;
/// The connection's data plane is driven through the legacy `connection_write`/`connection_read` ABI.
pub const ACCESS_MODE_LEGACY_ABI: u8 = 1;
/// The connection's data plane is mapped directly by Java and must not use the legacy ABI.
pub const ACCESS_MODE_SHARED_DIRECT: u8 = 2;

/// Handle for a single connection.
pub struct ConnHandle {
    pub state: Arc<AtomicU32>,
    pub cancel_tx: tokio::sync::watch::Sender<bool>,
    /// Bidirectional shared-memory data plane for this connection.
    pub shared_io: Arc<SharedConnectionIo>,
    /// One of [`ACCESS_MODE_UNCLAIMED`], [`ACCESS_MODE_LEGACY_ABI`], or [`ACCESS_MODE_SHARED_DIRECT`].
    /// Prevents the legacy ABI and direct ring access from being mixed on one connection.
    pub io_access_mode: AtomicU8,
    pub server_id: Option<u64>,
    /// Per-instance active count for server connections (None for client connections); decremented on
    /// removal.
    pub server_count: Option<Arc<AtomicUsize>>,
    /// Writes are allowed during connection establishment: a KCP client may write before its handshake
    /// completes (the bytes enter the TX ring and are sent immediately after the handshake; kcp-rs has
    /// a built-in handshake and does not depend on first-frame detection). False for QUIC clients.
    pub early_write: bool,
    /// Gate that ensures terminal events (FAILED/CLOSED) are emitted only once.
    pub terminal_sent: AtomicBool,
    /// Actual peer address for the connection, used by Java-side IP controls such as bans and rate
    /// limits.
    pub remote_addr: std::sync::RwLock<Option<SocketAddr>>,
    /// Serializes connection events to ensure no non-terminal event can occur after a terminal event.
    event_lock: Mutex<()>,
}

impl ConnHandle {
    /// Constructs the handle around an already allocated shared data plane.
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        state: Arc<AtomicU32>,
        cancel_tx: tokio::sync::watch::Sender<bool>,
        shared_io: Arc<SharedConnectionIo>,
        server_id: Option<u64>,
        server_count: Option<Arc<AtomicUsize>>,
        early_write: bool,
        remote_addr: Option<SocketAddr>,
    ) -> Self {
        Self {
            state,
            cancel_tx,
            shared_io,
            io_access_mode: AtomicU8::new(ACCESS_MODE_UNCLAIMED),
            server_id,
            server_count,
            early_write,
            terminal_sent: AtomicBool::new(false),
            remote_addr: std::sync::RwLock::new(remote_addr),
            event_lock: Mutex::new(()),
        }
    }

    /// Claims the legacy ABI data plane for this connection.
    ///
    /// Idempotent for repeated legacy use; fails with an invalid-state error if Java already mapped
    /// the rings directly.
    pub fn claim_legacy_abi(&self) -> Result<(), BridgeError> {
        match self.io_access_mode.compare_exchange(
            ACCESS_MODE_UNCLAIMED,
            ACCESS_MODE_LEGACY_ABI,
            Ordering::SeqCst,
            Ordering::SeqCst,
        ) {
            Ok(_) | Err(ACCESS_MODE_LEGACY_ABI) => Ok(()),
            Err(_) => Err(BridgeError::InvalidState(
                "connection data plane is bound to shared-direct IO",
            )),
        }
    }

    /// Claims the shared-direct data plane for this connection.
    ///
    /// Idempotent for repeated descriptor queries; fails with an invalid-state error if the legacy
    /// ABI was already used on this connection.
    pub fn claim_shared_direct(&self) -> Result<(), BridgeError> {
        match self.io_access_mode.compare_exchange(
            ACCESS_MODE_UNCLAIMED,
            ACCESS_MODE_SHARED_DIRECT,
            Ordering::SeqCst,
            Ordering::SeqCst,
        ) {
            Ok(_) | Err(ACCESS_MODE_SHARED_DIRECT) => Ok(()),
            Err(_) => Err(BridgeError::InvalidState(
                "connection data plane is bound to the legacy ABI",
            )),
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
