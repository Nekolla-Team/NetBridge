//! NativeContext: root of instance-level runtime ownership.

use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU8, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use dashmap::DashMap;
use futures_util::FutureExt;
use net_bridge_shared_io::SharedRing;
use tokio::runtime::{Builder, Runtime};

use crate::error::BridgeError;
use crate::event::{EventSink, NoopEventSink};
use crate::shared_io::{
    DEFAULT_SHARED_IO_RX_CAPACITY, DEFAULT_SHARED_IO_TX_CAPACITY, SharedConnectionIo,
    SharedIoDriver,
};
use crate::transport::TransportKind;
use crate::transport::kcp::config::KcpProfile;
use crate::{
    ConnHandle, STATE_CLOSED, STATE_CONNECTED, STATE_FAILED, ServerHandle, TransportEndpoint,
};

/// Production KCP listener startup window.
pub const STARTUP_TIMEOUT: Duration = Duration::from_secs(5);

pub const CONTEXT_STATE_RUNNING: u8 = 0;
pub const CONTEXT_STATE_SHUTTING_DOWN: u8 = 1;
pub const CONTEXT_STATE_CLOSED: u8 = 2;

/// Plain-value shared IO ring descriptor for one connection, safe to marshal across the C ABI.
pub struct ConnectionIoRegion {
    /// Base address of the Java -> transport ring region (header + data).
    pub tx_base: usize,
    /// Total mapped size of the TX region in bytes (`header + capacity`).
    pub tx_total_bytes: usize,
    /// Usable TX capacity in bytes.
    pub tx_capacity: usize,
    /// Base address of the transport -> Java ring region (header + data).
    pub rx_base: usize,
    /// Total mapped size of the RX region in bytes (`header + capacity`).
    pub rx_total_bytes: usize,
    /// Usable RX capacity in bytes.
    pub rx_capacity: usize,
    /// Ring layout version (`major << 32 | minor`).
    pub layout_version: u64,
}

pub struct NativeContext {
    state: AtomicU8,
    runtime: Mutex<Option<Runtime>>,
    handle: tokio::runtime::Handle,
    connections: DashMap<u64, Arc<ConnHandle>>,
    servers: DashMap<u64, Arc<ServerHandle>>,
    server_tasks: DashMap<u64, tokio::task::JoinHandle<()>>,
    conn_tasks: DashMap<u64, tokio::task::JoinHandle<()>>,
    next_id: AtomicU64,
    event_sink: Arc<dyn EventSink>,
    shared_io_tx_capacity: usize,
    shared_io_rx_capacity: usize,
}

impl NativeContext {
    /// Creates a new NativeContext instance with the default shared-ring capacities.
    pub fn new(
        worker_threads: usize,
        event_sink: Option<Arc<dyn EventSink>>,
    ) -> Result<Arc<Self>, BridgeError> {
        Self::new_with_shared_io_capacities(
            worker_threads,
            event_sink,
            DEFAULT_SHARED_IO_TX_CAPACITY,
            DEFAULT_SHARED_IO_RX_CAPACITY,
        )
    }

    /// Creates a new NativeContext with explicit per-connection shared-ring capacities.
    ///
    /// Zero capacities select the defaults. Capacities must be powers of two at or above the
    /// shared-io minimum; an invalid value fails context creation.
    pub fn new_with_shared_io_capacities(
        worker_threads: usize,
        event_sink: Option<Arc<dyn EventSink>>,
        shared_io_tx_capacity: usize,
        shared_io_rx_capacity: usize,
    ) -> Result<Arc<Self>, BridgeError> {
        let tx_capacity = if shared_io_tx_capacity == 0 {
            DEFAULT_SHARED_IO_TX_CAPACITY
        } else {
            shared_io_tx_capacity
        };
        let rx_capacity = if shared_io_rx_capacity == 0 {
            DEFAULT_SHARED_IO_RX_CAPACITY
        } else {
            shared_io_rx_capacity
        };
        // Validate the ring capacities eagerly so misconfiguration fails at creation time.
        SharedRing::allocate(tx_capacity)
            .and_then(|_| SharedRing::allocate(rx_capacity))
            .map_err(|err| {
                BridgeError::InvalidArgument(match err {
                    net_bridge_shared_io::SharedIoError::CapacityNotPowerOfTwo(_) => {
                        "shared io capacity must be a power of two"
                    }
                    net_bridge_shared_io::SharedIoError::CapacityTooSmall { .. } => {
                        "shared io capacity is below the minimum"
                    }
                    net_bridge_shared_io::SharedIoError::CapacityTooLarge(_) => {
                        "shared io capacity is above the maximum"
                    }
                    _ => "invalid shared io capacity",
                })
            })?;

        let mut builder = Builder::new_multi_thread();
        builder.enable_all();
        builder.thread_name("net-bridge-native");
        if worker_threads > 0 {
            builder.worker_threads(worker_threads);
        }
        let rt = builder
            .build()
            .map_err(|_| BridgeError::RuntimeUnavailable)?;
        let handle = rt.handle().clone();

        Ok(Arc::new(Self {
            state: AtomicU8::new(CONTEXT_STATE_RUNNING),
            runtime: Mutex::new(Some(rt)),
            handle,
            connections: DashMap::new(),
            servers: DashMap::new(),
            server_tasks: DashMap::new(),
            conn_tasks: DashMap::new(),
            next_id: AtomicU64::new(1),
            event_sink: event_sink.unwrap_or_else(|| Arc::new(NoopEventSink)),
            shared_io_tx_capacity: tx_capacity,
            shared_io_rx_capacity: rx_capacity,
        }))
    }

    /// Returns the configured per-connection shared-ring capacities `(tx, rx)`.
    pub fn shared_io_capacities(&self) -> (usize, usize) {
        (self.shared_io_tx_capacity, self.shared_io_rx_capacity)
    }

    /// Allocates the data plane and handle for a new connection.
    pub(crate) fn create_connection_io(
        &self,
    ) -> Result<(Arc<SharedConnectionIo>, SharedIoDriver), BridgeError> {
        SharedConnectionIo::create(self.shared_io_tx_capacity, self.shared_io_rx_capacity)
    }

