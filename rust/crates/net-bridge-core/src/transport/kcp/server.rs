//! KCP server: acceptor lifecycle and connection admission using the built-in kcp-rs handshake.

use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use kcp::{KcpConfig, KcpStream, KcpUdpStream};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use super::config::{KcpProfile, build_config};
use super::fec_stream::FecStream;
use crate::error::{BridgeError, Transport};
use crate::socket_util;
use crate::{ServerHandle, try_admit};

/// Starts a KCP server through NativeContext.
pub fn start_server_in_context(
    ctx: &Arc<crate::context::NativeContext>,
    port: u16,
    max_connections: usize,
    bind: Option<IpAddr>,
    profile: KcpProfile,
    startup_timeout: Duration,
) -> Result<u64, BridgeError> {
    let config = build_config(profile);
    let server_id = ctx.allocate_id()?;

    let (tx, rx) = std::sync::mpsc::channel::<Result<u16, BridgeError>>();
    let shutdown = CancellationToken::new();
    let task_shutdown = shutdown.clone();
    let ctx_clone = Arc::clone(ctx);
    ctx.spawn_server_task("kcp server task in context", server_id, async move {
        KcpServerTask {
            ctx: ctx_clone,
            server_id,
            port,
            bind,
            max_connections,
            config,
            startup_tx: tx,
            shutdown: task_shutdown,
        }
        .run()
        .await;
    });

    let result = match rx.recv_timeout(startup_timeout) {
        Ok(Ok(_port)) => Ok(server_id),
        Ok(Err(msg)) => Err(msg),
        Err(_) => Err(BridgeError::Timeout),
    };
    if result.is_err() {
        shutdown.cancel();
        ctx.servers_map().remove(&server_id);
    }
    result
}

struct KcpServerTask {
    ctx: Arc<crate::context::NativeContext>,
    server_id: u64,
    port: u16,
    bind: Option<IpAddr>,
    max_connections: usize,
    config: KcpConfig,
    startup_tx: std::sync::mpsc::Sender<Result<u16, BridgeError>>,
    shutdown: CancellationToken,
}

impl KcpServerTask {
    async fn run(self) {
        let (listener, local) =
            match bind_listener(self.port, self.bind, self.max_connections, &self.config).await {
                Ok(pair) => pair,
                Err(error) => {
                    let _ = self.startup_tx.send(Err(error));
                    return;
                }
            };
        let conn_count = Arc::new(AtomicUsize::new(0));
        let state = Arc::new(std::sync::atomic::AtomicU8::new(
            crate::SERVER_STATE_RUNNING,
        ));
        let commit_lock = Arc::new(std::sync::Mutex::new(()));
        let stopped_pair = Arc::new((std::sync::Mutex::new(false), std::sync::Condvar::new()));
        self.ctx.servers_map().insert(
            self.server_id,
            Arc::new(ServerHandle {
                shutdown: self.shutdown.clone(),
                port: local.port(),
                state: Arc::clone(&state),
                commit_lock: Arc::clone(&commit_lock),
                stopped_pair: Arc::clone(&stopped_pair),
            }),
        );
        if self.startup_tx.send(Ok(local.port())).is_err() {
            self.ctx.servers_map().remove(&self.server_id);
            drop(listener);
            return;
        }
        self.ctx
            .emit_server_state(self.server_id, crate::SERVER_STATE_RUNNING);

        KcpAcceptLoop {
            ctx: self.ctx,
            listener,
            shutdown: self.shutdown,
            server_id: self.server_id,
            max_connections: self.max_connections,
            conn_count,
            stopped_pair,
        }
        .run()
        .await;
    }
}

struct KcpAcceptLoop {
    ctx: Arc<crate::context::NativeContext>,
    listener: KcpUdpStream,
    shutdown: CancellationToken,
    server_id: u64,
    max_connections: usize,
    conn_count: Arc<AtomicUsize>,
    stopped_pair: Arc<(std::sync::Mutex<bool>, std::sync::Condvar)>,
}

impl KcpAcceptLoop {
    async fn run(mut self) {
        while self.accept_next().await {}
        self.publish_stopped();
        self.drain_adopted_connections().await;
    }

