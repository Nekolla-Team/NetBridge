//! Connection handle and event-ordering state.

use std::collections::VecDeque;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use bytes::Bytes;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use crate::event::EventSink;
use crate::{Command, STATE_CONNECTED};

/// Shared data-plane counters and wake handles.
#[derive(Clone, Default)]
pub(crate) struct ConnectionCounters {
    pub(crate) write_blocked: Arc<AtomicBool>,
    pub(crate) outbound_bytes: Arc<AtomicUsize>,
    pub(crate) inbound_bytes: Arc<AtomicUsize>,
    pub(crate) read_waker: Arc<tokio::sync::Notify>,
}

/// Handle for a single connection.
pub(crate) struct ConnHandle {
    pub(crate) state: Arc<AtomicU32>,
    /// Java read-side chunk queue plus an unconsumed remainder. Bytes shared views are sliced zero-copy;
    /// stored behind Arc so the read path can clone it and release the DashMap guard immediately.
    pub(crate) to_java: Arc<Mutex<(mpsc::Receiver<Bytes>, VecDeque<Bytes>)>>,
    pub(crate) to_transport: mpsc::Sender<Command>,
    pub(crate) cancel: CancellationToken,
    /// Notifies the read data plane when inbound capacity becomes available.
    pub(crate) read_waker: Arc<tokio::sync::Notify>,
    /// Per-instance active count for server connections (None for client connections); decremented on
    /// removal.
    pub(crate) server_count: Option<Arc<AtomicUsize>>,
    /// Writes are allowed during connection establishment: a KCP client may write before its handshake
    /// completes. False for QUIC clients.
    pub(crate) early_write: bool,
    /// Set when the write queue or byte budget is exhausted; cleared after the transport task frees
    /// capacity and emits WRITABLE.
    pub(crate) write_blocked: Arc<AtomicBool>,
    /// In-flight outbound byte count, increased by FFI write and decreased when the transport task
    /// consumes data.
    pub(crate) outbound_bytes: Arc<AtomicUsize>,
    /// In-flight inbound byte count, increased by the transport reader and decreased by Java reads.
    pub(crate) inbound_bytes: Arc<AtomicUsize>,
    /// Gate that ensures terminal events (FAILED/CLOSED) are emitted only once.
    pub(crate) terminal_sent: AtomicBool,
    /// Actual peer address for the connection, used by Java-side IP controls such as bans and rate
    /// limits.
    pub(crate) remote_addr: std::sync::RwLock<Option<SocketAddr>>,
    /// Serializes connection events to ensure no non-terminal event can occur after a terminal event.
    event_lock: Mutex<()>,
}

impl ConnHandle {
    /// Creates a client connection handle.
    pub(crate) fn client(
        state: Arc<AtomicU32>,
        to_java_rx: mpsc::Receiver<Bytes>,
        to_transport: mpsc::Sender<Command>,
        cancel: CancellationToken,
        early_write: bool,
        remote_addr: Option<SocketAddr>,
    ) -> Self {
        Self::new(
            state,
            to_java_rx,
            to_transport,
            cancel,
            None,
            early_write,
            remote_addr,
        )
    }

    /// Creates an accepted server connection handle.
    pub(crate) fn server(
        state: Arc<AtomicU32>,
        to_java_rx: mpsc::Receiver<Bytes>,
        to_transport: mpsc::Sender<Command>,
        cancel: CancellationToken,
        server_count: Arc<AtomicUsize>,
        remote_addr: SocketAddr,
    ) -> Self {
        Self::new(
            state,
            to_java_rx,
            to_transport,
            cancel,
            Some(server_count),
            false,
            Some(remote_addr),
        )
    }

    /// Constructs the handle by wrapping the read-side channel and empty remainder queue in a shared
    /// lock.
    fn new(
        state: Arc<AtomicU32>,
        to_java_rx: mpsc::Receiver<Bytes>,
        to_transport: mpsc::Sender<Command>,
        cancel: CancellationToken,
        server_count: Option<Arc<AtomicUsize>>,
        early_write: bool,
        remote_addr: Option<SocketAddr>,
    ) -> Self {
        Self {
            state,
            to_java: Arc::new(Mutex::new((to_java_rx, VecDeque::new()))),
            to_transport,
            cancel,
            read_waker: Arc::new(tokio::sync::Notify::new()),
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

    /// Attempts to emit a non-terminal event (DATA_AVAILABLE / WRITABLE). Silently drops it if a
    /// terminal event has already been committed.
    pub(crate) fn emit_non_terminal(
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

    /// Atomically emits CONNECTED unless a terminal event has already been committed.
    pub(crate) fn emit_connected(&self, sink: &dyn EventSink, conn_id: u64) -> bool {
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

    /// Atomically emits a terminal event exactly once and prevents all later events.
    pub(crate) fn emit_terminal(
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

    /// Marks the connection closed and wakes the data plane.
    pub(crate) fn request_close(&self) -> mpsc::Sender<Command> {
        self.cancel.cancel();
        self.to_transport.clone()
    }

    /// Clones the data-plane counters and wake handles.
    pub(crate) fn counters(&self) -> ConnectionCounters {
        ConnectionCounters {
            write_blocked: Arc::clone(&self.write_blocked),
            outbound_bytes: Arc::clone(&self.outbound_bytes),
            inbound_bytes: Arc::clone(&self.inbound_bytes),
            read_waker: Arc::clone(&self.read_waker),
        }
    }
}