    pub fn handle(&self) -> &tokio::runtime::Handle {
        &self.handle
    }

    pub fn state(&self) -> u8 {
        self.state.load(Ordering::SeqCst)
    }

    pub fn is_running(&self) -> bool {
        self.state() == CONTEXT_STATE_RUNNING
    }

    /// Allocates IDs that are never reused and never zero; wraparound is fatal for the context.
    pub fn allocate_id(&self) -> Result<u64, BridgeError> {
        let mut curr = self.next_id.load(Ordering::SeqCst);
        loop {
            if curr == 0 {
                return Err(BridgeError::IdOverflow);
            }
            let next = if curr == u64::MAX { 0 } else { curr + 1 };
            match self
                .next_id
                .compare_exchange_weak(curr, next, Ordering::SeqCst, Ordering::SeqCst)
            {
                Ok(allocated) => return Ok(allocated),
                Err(actual) => curr = actual,
            }
        }
    }

    pub fn event_sink(&self) -> &Arc<dyn EventSink> {
        &self.event_sink
    }

    pub fn conns(&self) -> &DashMap<u64, Arc<ConnHandle>> {
        &self.connections
    }

    pub fn servers_map(&self) -> &DashMap<u64, Arc<ServerHandle>> {
        &self.servers
    }

    pub fn remove_conn(&self, conn_id: u64) -> Option<Arc<ConnHandle>> {
        self.connections.remove(&conn_id).map(|(_, h)| {
            if let Some(count) = h.server_count.as_ref() {
                count.fetch_sub(1, Ordering::Relaxed);
            }
            h
        })
    }

    pub fn connection_state(&self, conn: u64) -> Option<u32> {
        self.connections
            .get(&conn)
            .map(|h| h.state.load(Ordering::SeqCst))
    }

    pub fn connection_remote_addr(&self, conn: u64) -> Option<SocketAddr> {
        self.connections
            .get(&conn)
            .and_then(|h| *h.remote_addr.read().unwrap())
    }

    /// Attempts to atomically commit an accepted connection; fails atomically if the server has stopped
    /// or is not running.
    ///
    /// Important: invoke the foreign callback only after releasing all locks and DashMap guards (INV-4).
    pub(crate) fn try_commit_accept(
        &self,
        server_id: u64,
        conn_id: u64,
        handle: Arc<ConnHandle>,
    ) -> bool {
        let server = match self.servers.get(&server_id).map(|r| Arc::clone(&*r)) {
            Some(s) => s,
            None => return false,
        };
        // DashMap guard has been released
        let committed = {
            let _guard = server.commit_lock.lock().unwrap_or_else(|p| p.into_inner());
            if !server.is_running() {
                false
            } else {
                self.connections.insert(conn_id, handle);
                true
            }
        };
        // commit_lock has been released
        if committed {
            self.event_sink().on_event(
                crate::event::NB_EVENT_ACCEPTED,
                server_id,
                conn_id as i64,
                0,
            );
            true
        } else {
            false
        }
    }

    /// Emits a successful-connection event (CONNECTED).
    ///
    /// Important: release the DashMap guard before the callback (INV-4).
    pub(crate) fn emit_connected(&self, conn_id: u64) -> bool {
        let handle = self.connections.get(&conn_id).map(|h| Arc::clone(&*h));
        if let Some(handle) = handle {
            handle.emit_connected(&*self.event_sink, conn_id)
        } else {
            false
        }
    }

    /// Emits a server-state event (SERVER_STATE).
    pub(crate) fn emit_server_state(&self, server_id: u64, state: u8) {
        self.event_sink().on_event(
            crate::event::NB_EVENT_SERVER_STATE,
            server_id,
            state as i64,
            0,
        );
    }

    /// Java-side release: send Close and remove the registry entry; the connection wrapper owns the
    /// entry.
    pub fn close_connection(&self, conn: u64) -> bool {
        let handle = match self.connections.get(&conn).map(|h| Arc::clone(&*h)) {
            Some(h) => h,
            None => return false,
        };
        // DashMap guard was released immediately after obtaining the Arc
        let _ = handle.emit_terminal(&*self.event_sink, conn, STATE_CLOSED, 0);
        let _ = handle.cancel_tx.send(true);
        drop(handle);
        self.remove_conn(conn);
        true
    }

    /// Emits a non-terminal event (DATA_AVAILABLE / WRITABLE). Silently drop it if terminal state has
    /// already been marked.
    ///
    /// Important: release the DashMap guard before the callback (INV-4).
    pub(crate) fn emit_non_terminal(&self, conn_id: u64, kind: u32, arg0: i64, arg1: i64) -> bool {
        let handle = self.connections.get(&conn_id).map(|h| Arc::clone(&*h));
        if let Some(handle) = handle {
            handle.emit_non_terminal(&*self.event_sink, conn_id, kind, arg0, arg1)
        } else {
            false
        }
    }

    /// Emit the terminal event (FAILED/CLOSED) exactly once; keep the entry as a tombstone until Java
    /// releases it.
    ///
    /// Important: release the DashMap guard before the callback (INV-4).
    pub(crate) fn emit_terminal_with_reason(&self, conn_id: u64, reason_code: i64) {
        let handle = self.connections.get(&conn_id).map(|h| Arc::clone(&*h));
        if let Some(handle) = handle {
            let state = handle.state.load(Ordering::SeqCst);
            let _ = handle.emit_terminal(&*self.event_sink, conn_id, state, reason_code);
        }
    }

    pub(crate) fn emit_terminal(&self, conn_id: u64) {
        self.emit_terminal_with_reason(conn_id, 0);
    }

    /// Commit terminal state while retaining the tombstone, plus emit the terminal event exactly once.
    ///
    /// Important: release the DashMap guard before the callback (INV-4).
    pub(crate) fn fail_connection_with_reason(&self, conn_id: u64, reason_code: i64) {
        let handle = self.connections.get(&conn_id).map(|h| Arc::clone(&*h));
        if let Some(handle) = handle {
            let _ = handle.emit_terminal(&*self.event_sink, conn_id, STATE_FAILED, reason_code);
        }
    }

