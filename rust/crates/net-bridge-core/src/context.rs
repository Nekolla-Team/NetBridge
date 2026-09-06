//! NativeContext：实例级运行时所有权根节点。

use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU8, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use bytes::Bytes;
use dashmap::DashMap;
use futures_util::FutureExt;
use tokio::runtime::{Builder, Runtime};

use crate::error::BridgeError;
use crate::event::{EventSink, NoopEventSink};
use crate::transport::TransportKind;
use crate::transport::kcp::config::KcpProfile;
use crate::{
    Command, ConnHandle, STATE_CLOSED, STATE_CONNECTED, STATE_FAILED, ServerHandle,
    TransportEndpoint,
};

/// 生产 KCP listener 启动窗口。
pub const STARTUP_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);

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
    /// 创建新的 NativeContext 实例。
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

    /// 分配永不复用、永不产生的 id；回绕即 context 级 fatal。
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

    /// 尝试原子提交接受连接：如果服务端已停止或不在运行状态，则原子失败。
    /// 关键：所有锁与 DashMap guard 释放后才调用 foreign callback（INV-4）。
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
        // DashMap guard 已释放
        let committed = {
            let _guard = match server.commit_lock.lock() {
                Ok(g) => g,
                Err(p) => p.into_inner(),
            };
            if !server.is_running() {
                false
            } else {
                self.connections.insert(conn_id, handle);
                true
            }
        };
        // commit_lock 已释放
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

    /// 发送连接成功事件（CONNECTED）。
    /// 关键：DashMap guard 在 callback 前释放（INV-4）。
    pub(crate) fn emit_connected(&self, conn_id: u64) -> bool {
        let handle = self.connections.get(&conn_id).map(|h| Arc::clone(&*h));
        if let Some(handle) = handle {
            handle.emit_connected(&*self.event_sink, conn_id)
        } else {
            false
        }
    }

    /// 发送服务端状态事件（SERVER_STATE）。
    pub(crate) fn emit_server_state(&self, server_id: u64, state: u8) {
        self.event_sink().on_event(
            crate::event::NB_EVENT_SERVER_STATE,
            server_id,
            state as i64,
            0,
        );
    }

    /// Java 侧 release：发送 Close 并移除注册表条目（连接 wrapper 是 entry 的 owner）。
    pub fn close_connection(&self, conn: u64) -> bool {
        let handle = match self.connections.get(&conn).map(|h| Arc::clone(&*h)) {
            Some(h) => h,
            None => return false,
        };
        // DashMap guard 已在获取 Arc 后立即释放
        let _ = handle.emit_terminal(&*self.event_sink, conn, STATE_CLOSED, 0);
        let to_transport = handle.to_transport.clone();
        let _ = handle.cancel_tx.send(true);
        drop(handle);
        let _ = to_transport.try_send(Command::Close);
        self.remove_conn(conn);
        true
    }

    /// 发送非终态事件（DATA_AVAILABLE / WRITABLE）。如果终态已标记则静默丢弃。
    /// 关键：DashMap guard 在 callback 前释放（INV-4）。
    pub(crate) fn emit_non_terminal(&self, conn_id: u64, kind: u32, arg0: i64, arg1: i64) -> bool {
        let handle = self.connections.get(&conn_id).map(|h| Arc::clone(&*h));
        if let Some(handle) = handle {
            handle.emit_non_terminal(&*self.event_sink, conn_id, kind, arg0, arg1)
        } else {
            false
        }
    }

    /// 终态事件（FAILED/CLOSED）恰好一次；entry 保留为 tombstone 直到 Java release。
    /// 关键：DashMap guard 在 callback 前释放（INV-4）。
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

    /// 终态落账（tombstone 保留）+ 恰好一次的终态事件。
    /// 关键：DashMap guard 在 callback 前释放（INV-4）。
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

    /// panic-in-poll 防护：真实捕获 future poll 期间的 panic（单层任务，无嵌套 spawn）。
    /// 注册优先于执行：保证任务在 registry 插入完成后才可退出/移除，杜绝 stale handle 竞态。
    /// RAII Guard 保证任务无论是正常退出、panic 还是被 abort，均能从 conn_tasks 移除。
    pub(crate) fn spawn_connection_task<F>(
        self: &Arc<Self>,
        what: &'static str,
        conn_id: u64,
        fut: F,
    ) where
        F: std::future::Future<Output = ()> + Send + 'static,
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

    /// 服务端任务 panic → 状态置为 FAILED 并发出 SERVER_STATE 事件。
    /// 单层任务，无嵌套 spawn，注册优先于执行，RAII Guard 保证清理。
    pub(crate) fn spawn_server_task<F>(self: &Arc<Self>, what: &'static str, server_id: u64, fut: F)
    where
        F: std::future::Future<Output = ()> + Send + 'static,
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

    pub fn write_chunk(&self, conn: u64, data: Bytes) -> Result<usize, BridgeError> {
        if data.is_empty() {
            return Ok(0);
        }
        let len = data.len();
        if len > crate::MAX_IO_CHUNK {
            return Err(BridgeError::InvalidArgument(
                "chunk size exceeds MAX_IO_CHUNK",
            ));
        }
        let Some(handle) = self.connections.get(&conn) else {
            return Err(BridgeError::NoSuchConnection);
        };
        let writable = handle.state.load(Ordering::SeqCst) == STATE_CONNECTED || handle.early_write;
        let write_blocked = Arc::clone(&handle.write_blocked);
        let outbound_bytes = Arc::clone(&handle.outbound_bytes);
        let to_transport = handle.to_transport.clone();
        drop(handle);
        if !writable {
            return Ok(0);
        }

        // Reserve byte budget atomically
        let mut curr_bytes = outbound_bytes.load(Ordering::SeqCst);
        loop {
            if curr_bytes.saturating_add(len) > crate::DEFAULT_MAX_BUFFERED_BYTES {
                write_blocked.store(true, Ordering::SeqCst);
                // Double check to prevent lost-wakeup race
                if outbound_bytes.load(Ordering::SeqCst) < crate::DEFAULT_MAX_BUFFERED_BYTES
                    && write_blocked.swap(false, Ordering::SeqCst)
                {
                    self.emit_non_terminal(conn, crate::event::NB_EVENT_WRITABLE, 0, 0);
                }
                return Ok(0);
            }
            match outbound_bytes.compare_exchange_weak(
                curr_bytes,
                curr_bytes + len,
                Ordering::SeqCst,
                Ordering::SeqCst,
            ) {
                Ok(_) => break,
                Err(actual) => curr_bytes = actual,
            }
        }

        match to_transport.try_send(Command::Write(data)) {
            Ok(()) => Ok(len),
            Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {
                // Rollback byte budget
                outbound_bytes.fetch_sub(len, Ordering::SeqCst);
                write_blocked.store(true, Ordering::SeqCst);
                // Double check to prevent lost-wakeup race if consumer drained just before store
                if to_transport.capacity() > 0 && write_blocked.swap(false, Ordering::SeqCst) {
                    self.emit_non_terminal(conn, crate::event::NB_EVENT_WRITABLE, 0, 0);
                }
                Ok(0)
            }
            Err(_) => {
                // Rollback byte budget
                outbound_bytes.fetch_sub(len, Ordering::SeqCst);
                Err(BridgeError::ConnectionClosed)
            }
        }
    }

    pub fn read_chunk(&self, conn: u64, max_bytes: usize) -> Result<Bytes, BridgeError> {
        let Some(handle) = self.connections.get(&conn) else {
            return Err(BridgeError::NoSuchConnection);
        };
        let to_java = handle.to_java.clone();
        let inbound_bytes = Arc::clone(&handle.inbound_bytes);
        let read_waker = Arc::clone(&handle.read_waker);
        drop(handle);
        let mut guard = match to_java.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        let (rx, pending) = &mut *guard;

        let first = match pending.pop_front().or_else(|| rx.try_recv().ok()) {
            Some(b) => b,
            None => return Ok(Bytes::new()),
        };
        if first.len() > max_bytes {
            pending.push_front(first.slice(max_bytes..));
            inbound_bytes.fetch_sub(max_bytes, Ordering::SeqCst);
            read_waker.notify_one();
            return Ok(first.slice(..max_bytes));
        }

        match pending.pop_front().or_else(|| rx.try_recv().ok()) {
            None => {
                inbound_bytes.fetch_sub(first.len(), Ordering::SeqCst);
                read_waker.notify_one();
                Ok(first)
            }
            Some(second) => {
                let mut out = bytes::BytesMut::with_capacity(max_bytes);
                out.extend_from_slice(&first);
                let mut next = Some(second);
                while out.len() < max_bytes {
                    let Some(mut chunk) = next
                        .take()
                        .or_else(|| pending.pop_front().or_else(|| rx.try_recv().ok()))
                    else {
                        break;
                    };
                    if out.len() + chunk.len() > max_bytes {
                        let cut = max_bytes - out.len();
                        pending.push_front(chunk.slice(cut..));
                        chunk = chunk.slice(..cut);
                    }
                    out.extend_from_slice(&chunk);
                }
                let total_consumed = out.len();
                inbound_bytes.fetch_sub(total_consumed, Ordering::SeqCst);
                read_waker.notify_one();
                Ok(out.freeze())
            }
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
        // DashMap guard 已在获取 Arc 后释放
        let endpoint_stop = {
            let _guard = match handle.commit_lock.lock() {
                Ok(g) => g,
                Err(p) => p.into_inner(),
            };
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
        // commit_lock 已释放
        match &endpoint_stop {
            TransportEndpoint::Quic(stop_tx) => {
                let _ = stop_tx.try_send(());
            }
            TransportEndpoint::Kcp(stop_tx) => {
                let _ = stop_tx.try_send(());
            }
        }
        let (lock, cvar) = &*handle.stopped_pair;
        let mut guard = match lock.lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        let start = std::time::Instant::now();
        while !*guard {
            let elapsed = start.elapsed();
            if elapsed >= timeout {
                return Err(BridgeError::Timeout);
            }
            let (g, _) = match cvar.wait_timeout(guard, timeout.saturating_sub(elapsed)) {
                Ok(res) => res,
                Err(p) => p.into_inner(),
            };
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

        // 停止所有服务端
        let server_ids: Vec<u64> = self.servers.iter().map(|e| *e.key()).collect();
        for s_id in server_ids {
            let remaining = deadline.saturating_duration_since(std::time::Instant::now());
            if let Err(e) = self.stop_server_with_timeout(s_id, remaining)
                && shutdown_err.is_none()
            {
                shutdown_err = Some(e);
            }
        }

        // 关闭所有活跃连接
        let conn_ids: Vec<u64> = self.connections.iter().map(|e| *e.key()).collect();
        for c_id in conn_ids {
            self.close_connection(c_id);
        }

        // 等待所有连接和任务真正 join 退出
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

        // 确保所有注册表清空
        let remaining_conns: Vec<u64> = self.connections.iter().map(|e| *e.key()).collect();
        for c_id in remaining_conns {
            self.remove_conn(c_id);
        }
        self.conn_tasks.clear();
        self.server_tasks.clear();

        // 关闭 Tokio runtime
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

    struct RecordingSink(std::sync::Mutex<Vec<(u32, u64, i64, i64)>>);

    impl EventSink for RecordingSink {
        fn on_event(&self, kind: u32, object_id: u64, arg0: i64, arg1: i64) {
            self.0.lock().unwrap().push((kind, object_id, arg0, arg1));
        }
    }

    fn recording_ctx() -> (Arc<NativeContext>, Arc<RecordingSink>) {
        let sink = Arc::new(RecordingSink(std::sync::Mutex::new(Vec::new())));
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

        // 等待连接建立并关停
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while std::time::Instant::now() < deadline {
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
            let deadline = std::time::Instant::now() + Duration::from_secs(5);
            while std::time::Instant::now() < deadline {
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
            let read_deadline = std::time::Instant::now() + Duration::from_secs(5);
            let mut got = 0usize;
            while got < payload.len() && std::time::Instant::now() < read_deadline {
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
        let allocated_ids = Arc::new(std::sync::Mutex::new(Vec::new()));
        let mut handles = Vec::new();
        for _ in 0..num_threads {
            let ctx = Arc::clone(&ctx);
            let allocated_ids = Arc::clone(&allocated_ids);
            handles.push(std::thread::spawn(move || {
                for _ in 0..iters_per_thread {
                    if let Ok(id) = ctx.allocate_id() {
                        assert_ne!(id, 0, "ID 0 绝不能被分配");
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
        assert_eq!(unique_set.len(), ids.len(), "所有分配的 ID 必须全局唯一");
        for expected in (u64::MAX - start_offset)..=u64::MAX {
            assert!(
                unique_set.contains(&expected),
                "必须成功分配范围内的所有 ID"
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
            Arc::new(ConnHandle::new(
                state.clone(),
                {
                    let (_tx, rx) = tokio::sync::mpsc::channel(1);
                    rx
                },
                {
                    let (tx, _rx) = tokio::sync::mpsc::channel(1);
                    tx
                },
                {
                    let (tx, _rx) = tokio::sync::watch::channel(false);
                    tx
                },
                None,
                None,
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
            assert!(Instant::now() < deadline, "panic 未产生终态事件");
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(ctx.connection_state(7), Some(STATE_FAILED));
        assert!(ctx.conns().contains_key(&7), "tombstone 保留待 release");
        let events = sink.0.lock().unwrap().len();
        std::thread::sleep(Duration::from_millis(50));
        assert_eq!(sink.0.lock().unwrap().len(), events, "终态事件必须恰好一次");
        ctx.close_connection(7);
        assert_eq!(ctx.connection_state(7), None);
    }

    #[test]
    fn byte_budget_limits_outbound_and_releases_properly() {
        let (ctx, sink) = recording_ctx();
        let (to_transport_tx, mut to_transport_rx) = tokio::sync::mpsc::channel::<Command>(4096);
        let (_to_java_tx, to_java_rx) = tokio::sync::mpsc::channel::<Bytes>(8192);
        let (cancel_tx, _cancel_rx) = tokio::sync::watch::channel(false);

        ctx.conns().insert(
            42,
            Arc::new(ConnHandle::new(
                Arc::new(AtomicU32::new(STATE_CONNECTED)),
                to_java_rx,
                to_transport_tx,
                cancel_tx,
                None,
                None,
                false,
                None,
            )),
        );

        let chunk = Bytes::copy_from_slice(&vec![0xAAu8; crate::MAX_IO_CHUNK]); // 64 KiB
        let total_chunks = crate::DEFAULT_MAX_BUFFERED_BYTES / crate::MAX_IO_CHUNK; // 64 chunks = 4 MiB

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
        assert!(result.is_err(), "ZERO 超时必须失败");
        std::thread::sleep(Duration::from_millis(50));
        assert!(
            ctx.servers_map().is_empty(),
            "启动超时不得留下 orphan server: {:?}",
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
            .expect("client remote addr 必须在握手成功后记录");
        assert!(remote.port() > 0);
    }

    #[test]
    fn event_ordering_terminal_fences_non_terminal() {
        let (ctx, sink) = recording_ctx();
        let (to_transport_tx, _) = tokio::sync::mpsc::channel(16);
        let (_, to_java_rx) = tokio::sync::mpsc::channel(16);
        let (cancel_tx, _) = tokio::sync::watch::channel(false);
        let conn_id = 999;
        ctx.conns().insert(
            conn_id,
            Arc::new(ConnHandle::new(
                Arc::new(AtomicU32::new(STATE_CONNECTED)),
                to_java_rx,
                to_transport_tx,
                cancel_tx,
                None,
                None,
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
        let (stop_tx, _) = tokio::sync::mpsc::channel(1);
        let server_id = 100;
        let conn_count = Arc::new(AtomicUsize::new(1));
        let state = Arc::new(std::sync::atomic::AtomicU8::new(
            crate::SERVER_STATE_RUNNING,
        ));
        let commit_lock = Arc::new(std::sync::Mutex::new(()));
        let stopped_pair = Arc::new((std::sync::Mutex::new(true), std::sync::Condvar::new()));

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
        let (to_transport_tx, _) = tokio::sync::mpsc::channel(16);
        let (_, to_java_rx) = tokio::sync::mpsc::channel(16);
        let (cancel_tx, _) = tokio::sync::watch::channel(false);
        let handle = ConnHandle::new(
            Arc::new(AtomicU32::new(STATE_CONNECTED)),
            to_java_rx,
            to_transport_tx,
            cancel_tx,
            Some(server_id),
            Some(conn_count),
            false,
            None,
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
        let (cancel_tx, cancel_rx) = tokio::sync::watch::channel(false);
        let conn_id = 777;

        // Fill the 1-capacity command channel
        let _ = to_transport_tx.try_send(Command::Write(Bytes::from_static(b"fill")));

        ctx.conns().insert(
            conn_id,
            Arc::new(ConnHandle::new(
                Arc::new(AtomicU32::new(STATE_CONNECTED)),
                to_java_rx,
                to_transport_tx,
                cancel_tx,
                None,
                None,
                false,
                None,
            )),
        );

        // Closing a connection with a saturated command queue must succeed immediately out-of-band
        assert!(ctx.close_connection(conn_id));
        assert!(*cancel_rx.borrow(), "Cancellation signal must be delivered");
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
        let (cancel_tx, _) = tokio::sync::watch::channel(false);
        let conn_id = 888;
        let handle = ConnHandle::new(
            Arc::new(AtomicU32::new(STATE_CONNECTED)),
            to_java_rx,
            to_transport_tx,
            cancel_tx,
            None,
            None,
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
                // 同步重入调用 close_connection 以及查询状态，验证绝无死锁
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
        let (cancel_tx, _) = tokio::sync::watch::channel(false);
        let conn_id = 999;
        let handle = ConnHandle::new(
            Arc::new(AtomicU32::new(crate::STATE_CONNECTING)),
            to_java_rx,
            to_transport_tx,
            cancel_tx,
            None,
            None,
            false,
            None,
        );
        ctx.conns().insert(conn_id, Arc::new(handle));

        // 触发 CONNECTED 事件，sink 同步重入 close_connection
        assert!(ctx.emit_connected(conn_id));
        assert!(
            sink.reentered.load(Ordering::SeqCst),
            "Sink 必须被调用并重入"
        );
        assert!(!ctx.conns().contains_key(&conn_id), "连接必须被成功移除");
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
                // 收到 ACCEPTED 时同步调用 stop_server，验证绝无死锁（INV-4, P0-RUST-01, P0-RUST-02）
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
        let state = Arc::new(std::sync::atomic::AtomicU8::new(
            crate::SERVER_STATE_RUNNING,
        ));
        let commit_lock = Arc::new(std::sync::Mutex::new(()));
        let stopped_pair = Arc::new((std::sync::Mutex::new(true), std::sync::Condvar::new()));
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

        let (to_transport_tx, _) = tokio::sync::mpsc::channel(16);
        let (_, to_java_rx) = tokio::sync::mpsc::channel(16);
        let (cancel_tx, _) = tokio::sync::watch::channel(false);
        let handle = ConnHandle::new(
            Arc::new(AtomicU32::new(STATE_CONNECTED)),
            to_java_rx,
            to_transport_tx,
            cancel_tx,
            Some(server_id),
            Some(conn_count),
            false,
            None,
        );

        let accepted = ctx.try_commit_accept(server_id, 1001, Arc::new(handle));
        assert!(accepted, "Commit accept 必须成功");
        assert!(
            sink.stopped_in_callback.load(Ordering::SeqCst),
            "必须在 callback 中重入 stop_server"
        );
        assert!(
            !ctx.servers_map().contains_key(&server_id),
            "Server 必须被 stop 并移出注册表"
        );
    }
}