    async fn accept_next(&mut self) -> bool {
        let accepted = tokio::select! {
            _ = self.shutdown.cancelled() => None,
            accepted = self.listener.accept() => accepted.ok(),
        };
        let Some((stream, peer)) = accepted else {
            return false;
        };
        if !admit(&self.conn_count, self.max_connections) {
            return true;
        }
        self.adopt(stream, peer).await;
        true
    }

    async fn adopt(&mut self, stream: KcpStream, peer: SocketAddr) {
        let state = Arc::new(std::sync::atomic::AtomicU32::new(crate::STATE_CONNECTED));
        let Ok(conn_id) = self.ctx.allocate_id() else {
            self.conn_count.fetch_sub(1, Ordering::Relaxed);
            return;
        };
        let (mc_stream, session) =
            match super::connection::prepare_kcp_data_plane(FecStream::new(stream), false).await {
                Ok(data_plane) => data_plane,
                Err(error) => {
                    crate::report_error(format!("kcp conn {conn_id}: {error}"));
                    self.conn_count.fetch_sub(1, Ordering::Relaxed);
                    return;
                }
            };
        let (to_transport_tx, to_transport_rx) = mpsc::channel::<crate::Command>(4096);
        let (to_java_tx, to_java_rx) = mpsc::channel::<bytes::Bytes>(8192);
        let cancel = CancellationToken::new();
        let data_plane_cancel = cancel.clone();
        let handle = crate::ConnHandle::server(
            state.clone(),
            to_java_rx,
            to_transport_tx,
            cancel,
            Arc::clone(&self.conn_count),
            peer,
        );
        if !self
            .ctx
            .try_commit_accept(self.server_id, conn_id, Arc::new(handle))
        {
            self.conn_count.fetch_sub(1, Ordering::Relaxed);
            let _ = session.close().await;
            return;
        }
        self.ctx.set_conn_remote_addr(conn_id, peer);
        let counters = self
            .ctx
            .conns()
            .get(&conn_id)
            .map(|handle| handle.counters())
            .unwrap_or_default();
        self.ctx.spawn_connection_task(
            "kcp connection task in context",
            conn_id,
            super::connection::KcpDataPlane {
                conn_id,
                stream: mc_stream,
                session,
                cancel: data_plane_cancel,
                to_transport_rx,
                to_java_tx,
                state,
                ctx: Arc::clone(&self.ctx),
                counters,
            }
            .run(),
        );
    }

    fn publish_stopped(&self) {
        let (lock, condvar) = &*self.stopped_pair;
        if let Ok(mut stopped) = lock.lock() {
            *stopped = true;
            condvar.notify_all();
        }
    }

    async fn drain_adopted_connections(&mut self) {
        while self.conn_count.load(Ordering::SeqCst) > 0 {
            tokio::select! {
                _ = tokio::time::sleep(Duration::from_millis(20)) => {}
                accepted = self.listener.accept() => {
                    if let Ok((stream, _)) = accepted {
                        drop(stream);
                    }
                }
            }
        }
    }
}

async fn bind_listener(
    port: u16,
    bind: Option<IpAddr>,
    max_connections: usize,
    config: &KcpConfig,
) -> Result<(KcpUdpStream, SocketAddr), BridgeError> {
    let (socket, _) = socket_util::bind_server(port, bind).map_err(|source| BridgeError::Bind {
        transport: Transport::Kcp,
        port,
        source,
    })?;
    let socket = nonblocking(socket).map_err(|source| BridgeError::Setup {
        transport: Transport::Kcp,
        stage: "set_nonblocking",
        source,
    })?;
    let udp = UdpSocket::from_std(socket).map_err(|source| BridgeError::Setup {
        transport: Transport::Kcp,
        stage: "from_std",
        source,
    })?;
    let local = udp.local_addr().map_err(|source| BridgeError::Setup {
        transport: Transport::Kcp,
        stage: "local addr",
        source,
    })?;
    let listener =
        KcpUdpStream::socket_listen(Arc::new(config.clone()), udp, max_connections.max(8), None)
            .map_err(|source| BridgeError::Setup {
                transport: Transport::Kcp,
                stage: "socket_listen",
                source,
            })?;
    Ok((listener, local))
}

fn admit(conn_count: &AtomicUsize, max: usize) -> bool {
    conn_count.load(Ordering::Relaxed) < max && try_admit(conn_count, max)
}

fn nonblocking(s: std::net::UdpSocket) -> std::io::Result<std::net::UdpSocket> {
    s.set_nonblocking(true)?;
    Ok(s)
}