    #[allow(dead_code)]
    pub(crate) fn fail_connection(&self, conn_id: u64) {
        self.fail_connection_with_reason(conn_id, 0);
    }

    pub(crate) fn set_conn_remote_addr(&self, conn_id: u64, addr: SocketAddr) {
        if let Some(handle) = self.connections.get(&conn_id) {
            *handle.remote_addr.write().unwrap() = Some(addr);
        }
    }

    /// panic-in-poll protection: actually catch panics during future polling with a single-layer task
    /// and no nested spawn. Register before execution so a task cannot exit/remove itself until registry
    /// insertion completes, preventing stale-handle races. An RAII guard ensures the task is removed
    /// from conn_tasks whether it exits normally, panics, or is aborted.
    pub(crate) fn spawn_connection_task<F>(
        self: &Arc<Self>,
        what: &'static str,
        conn_id: u64,
        fut: F,
    ) where
        F: Future<Output = ()> + Send + 'static,
    {
        let ctx = Arc::clone(self);
        let (start_tx, start_rx) = tokio::sync::oneshot::channel::<()>();
        let join_handle = self.handle.spawn(async move {
            struct ConnTaskGuard {
                ctx: Arc<NativeContext>,
                id: u64,
            }
            impl Drop for ConnTaskGuard {
                fn drop(&mut self) {
                    self.ctx.conn_tasks.remove(&self.id);
                }
            }
            let _guard = ConnTaskGuard {
                ctx: Arc::clone(&ctx),
                id: conn_id,
            };
            let _ = start_rx.await;
            let res = std::panic::AssertUnwindSafe(fut).catch_unwind().await;
            if let Err(payload) = res {
                crate::report_error(format!(
                    "{what} panicked: {}",
                    crate::describe_panic(&payload)
                ));
                ctx.fail_connection_with_reason(conn_id, crate::event::NB_REASON_INTERNAL);
            }
        });
        self.conn_tasks.insert(conn_id, join_handle);
        let _ = start_tx.send(());
    }

