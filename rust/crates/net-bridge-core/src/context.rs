//! NativeContext: root of instance-level runtime ownership.

mod io;
mod shutdown;
mod tasks;

use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU8, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use dashmap::DashMap;
use tokio::runtime::{Builder, Runtime};

use crate::error::BridgeError;
use crate::event::{EventSink, NoopEventSink};
use crate::transport::TransportKind;
use crate::transport::kcp::config::KcpProfile;
use crate::{Command, ConnHandle, STATE_CLOSED, STATE_FAILED, ServerHandle};

/// Production KCP listener startup window.
pub const STARTUP_TIMEOUT: Duration = Duration::from_secs(5);

pub const CONTEXT_STATE_RUNNING: u8 = 0;
pub const CONTEXT_STATE_SHUTTING_DOWN: u8 = 1;
pub const CONTEXT_STATE_CLOSED: u8 = 2;

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
}

impl NativeContext {
    /// Creates a new NativeContext instance.
    pub fn new(
        worker_threads: usize,
        event_sink: Option<Arc<dyn EventSink>>,
    ) -> Result<Arc<Self>, BridgeError> {
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
        }))
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

    pub(crate) fn conns(&self) -> &DashMap<u64, Arc<ConnHandle>> {
        &self.connections
    }

    pub(crate) fn servers_map(&self) -> &DashMap<u64, Arc<ServerHandle>> {
        &self.servers
    }

    pub(crate) fn remove_conn(&self, conn_id: u64) -> Option<Arc<ConnHandle>> {
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
    /// or is not running. The ACCEPTED callback runs only after all locks and registry guards are
    /// released.
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
        let committed = {
            let _guard = server.commit_lock.lock().unwrap_or_else(|p| p.into_inner());
            if !server.is_running() {
                false
            } else {
                self.connections.insert(conn_id, handle);
                true
            }
        };
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

    /// Emits a successful-connection event (CONNECTED) after releasing the registry guard.
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
        let to_transport = handle.request_close();
        drop(handle);
        let _ = to_transport.try_send(Command::Close);
        self.remove_conn(conn);
        true
    }

    /// Emits a non-terminal event (DATA_AVAILABLE / WRITABLE) after releasing the registry guard.
    /// Silently drops the event if terminal state has already been marked.
    pub(crate) fn emit_non_terminal(&self, conn_id: u64, kind: u32, arg0: i64, arg1: i64) -> bool {
        let handle = self.connections.get(&conn_id).map(|h| Arc::clone(&*h));
        if let Some(handle) = handle {
            handle.emit_non_terminal(&*self.event_sink, conn_id, kind, arg0, arg1)
        } else {
            false
        }
    }

    /// Emits the terminal event (FAILED/CLOSED) exactly once and keeps the entry as a tombstone until
    /// Java releases it. The callback runs after the registry guard is released.
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

    /// Commits terminal state while retaining the tombstone and emits the terminal event exactly once.
    /// The callback runs after the registry guard is released.
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
        let shutdown = {
            let _guard = handle.commit_lock.lock().unwrap_or_else(|p| p.into_inner());
            let curr = handle.state.load(Ordering::SeqCst);
            if curr == crate::SERVER_STATE_STOPPED {
                return Ok(());
            }
            handle
                .state
                .store(crate::SERVER_STATE_STOPPED, Ordering::SeqCst);
            handle.shutdown.clone()
        };
        // commit_lock has been released
        shutdown.cancel();
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
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::STATE_CONNECTED;
    use bytes::Bytes;
    use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize};
    use std::time::Instant;
    use tokio_util::sync::CancellationToken;

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
                ctx.write_chunk(client, Bytes::copy_from_slice(&payload))
                    .expect("write"),
                payload.len()
            );
            let read_deadline = Instant::now() + Duration::from_secs(5);
            let mut got = 0usize;
            while got < payload.len() && Instant::now() < read_deadline {
                match ctx.read_chunk(server_conn, 65536) {
                    Ok(data) if !data.is_empty() => got += data.len(),
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
        ctx.conns().insert(
            7,
            Arc::new(ConnHandle::client(
                state.clone(),
                {
                    let (_tx, rx) = tokio::sync::mpsc::channel(1);
                    rx
                },
                {
                    let (tx, _rx) = tokio::sync::mpsc::channel(1);
                    tx
                },
                CancellationToken::new(),
                false,
                None,
            )),
        );
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
    fn byte_budget_limits_outbound_and_releases_properly() {
        let (ctx, sink) = recording_ctx();
        let (to_transport_tx, mut to_transport_rx) = tokio::sync::mpsc::channel::<Command>(4096);
        let (_to_java_tx, to_java_rx) = tokio::sync::mpsc::channel::<Bytes>(8192);
        ctx.conns().insert(
            42,
            Arc::new(ConnHandle::client(
                Arc::new(AtomicU32::new(STATE_CONNECTED)),
                to_java_rx,
                to_transport_tx,
                CancellationToken::new(),
                false,
                None,
            )),
        );

        // 64 KiB
        let chunk = Bytes::copy_from_slice(&vec![0xAAu8; crate::MAX_IO_CHUNK]);
        // 64 chunks = 4 MiB
        let total_chunks = crate::DEFAULT_MAX_BUFFERED_BYTES / crate::MAX_IO_CHUNK;

        for i in 0..total_chunks {
            let res = ctx.write_chunk(42, chunk.clone()).expect("write ok");
            assert_eq!(res, crate::MAX_IO_CHUNK, "chunk {i} should be accepted");
        }

        // Now byte budget is exhausted (4 MiB buffered)
        let over_res = ctx.write_chunk(42, chunk.clone()).expect("would block");
        assert_eq!(
            over_res, 0,
            "exceeding byte budget must return 0 / would_block"
        );

        // Drain one chunk from to_transport_rx
        let cmd = to_transport_rx.try_recv().expect("cmd");
        if let Command::Write(b) = cmd {
            let conn = ctx.conns().get(&42).unwrap();
            conn.outbound_bytes.fetch_sub(b.len(), Ordering::SeqCst);
            if conn.write_blocked.swap(false, Ordering::SeqCst) {
                ctx.event_sink()
                    .on_event(crate::event::NB_EVENT_WRITABLE, 42, 0, 0);
            }
        }

        // Verify WRITABLE event is fired
        let writable_events: Vec<_> = sink
            .0
            .lock()
            .unwrap()
            .iter()
            .filter(|(k, o, _, _)| *k == crate::event::NB_EVENT_WRITABLE && *o == 42)
            .cloned()
            .collect();
        assert_eq!(
            writable_events.len(),
            1,
            "WRITABLE event must be emitted on edge"
        );

        // Now we can write one more chunk
        let next_res = ctx
            .write_chunk(42, chunk.clone())
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
        let (to_transport_tx, _) = tokio::sync::mpsc::channel(16);
        let (_, to_java_rx) = tokio::sync::mpsc::channel(16);
        let conn_id = 999;
        ctx.conns().insert(
            conn_id,
            Arc::new(ConnHandle::client(
                Arc::new(AtomicU32::new(STATE_CONNECTED)),
                to_java_rx,
                to_transport_tx,
                CancellationToken::new(),
                false,
                None,
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
        let server_id = 100;
        let conn_count = Arc::new(AtomicUsize::new(1));
        let state = Arc::new(AtomicU8::new(crate::SERVER_STATE_RUNNING));
        let commit_lock = Arc::new(Mutex::new(()));
        let stopped_pair = Arc::new((Mutex::new(true), std::sync::Condvar::new()));

        ctx.servers_map().insert(
            server_id,
            Arc::new(ServerHandle {
                shutdown: CancellationToken::new(),
                port: 12345,
                state: Arc::clone(&state),
                commit_lock: Arc::clone(&commit_lock),
                stopped_pair,
            }),
        );

        // Stop the server
        assert!(ctx.stop_server(server_id).is_ok());

        // Now an accept attempt comes in after stop linearization:
        let (to_transport_tx, _) = tokio::sync::mpsc::channel(16);
        let (_, to_java_rx) = tokio::sync::mpsc::channel(16);
        let handle = ConnHandle::server(
            Arc::new(AtomicU32::new(STATE_CONNECTED)),
            to_java_rx,
            to_transport_tx,
            CancellationToken::new(),
            conn_count,
            "127.0.0.1:1".parse().expect("remote address"),
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
    fn saturated_command_queue_does_not_block_close() {
        let (ctx, _) = recording_ctx();
        let (to_transport_tx, _to_transport_rx) = tokio::sync::mpsc::channel(1);
        let (_, to_java_rx) = tokio::sync::mpsc::channel(16);
        let cancel = CancellationToken::new();
        let cancel_probe = cancel.clone();
        let conn_id = 777;

        // Fill the 1-capacity command channel
        let _ = to_transport_tx.try_send(Command::Write(Bytes::from_static(b"fill")));

        ctx.conns().insert(
            conn_id,
            Arc::new(ConnHandle::client(
                Arc::new(AtomicU32::new(STATE_CONNECTED)),
                to_java_rx,
                to_transport_tx,
                cancel,
                false,
                None,
            )),
        );

        // Closing a connection with a saturated command queue must succeed immediately out-of-band
        assert!(ctx.close_connection(conn_id));
        assert!(
            cancel_probe.is_cancelled(),
            "Cancellation signal must be delivered"
        );
        assert!(
            !ctx.conns().contains_key(&conn_id),
            "Registry entry must be removed"
        );
    }

    #[test]
    fn partial_read_accounting_is_exact() {
        let (ctx, _) = recording_ctx();
        let (to_transport_tx, _) = tokio::sync::mpsc::channel(16);
        let (to_java_tx, to_java_rx) = tokio::sync::mpsc::channel(16);
        let conn_id = 888;
        let handle = ConnHandle::client(
            Arc::new(AtomicU32::new(STATE_CONNECTED)),
            to_java_rx,
            to_transport_tx,
            CancellationToken::new(),
            false,
            None,
        );
        let inbound_bytes = Arc::clone(&handle.inbound_bytes);
        ctx.conns().insert(conn_id, Arc::new(handle));

        // Put 100 bytes
        inbound_bytes.store(100, Ordering::SeqCst);
        let _ = to_java_tx.try_send(Bytes::copy_from_slice(&[42u8; 100]));

        // Read 30 bytes
        let first = ctx.read_chunk(conn_id, 30).expect("read");
        assert_eq!(first.len(), 30);
        assert_eq!(
            inbound_bytes.load(Ordering::SeqCst),
            70,
            "Inbound budget must decrease by exactly 30"
        );

        // Read remaining 70 bytes
        let second = ctx.read_chunk(conn_id, 100).expect("read");
        assert_eq!(second.len(), 70);
        assert_eq!(
            inbound_bytes.load(Ordering::SeqCst),
            0,
            "Inbound budget must return to 0"
        );
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

        let (to_transport_tx, _) = tokio::sync::mpsc::channel(16);
        let (_, to_java_rx) = tokio::sync::mpsc::channel(16);
        let conn_id = 999;
        let handle = ConnHandle::client(
            Arc::new(AtomicU32::new(crate::STATE_CONNECTING)),
            to_java_rx,
            to_transport_tx,
            CancellationToken::new(),
            false,
            None,
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
                // Synchronously call stop_server on ACCEPTED to verify there is no deadlock.
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
        ctx.servers_map().insert(
            server_id,
            Arc::new(ServerHandle {
                shutdown: CancellationToken::new(),
                port: 23456,
                state: Arc::clone(&state),
                commit_lock,
                stopped_pair,
            }),
        );

        let (to_transport_tx, _) = tokio::sync::mpsc::channel(16);
        let (_, to_java_rx) = tokio::sync::mpsc::channel(16);
        let handle = ConnHandle::server(
            Arc::new(AtomicU32::new(STATE_CONNECTED)),
            to_java_rx,
            to_transport_tx,
            CancellationToken::new(),
            conn_count,
            "127.0.0.1:1".parse().expect("remote address"),
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