    /// A server-task panic transitions state to FAILED and emits a SERVER_STATE event. Single-layer task
    /// with no nested spawn; registration precedes execution, and an RAII guard guarantees cleanup.
    pub(crate) fn spawn_server_task<F>(self: &Arc<Self>, what: &'static str, server_id: u64, fut: F)
    where
        F: Future<Output = ()> + Send + 'static,
    {
        let ctx = Arc::clone(self);
        let (start_tx, start_rx) = tokio::sync::oneshot::channel::<()>();
        let join_handle = self.handle.spawn(async move {
            struct ServerTaskGuard {
                ctx: Arc<NativeContext>,
                id: u64,
            }
            impl Drop for ServerTaskGuard {
                fn drop(&mut self) {
                    self.ctx.server_tasks.remove(&self.id);
                }
            }
            let _guard = ServerTaskGuard {
                ctx: Arc::clone(&ctx),
                id: server_id,
            };
            let _ = start_rx.await;
            let res = std::panic::AssertUnwindSafe(fut).catch_unwind().await;
            if let Err(payload) = res {
                crate::report_error(format!(
                    "{what} panicked: {}",
                    crate::describe_panic(&payload)
                ));
                if let Some(server) = ctx.servers.get(&server_id) {
                    server
                        .state
                        .store(crate::SERVER_STATE_FAILED, Ordering::SeqCst);
                }
                ctx.emit_server_state(server_id, crate::SERVER_STATE_FAILED);
                ctx.servers.remove(&server_id);
            }
        });
        self.server_tasks.insert(server_id, join_handle);
        let _ = start_tx.send(());
    }

    /// Legacy ABI write shim: copies `data` into the connection's TX ring.
    ///
    /// Returns the number of bytes published. `Ok(0)` with non-empty input means the ring is full
    /// (the caller maps this to WOULD_BLOCK).
    pub fn write_chunk_legacy(&self, conn: u64, data: &[u8]) -> Result<usize, BridgeError> {
        if data.is_empty() {
            return Ok(0);
        }
        if data.len() > crate::MAX_IO_CHUNK {
            return Err(BridgeError::InvalidArgument(
                "chunk size exceeds MAX_IO_CHUNK",
            ));
        }
        let handle = self
            .connections
            .get(&conn)
            .map(|h| Arc::clone(&*h))
            .ok_or(BridgeError::NoSuchConnection)?;
        handle.claim_legacy_abi()?;
        let writable = handle.state.load(Ordering::SeqCst) == STATE_CONNECTED || handle.early_write;
        if !writable {
            return Ok(0);
        }
        let io = Arc::clone(&handle.shared_io);
        drop(handle);
        Ok(io.write_legacy(data))
    }

    /// Legacy ABI read shim: copies up to `dst.len()` bytes out of the connection's RX ring.
    ///
    /// Returns the number of bytes consumed. `Ok(0)` with non-empty `dst` means the ring is empty
    /// (the caller maps this to WOULD_BLOCK).
    pub fn read_chunk_legacy(&self, conn: u64, dst: &mut [u8]) -> Result<usize, BridgeError> {
        let handle = self
            .connections
            .get(&conn)
            .map(|h| Arc::clone(&*h))
            .ok_or(BridgeError::NoSuchConnection)?;
        handle.claim_legacy_abi()?;
        let io = Arc::clone(&handle.shared_io);
        drop(handle);
        Ok(io.read_legacy(dst))
    }

    /// Returns the shared IO ring descriptor for a connection and claims shared-direct access.
    ///
    /// Repeated calls are allowed; mixing with the legacy ABI on the same connection fails with
    /// [`BridgeError::InvalidState`].
    pub fn connection_io_region(&self, conn: u64) -> Result<ConnectionIoRegion, BridgeError> {
        let handle = self
            .connections
            .get(&conn)
            .map(|h| Arc::clone(&*h))
            .ok_or(BridgeError::NoSuchConnection)?;
        handle.claim_shared_direct()?;
        let io = &handle.shared_io;
        Ok(ConnectionIoRegion {
            tx_base: io.tx.base_address(),
            tx_total_bytes: io.tx.total_bytes(),
            tx_capacity: io.tx.capacity(),
            rx_base: io.rx.base_address(),
            rx_total_bytes: io.rx.total_bytes(),
            rx_capacity: io.rx.capacity(),
            layout_version: net_bridge_shared_io::layout_version(),
        })
    }

    /// Edge-kicks a connection's transport driver.
    ///
    /// `tx_data` wakes the TX consumer waiting for Java-written data; `rx_space` wakes the RX
    /// producer waiting for Java to release space. This never copies payload or blocks.
    pub fn connection_io_kick(
        &self,
        conn: u64,
        tx_data: bool,
        rx_space: bool,
    ) -> Result<(), BridgeError> {
        let handle = self
            .connections
            .get(&conn)
            .map(|h| Arc::clone(&*h))
            .ok_or(BridgeError::NoSuchConnection)?;
        let io = &handle.shared_io;
        if tx_data {
            io.tx_data_notify.notify_one();
        }
        if rx_space {
            io.rx_space_notify.notify_one();
        }
        Ok(())
    }

    pub fn server_port(&self, server: u64) -> Option<u16> {
        self.servers.get(&server).map(|h| h.port)
    }

    pub fn stop_server(&self, server: u64) -> Result<(), BridgeError> {
        self.stop_server_with_timeout(server, Duration::from_secs(5))
    }

    pub fn stop_server_with_timeout(
        &self,
        server: u64,
        timeout: Duration,
    ) -> Result<(), BridgeError> {
        let handle = match self.servers.get(&server).map(|r| Arc::clone(&*r)) {
            Some(s) => s,
            None => return Err(BridgeError::NoSuchConnection),
        };
        // DashMap guard was released after obtaining the Arc
        let endpoint_stop = {
            let _guard = handle.commit_lock.lock().unwrap_or_else(|p| p.into_inner());
            let curr = handle.state.load(Ordering::SeqCst);
            if curr == crate::SERVER_STATE_STOPPED {
                return Ok(());
            }
            handle
                .state
                .store(crate::SERVER_STATE_STOPPED, Ordering::SeqCst);
            match &handle.endpoint {
                TransportEndpoint::Quic(stop_tx) => TransportEndpoint::Quic(stop_tx.clone()),
                TransportEndpoint::Kcp(stop_tx) => TransportEndpoint::Kcp(stop_tx.clone()),
            }
        };
        // commit_lock has been released
        match &endpoint_stop {
            TransportEndpoint::Quic(stop_tx) => {
                let _ = stop_tx.try_send(());
            }
            TransportEndpoint::Kcp(stop_tx) => {
                let _ = stop_tx.try_send(());
            }
        }
        let (lock, cvar) = &*handle.stopped_pair;
        let mut guard = lock.lock().unwrap_or_else(|p| p.into_inner());
        let start = std::time::Instant::now();
        while !*guard {
            let elapsed = start.elapsed();
            if elapsed >= timeout {
                return Err(BridgeError::Timeout);
            }
            let (g, _) = cvar
                .wait_timeout(guard, timeout.saturating_sub(elapsed))
                .unwrap_or_else(|p| p.into_inner());
            guard = g;
        }

        self.emit_server_state(server, crate::SERVER_STATE_STOPPED);
        self.servers.remove(&server);
        self.server_tasks.remove(&server);
        Ok(())
    }

    pub fn connect(
        self: &Arc<Self>,
        kind: TransportKind,
        host: &str,
        port: u16,
        profile: KcpProfile,
    ) -> Result<u64, BridgeError> {
        if !self.is_running() {
            return Err(BridgeError::RuntimeUnavailable);
        }
        match kind {
            TransportKind::Quic => crate::transport::quic::connect_in_context(self, host, port),
            TransportKind::Kcp => {
                crate::transport::kcp::connect_in_context(self, host, port, profile)
            }
        }
    }

    pub fn start_server(
        self: &Arc<Self>,
        kind: TransportKind,
        port: u16,
        max_connections: usize,
        bind: Option<IpAddr>,
        profile: KcpProfile,
    ) -> Result<u64, BridgeError> {
        if !self.is_running() {
            return Err(BridgeError::RuntimeUnavailable);
        }
        match kind {
            TransportKind::Quic => {
                crate::transport::quic::start_server_in_context(self, port, max_connections, bind)
            }
            TransportKind::Kcp => crate::transport::kcp::start_server_in_context(
                self,
                port,
                max_connections,
                bind,
                profile,
                STARTUP_TIMEOUT,
            ),
        }
    }

    pub fn shutdown(&self, timeout: Duration) -> Result<(), BridgeError> {
        let deadline = std::time::Instant::now() + timeout;
        if self
            .state
            .compare_exchange(
                CONTEXT_STATE_RUNNING,
                CONTEXT_STATE_SHUTTING_DOWN,
                Ordering::SeqCst,
                Ordering::SeqCst,
            )
            .is_err()
            && self.state() != CONTEXT_STATE_SHUTTING_DOWN
            && self.state() == CONTEXT_STATE_CLOSED
        {
            return Ok(());
        }

        let mut shutdown_err: Option<BridgeError> = None;

        // Stop all servers
        let server_ids: Vec<u64> = self.servers.iter().map(|e| *e.key()).collect();
        for s_id in server_ids {
            let remaining = deadline.saturating_duration_since(std::time::Instant::now());
            if let Err(e) = self.stop_server_with_timeout(s_id, remaining)
                && shutdown_err.is_none()
            {
                shutdown_err = Some(e);
            }
        }

        // Close all active connections
        let conn_ids: Vec<u64> = self.connections.iter().map(|e| *e.key()).collect();
        for c_id in conn_ids {
            self.close_connection(c_id);
        }

        // Wait for all connections and tasks to actually join and exit
        while !self.conn_tasks.is_empty() || !self.server_tasks.is_empty() {
            if std::time::Instant::now() >= deadline {
                if shutdown_err.is_none() {
                    shutdown_err = Some(BridgeError::Timeout);
                }
                break;
            }
            for entry in self.conn_tasks.iter() {
                entry.value().abort();
            }
            for entry in self.server_tasks.iter() {
                entry.value().abort();
            }
            std::thread::sleep(Duration::from_millis(5));
        }

        if let Some(err) = shutdown_err {
            return Err(err);
        }

        // Ensure all registries are empty
        let remaining_conns: Vec<u64> = self.connections.iter().map(|e| *e.key()).collect();
        for c_id in remaining_conns {
            self.remove_conn(c_id);
        }
        self.conn_tasks.clear();
        self.server_tasks.clear();

        // Shut down the Tokio runtime
        if let Ok(mut guard) = self.runtime.lock()
            && let Some(rt) = guard.take()
        {
            let remaining = deadline.saturating_duration_since(std::time::Instant::now());
            rt.shutdown_timeout(remaining);
        }

        self.state.store(CONTEXT_STATE_CLOSED, Ordering::SeqCst);
        Ok(())
    }
}

impl Drop for NativeContext {
    fn drop(&mut self) {
        if let Ok(mut rt_opt) = self.runtime.lock()
            && let Some(rt) = rt_opt.take()
        {
            if tokio::runtime::Handle::try_current().is_ok() {
                std::thread::spawn(move || {
                    drop(rt);
                });
            } else {
                drop(rt);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize};
    use std::time::Instant;

    struct TestEventSink {
        event_count: AtomicUsize,
    }

    impl EventSink for TestEventSink {
        fn on_event(&self, _event_kind: u32, _object_id: u64, _arg0: i64, _arg1: i64) {
            self.event_count.fetch_add(1, Ordering::SeqCst);
        }
    }

    struct RecordingSink(Mutex<Vec<(u32, u64, i64, i64)>>);

    impl EventSink for RecordingSink {
        fn on_event(&self, kind: u32, object_id: u64, arg0: i64, arg1: i64) {
            self.0.lock().unwrap().push((kind, object_id, arg0, arg1));
        }
    }

    fn recording_ctx() -> (Arc<NativeContext>, Arc<RecordingSink>) {
        let sink = Arc::new(RecordingSink(Mutex::new(Vec::new())));
        let ctx = NativeContext::new(2, Some(sink.clone())).expect("context");
        (ctx, sink)
    }

    /// Builds a ring-backed connection handle for tests that do not drive a transport task.
    fn test_handle(
        state: Arc<AtomicU32>,
        server_id: Option<u64>,
        server_count: Option<Arc<AtomicUsize>>,
        early_write: bool,
    ) -> ConnHandle {
        let (shared_io, _driver) = SharedConnectionIo::create(
            crate::shared_io::DEFAULT_SHARED_IO_TX_CAPACITY,
            crate::shared_io::DEFAULT_SHARED_IO_RX_CAPACITY,
        )
        .expect("shared io");
        let (cancel_tx, _cancel_rx) = tokio::sync::watch::channel(false);
        ConnHandle::new(
            state,
            cancel_tx,
            shared_io,
            server_id,
            server_count,
            early_write,
            None,
        )
    }

    fn wait_accepted(sink: &RecordingSink, server: u64) -> u64 {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let hit = sink
                .0
                .lock()
                .unwrap()
                .iter()
                .find(|(k, o, _, _)| *k == crate::event::NB_EVENT_ACCEPTED && *o == server)
                .map(|(_, _, a, _)| *a as u64);
            if let Some(conn) = hit {
                return conn;
            }
            assert!(Instant::now() < deadline, "accept timeout");
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    #[test]
    fn context_lifecycle_and_shutdown() {
        let sink = Arc::new(TestEventSink {
            event_count: AtomicUsize::new(0),
        });
        let ctx = NativeContext::new(2, Some(sink.clone())).expect("create context");
        assert!(ctx.is_running());
        assert_eq!(ctx.state(), CONTEXT_STATE_RUNNING);

        let server = ctx
            .start_server(TransportKind::Quic, 0, 64, None, KcpProfile::Balanced)
            .expect("start server in ctx");
        let port = ctx.server_port(server).expect("server port");
        assert_ne!(port, 0);

        let client = ctx
            .connect(TransportKind::Quic, "127.0.0.1", port, KcpProfile::Balanced)
            .expect("connect in ctx");

        // Wait for connection establishment and shutdown
        let deadline = Instant::now() + Duration::from_secs(5);
        while Instant::now() < deadline {
            if ctx.connection_state(client) == Some(STATE_CONNECTED) {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }

        ctx.shutdown(Duration::from_secs(3))
            .expect("shutdown context");
        assert_eq!(ctx.state(), CONTEXT_STATE_CLOSED);
        assert!(!ctx.is_running());
    }

    #[test]
    fn context_repeated_lifecycle_cycles_no_leak() {
        for cycle in 0..8u32 {
            let (ctx, sink) = recording_ctx();
            let server = ctx
                .start_server(TransportKind::Quic, 0, 16, None, KcpProfile::Balanced)
                .expect("start server");
            let port = ctx.server_port(server).expect("server port");

            let client = ctx
                .connect(TransportKind::Quic, "127.0.0.1", port, KcpProfile::Balanced)
                .expect("connect");
            let deadline = Instant::now() + Duration::from_secs(5);
            while Instant::now() < deadline {
                if ctx.connection_state(client) == Some(STATE_CONNECTED) {
                    break;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
            assert_eq!(
                ctx.connection_state(client),
                Some(STATE_CONNECTED),
                "cycle {cycle}: client not connected"
            );

            let server_conn = wait_accepted(&sink, server);

            let payload = format!("cycle-{cycle}-payload").into_bytes();
            assert_eq!(
                ctx.write_chunk_legacy(client, &payload).expect("write"),
                payload.len()
            );
            let read_deadline = Instant::now() + Duration::from_secs(5);
            let mut buf = vec![0u8; 65536];
            let mut got = 0usize;
            while got < payload.len() && Instant::now() < read_deadline {
                match ctx.read_chunk_legacy(server_conn, &mut buf) {
                    Ok(n) if n > 0 => got += n,
                    _ => std::thread::sleep(Duration::from_millis(10)),
                }
            }
            assert_eq!(got, payload.len(), "cycle {cycle}: roundtrip incomplete");

            ctx.close_connection(client);
            ctx.close_connection(server_conn);
            ctx.shutdown(Duration::from_secs(3)).expect("shutdown");
            assert_eq!(ctx.state(), CONTEXT_STATE_CLOSED);
            assert!(
                ctx.conns().is_empty(),
                "cycle {cycle}: leaked connections: {:?}",
                ctx.conns().iter().map(|e| *e.key()).collect::<Vec<_>>()
            );
            assert!(
                ctx.servers_map().is_empty(),
                "cycle {cycle}: leaked servers"
            );
        }
    }

    #[test]
    fn id_overflow_is_fatal_not_wrap() {
        let ctx = NativeContext::new(1, None).expect("context");
        ctx.next_id.store(u64::MAX - 1, Ordering::SeqCst);
        assert_eq!(ctx.allocate_id().expect("first"), u64::MAX - 1);
        assert_eq!(ctx.allocate_id().expect("second"), u64::MAX);
        assert!(matches!(ctx.allocate_id(), Err(BridgeError::IdOverflow)));
        assert!(matches!(ctx.allocate_id(), Err(BridgeError::IdOverflow)));
        assert!(matches!(ctx.allocate_id(), Err(BridgeError::IdOverflow)));
    }

    #[test]
    fn id_overflow_concurrent_near_max_never_reuses_or_resurrects() {
        let ctx = NativeContext::new(4, None).expect("context");
        let start_offset = 100u64;
        ctx.next_id.store(u64::MAX - start_offset, Ordering::SeqCst);
        let num_threads = 8;
        let iters_per_thread = 50;
        let allocated_ids = Arc::new(Mutex::new(Vec::new()));
        let mut handles = Vec::new();
        for _ in 0..num_threads {
            let ctx = Arc::clone(&ctx);
            let allocated_ids = Arc::clone(&allocated_ids);
            handles.push(std::thread::spawn(move || {
                for _ in 0..iters_per_thread {
                    if let Ok(id) = ctx.allocate_id() {
                        assert_ne!(id, 0, "ID 0 must never be allocated");
                        allocated_ids.lock().unwrap().push(id);
                    }
                }
            }));
        }
        for h in handles {
            h.join().unwrap();
        }
        let ids = allocated_ids.lock().unwrap().clone();
        assert_eq!(ids.len(), (start_offset + 1) as usize);
        let unique_set: std::collections::HashSet<u64> = ids.iter().copied().collect();
        assert_eq!(
            unique_set.len(),
            ids.len(),
            "All allocated IDs must be globally unique"
        );
        for expected in (u64::MAX - start_offset)..=u64::MAX {
            assert!(
                unique_set.contains(&expected),
                "All IDs in the requested range must be allocated successfully"
            );
        }
        for _ in 0..100 {
            assert!(matches!(ctx.allocate_id(), Err(BridgeError::IdOverflow)));
        }
    }

    #[test]
    fn panic_in_poll_cleans_up_and_emits_terminal_once() {
        let (ctx, sink) = recording_ctx();
        let state = Arc::new(AtomicU32::new(STATE_CONNECTED));
        ctx.conns()
            .insert(7, Arc::new(test_handle(state.clone(), None, None, false)));
        ctx.spawn_connection_task("panic probe", 7, async {
            tokio::time::sleep(Duration::from_millis(10)).await;
            panic!("boom in poll");
        });
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            if sink
                .0
                .lock()
                .unwrap()
                .iter()
                .any(|(k, o, _, _)| *k == crate::event::NB_EVENT_CONNECTION_STATE && *o == 7)
            {
                break;
            }
            assert!(
                Instant::now() < deadline,
                "Panic did not produce a terminal event"
            );
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(ctx.connection_state(7), Some(STATE_FAILED));
        assert!(
            ctx.conns().contains_key(&7),
            "Tombstone must remain until release"
        );
        let events = sink.0.lock().unwrap().len();
        std::thread::sleep(Duration::from_millis(50));
        assert_eq!(
            sink.0.lock().unwrap().len(),
            events,
            "Terminal event must occur exactly once"
        );
        ctx.close_connection(7);
        assert_eq!(ctx.connection_state(7), None);
    }

    #[test]
    fn ring_backpressure_blocks_then_writable_fires_on_edge() {
        let (ctx, sink) = recording_ctx();
        let capacity = crate::shared_io::DEFAULT_SHARED_IO_TX_CAPACITY;
        let (shared_io, mut driver) =
            SharedConnectionIo::create(capacity, capacity).expect("shared io");
        let (cancel_tx, _cancel_rx) = tokio::sync::watch::channel(false);
        ctx.conns().insert(
            42,
            Arc::new(ConnHandle::new(
                Arc::new(AtomicU32::new(STATE_CONNECTED)),
                cancel_tx,
                shared_io,
                None,
                None,
                false,
                None,
            )),
        );

        // 64 KiB chunks, 128 KiB ring => exactly two fit.
        let chunk = vec![0xAAu8; crate::MAX_IO_CHUNK];
        let total_chunks = capacity / crate::MAX_IO_CHUNK;
        for i in 0..total_chunks {
            let res = ctx.write_chunk_legacy(42, &chunk).expect("write ok");
            assert_eq!(res, crate::MAX_IO_CHUNK, "chunk {i} should be accepted");
        }

        // Ring is now full: the shim returns WOULD_BLOCK and arms the producer wait state.
        let over_res = ctx.write_chunk_legacy(42, &chunk).expect("would block");
        assert_eq!(over_res, 0, "full ring must return 0 / would_block");

        // The transport consumer drains one chunk; because the producer was parked this claims the
        // WRITABLE edge.
        let mut drain = vec![0u8; crate::MAX_IO_CHUNK];
        let (n, wake) = driver.tx_consumer.copy_into(&mut drain);
        assert_eq!(n, crate::MAX_IO_CHUNK);
        assert!(wake, "draining a parked producer must claim the wakeup");
        ctx.emit_non_terminal(42, crate::event::NB_EVENT_WRITABLE, 0, 0);

        let writable_events = sink
            .0
            .lock()
            .unwrap()
            .iter()
            .filter(|(k, o, _, _)| *k == crate::event::NB_EVENT_WRITABLE && *o == 42)
            .count();
        assert_eq!(writable_events, 1, "WRITABLE event must be emitted on edge");

        // Space was released, so another chunk is accepted.
        let next_res = ctx
            .write_chunk_legacy(42, &chunk)
            .expect("write ok after drain");
        assert_eq!(next_res, crate::MAX_IO_CHUNK);

        ctx.close_connection(42);
    }

    #[test]
    fn kcp_startup_timeout_rolls_back_no_orphan() {
        let ctx = NativeContext::new(1, None).expect("context");
        let result = crate::transport::kcp::start_server_in_context(
            &ctx,
            0,
            16,
            None,
            KcpProfile::Balanced,
            Duration::ZERO,
        );
        assert!(result.is_err(), "ZERO timeout must fail");
        std::thread::sleep(Duration::from_millis(50));
        assert!(
            ctx.servers_map().is_empty(),
            "Startup timeout must not leave an orphan server: {:?}",
            ctx.servers_map()
                .iter()
                .map(|e| *e.key())
                .collect::<Vec<_>>()
        );
    }

    #[test]
    fn client_remote_addr_recorded_after_connect() {
        let ctx = NativeContext::new(2, None).expect("context");
        let server = ctx
            .start_server(TransportKind::Quic, 0, 8, None, KcpProfile::Balanced)
            .expect("server");
        let port = ctx.server_port(server).expect("port");
        let client = ctx
            .connect(TransportKind::Quic, "127.0.0.1", port, KcpProfile::Balanced)
            .expect("client");
        let deadline = Instant::now() + Duration::from_secs(5);
        while ctx.connection_state(client) != Some(STATE_CONNECTED) && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
        }
        let remote = ctx
            .connection_remote_addr(client)
            .expect("Client remote address must be recorded after a successful handshake");
        assert!(remote.port() > 0);
    }

    #[test]
    fn event_ordering_terminal_fences_non_terminal() {
        let (ctx, sink) = recording_ctx();
        let conn_id = 999;
        ctx.conns().insert(
            conn_id,
            Arc::new(test_handle(
                Arc::new(AtomicU32::new(STATE_CONNECTED)),
                None,
                None,
                false,
            )),
        );

        // Emit terminal
        ctx.emit_terminal_with_reason(conn_id, crate::event::NB_REASON_PROTOCOL);

        // Attempt non-terminal events after terminal: MUST be rejected/suppressed!
        let data_emitted =
            ctx.emit_non_terminal(conn_id, crate::event::NB_EVENT_DATA_AVAILABLE, 0, 0);
        assert!(
            !data_emitted,
            "DATA_AVAILABLE after terminal must be suppressed"
        );

        let writable_emitted =
            ctx.emit_non_terminal(conn_id, crate::event::NB_EVENT_WRITABLE, 0, 0);
        assert!(
            !writable_emitted,
            "WRITABLE after terminal must be suppressed"
        );

        // Duplicate terminal must also be a no-op
        ctx.emit_terminal(conn_id);

        let events = sink.0.lock().unwrap().clone();
        assert_eq!(
            events.len(),
            1,
            "Only exact-once terminal event must be emitted"
        );
        assert_eq!(events[0].0, crate::event::NB_EVENT_CONNECTION_STATE);
        assert_eq!(events[0].1, conn_id);
        assert_eq!(events[0].3, crate::event::NB_REASON_PROTOCOL);
    }

    #[test]
    fn server_stop_linearization_rejects_late_accept() {
        let (ctx, sink) = recording_ctx();
        let (stop_tx, _) = tokio::sync::mpsc::channel(1);
        let server_id = 100;
        let conn_count = Arc::new(AtomicUsize::new(1));
        let state = Arc::new(AtomicU8::new(crate::SERVER_STATE_RUNNING));
        let commit_lock = Arc::new(Mutex::new(()));
        let stopped_pair = Arc::new((Mutex::new(true), std::sync::Condvar::new()));

        ctx.servers_map().insert(
            server_id,
            Arc::new(ServerHandle {
                endpoint: TransportEndpoint::Quic(stop_tx),
                port: 12345,
                max_connections: 16,
                conn_count: Arc::clone(&conn_count),
                state: Arc::clone(&state),
                commit_lock: Arc::clone(&commit_lock),
                stopped_pair,
            }),
        );

        // Stop the server
        assert!(ctx.stop_server(server_id).is_ok());

        // Now an accept attempt comes in after stop linearization:
        let handle = test_handle(
            Arc::new(AtomicU32::new(STATE_CONNECTED)),
            Some(server_id),
            Some(conn_count),
            false,
        );

        let accepted = ctx.try_commit_accept(server_id, 200, Arc::new(handle));
        assert!(!accepted, "Late accept after server stop must be rejected");
        assert!(
            !ctx.conns().contains_key(&200),
            "Rejected connection must not be in registry"
        );

        let events: Vec<_> = sink
            .0
            .lock()
            .unwrap()
            .iter()
            .filter(|(k, _, _, _)| *k == crate::event::NB_EVENT_ACCEPTED)
            .cloned()
            .collect();
        assert!(
            events.is_empty(),
            "No ACCEPTED event may be emitted after stop"
        );
    }

    #[test]
    fn close_is_out_of_band_and_cancels() {
        let (ctx, _) = recording_ctx();
        let (cancel_tx, cancel_rx) = tokio::sync::watch::channel(false);
        let conn_id = 777;
        let (shared_io, _driver) = SharedConnectionIo::create(
            crate::shared_io::DEFAULT_SHARED_IO_TX_CAPACITY,
            crate::shared_io::DEFAULT_SHARED_IO_RX_CAPACITY,
        )
        .expect("shared io");

        ctx.conns().insert(
            conn_id,
            Arc::new(ConnHandle::new(
                Arc::new(AtomicU32::new(STATE_CONNECTED)),
                cancel_tx,
                shared_io,
                None,
                None,
                false,
                None,
            )),
        );

        // Closing must succeed immediately and signal cancellation out-of-band.
        assert!(ctx.close_connection(conn_id));
        assert!(*cancel_rx.borrow(), "Cancellation signal must be delivered");
        assert!(
            !ctx.conns().contains_key(&conn_id),
            "Registry entry must be removed"
        );
    }

    #[test]
    fn partial_read_consumes_exact_bytes() {
        let (ctx, _) = recording_ctx();
        let capacity = crate::shared_io::DEFAULT_SHARED_IO_RX_CAPACITY;
        let (shared_io, mut driver) =
            SharedConnectionIo::create(capacity, capacity).expect("shared io");
        let (cancel_tx, _) = tokio::sync::watch::channel(false);
        let conn_id = 888;
        let handle = ConnHandle::new(
            Arc::new(AtomicU32::new(STATE_CONNECTED)),
            cancel_tx,
            shared_io,
            None,
            None,
            false,
            None,
        );
        ctx.conns().insert(conn_id, Arc::new(handle));

        // The transport producer publishes 100 bytes.
        let (written, _) = driver.rx_producer.copy_from(&[42u8; 100]);
        assert_eq!(written, 100);

        // Read 30 bytes: exactly 30 are consumed and 70 remain readable.
        let mut first = [0u8; 30];
        let n = ctx.read_chunk_legacy(conn_id, &mut first).expect("read");
        assert_eq!(n, 30);
        assert_eq!(first, [42u8; 30]);
        let used = driver.rx_producer.capacity() - driver.rx_producer.writable_len();
        assert_eq!(used, 70);

        // Read the remaining 70 bytes.
        let mut second = [0u8; 100];
        let m = ctx.read_chunk_legacy(conn_id, &mut second).expect("read");
        assert_eq!(m, 70);
        assert_eq!(&second[..70], &[42u8; 70]);
        assert_eq!(driver.rx_producer.writable_len(), capacity);
    }

    struct ReentrantConnSink {
        ctx: std::sync::RwLock<Option<Arc<NativeContext>>>,
        reentered: AtomicBool,
    }

    impl EventSink for ReentrantConnSink {
        fn on_event(&self, kind: u32, object_id: u64, _arg0: i64, _arg1: i64) {
            if kind == crate::event::NB_EVENT_CONNECTION_STATE
                && !self.reentered.swap(true, Ordering::SeqCst)
                && let Some(ctx) = self.ctx.read().unwrap().as_ref()
            {
                // Synchronously reenter close_connection and query state to verify there is no
                // deadlock
                assert!(ctx.close_connection(object_id));
                let _ = ctx.connection_state(object_id);
            }
        }
    }

    #[test]
    fn reentrant_callback_connection_state_calls_close_no_deadlock() {
        let sink = Arc::new(ReentrantConnSink {
            ctx: std::sync::RwLock::new(None),
            reentered: AtomicBool::new(false),
        });
        let ctx = NativeContext::new(2, Some(sink.clone())).expect("context");
        *sink.ctx.write().unwrap() = Some(ctx.clone());

        let conn_id = 999;
        let handle = test_handle(
            Arc::new(AtomicU32::new(crate::STATE_CONNECTING)),
            None,
            None,
            false,
        );
        ctx.conns().insert(conn_id, Arc::new(handle));

        // Trigger CONNECTED; the sink synchronously reenters close_connection
        assert!(ctx.emit_connected(conn_id));
        assert!(
            sink.reentered.load(Ordering::SeqCst),
            "Sink must be invoked and reenter"
        );
        assert!(
            !ctx.conns().contains_key(&conn_id),
            "Connection must be removed successfully"
        );
    }

    struct ReentrantServerSink {
        ctx: std::sync::RwLock<Option<Arc<NativeContext>>>,
        stopped_in_callback: AtomicBool,
    }

    impl EventSink for ReentrantServerSink {
        fn on_event(&self, kind: u32, object_id: u64, _arg0: i64, _arg1: i64) {
            if kind == crate::event::NB_EVENT_ACCEPTED
                && !self.stopped_in_callback.swap(true, Ordering::SeqCst)
                && let Some(ctx) = self.ctx.read().unwrap().as_ref()
            {
                // Synchronously call stop_server on ACCEPTED to verify there is no deadlock
                // (INV-4, P0-RUST-01, P0-RUST-02)
                assert!(ctx.stop_server(object_id).is_ok());
            }
        }
    }

    #[test]
    fn reentrant_callback_accepted_calls_server_stop_no_deadlock() {
        let sink = Arc::new(ReentrantServerSink {
            ctx: std::sync::RwLock::new(None),
            stopped_in_callback: AtomicBool::new(false),
        });
        let ctx = NativeContext::new(2, Some(sink.clone())).expect("context");
        *sink.ctx.write().unwrap() = Some(ctx.clone());

        let server_id = 500;
        let conn_count = Arc::new(AtomicUsize::new(0));
        let state = Arc::new(AtomicU8::new(crate::SERVER_STATE_RUNNING));
        let commit_lock = Arc::new(Mutex::new(()));
        let stopped_pair = Arc::new((Mutex::new(true), std::sync::Condvar::new()));
        let (stop_tx, _) = tokio::sync::mpsc::channel(1);

        ctx.servers_map().insert(
            server_id,
            Arc::new(ServerHandle {
                endpoint: TransportEndpoint::Quic(stop_tx),
                port: 23456,
                max_connections: 16,
                conn_count: Arc::clone(&conn_count),
                state: Arc::clone(&state),
                commit_lock,
                stopped_pair,
            }),
        );

        let handle = test_handle(
            Arc::new(AtomicU32::new(STATE_CONNECTED)),
            Some(server_id),
            Some(conn_count),
            false,
        );

        let accepted = ctx.try_commit_accept(server_id, 1001, Arc::new(handle));
        assert!(accepted, "Commit accept must succeed");
        assert!(
            sink.stopped_in_callback.load(Ordering::SeqCst),
            "stop_server must be reentered from the callback"
        );
        assert!(
            !ctx.servers_map().contains_key(&server_id),
            "Server must be stopped and removed from the registry"
        );
    }
}
