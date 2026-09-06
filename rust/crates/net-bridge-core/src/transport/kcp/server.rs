//! KCP 服务端：acceptor 生命周期与连接接纳（kcp-rs 内建握手）。

use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use kcp::{KcpConfig, KcpUdpStream};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;

use super::config::{KcpProfile, build_config};
use super::fec_stream::FecStream;
use crate::error::{BridgeError, Transport};
use crate::socket_util;
use crate::{ServerHandle, TransportEndpoint, try_admit};

/// 经 NativeContext 启动 KCP 服务端。
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
    let (stop_tx, stop_rx) = mpsc::channel::<()>(1);
    let ctx_clone = Arc::clone(ctx);
    ctx.spawn_server_task("kcp server task in context", server_id, async move {
        let c = Arc::clone(&ctx_clone);
        server_task_in_context(
            c,
            server_id,
            port,
            bind,
            max_connections,
            config,
            tx,
            stop_tx,
            stop_rx,
        )
        .await;
    });

    let result = match rx.recv_timeout(startup_timeout) {
        Ok(Ok(_port)) => Ok(server_id),
        Ok(Err(msg)) => Err(msg),
        Err(_) => Err(BridgeError::Timeout),
    };
    if result.is_err() {
        // 启动窗口超时：回滚任何已插入的 registry 条目，杜绝 orphan server。
        ctx.servers_map().remove(&server_id);
    }
    result
}

#[allow(clippy::too_many_arguments)]
async fn server_task_in_context(
    ctx: Arc<crate::context::NativeContext>,
    server_id: u64,
    port: u16,
    bind: Option<IpAddr>,
    max_connections: usize,
    config: KcpConfig,
    tx: std::sync::mpsc::Sender<Result<u16, BridgeError>>,
    stop_tx: mpsc::Sender<()>,
    mut stop_rx: mpsc::Receiver<()>,
) {
    let (listener, local) = match bind_listener(port, bind, max_connections, &config).await {
        Ok(pair) => pair,
        Err(msg) => {
            let _ = tx.send(Err(msg));
            return;
        }
    };
    let conn_count = Arc::new(AtomicUsize::new(0));
    let state = Arc::new(std::sync::atomic::AtomicU8::new(
        crate::SERVER_STATE_RUNNING,
    ));
    let commit_lock = Arc::new(std::sync::Mutex::new(()));
    let stopped_pair = Arc::new((std::sync::Mutex::new(false), std::sync::Condvar::new()));
    ctx.servers_map().insert(
        server_id,
        ServerHandle {
            endpoint: TransportEndpoint::Kcp(stop_tx),
            port: local.port(),
            max_connections,
            conn_count: Arc::clone(&conn_count),
            state: Arc::clone(&state),
            commit_lock: Arc::clone(&commit_lock),
            stopped_pair: Arc::clone(&stopped_pair),
        },
    );
    ctx.event_sink().on_event(
        crate::event::NB_EVENT_SERVER_STATE,
        server_id,
        crate::event::NB_SERVER_STATE_RUNNING as i64,
        0,
    );
    let _ = tx.send(Ok(local.port()));

    let ctx_clone = Arc::clone(&ctx);
    accept_loop_in_context(
        ctx_clone,
        listener,
        &mut stop_rx,
        server_id,
        max_connections,
        conn_count,
        stopped_pair,
    )
    .await;
}

async fn accept_loop_in_context(
    ctx: Arc<crate::context::NativeContext>,
    mut listener: KcpUdpStream,
    stop_rx: &mut mpsc::Receiver<()>,
    server_id: u64,
    max_connections: usize,
    conn_count: Arc<AtomicUsize>,
    stopped_pair: Arc<(std::sync::Mutex<bool>, std::sync::Condvar)>,
) {
    loop {
        let accepted = tokio::select! {
            _ = stop_rx.recv() => None,
            acc = listener.accept() => acc.ok(),
        };
        let Some((stream, peer)) = accepted else {
            break;
        };
        if !admit(&conn_count, max_connections) {
            continue;
        }
        let state = Arc::new(std::sync::atomic::AtomicU32::new(crate::STATE_CONNECTED));
        let Ok(conn_id) = ctx.allocate_id() else {
            conn_count.fetch_sub(1, Ordering::Relaxed);
            continue;
        };
        let kcp_fec = FecStream::new(stream);
        let Some((mc_stream, session)) =
            super::connection::prepare_kcp_data_plane(conn_id, kcp_fec, false, &state, &ctx).await
        else {
            conn_count.fetch_sub(1, Ordering::Relaxed);
            continue;
        };
        let (to_transport_tx, to_transport_rx) = mpsc::channel::<crate::Command>(4096);
        let (to_java_tx, to_java_rx) = mpsc::channel::<bytes::Bytes>(8192);
        let (cancel_tx, cancel_rx) = tokio::sync::watch::channel(false);

        let handle = crate::ConnHandle::new(
            state.clone(),
            to_java_rx,
            to_transport_tx,
            cancel_tx,
            Some(server_id),
            Some(Arc::clone(&conn_count)),
            false,
            Some(peer),
        );

        if !ctx.try_commit_accept(server_id, conn_id, handle) {
            conn_count.fetch_sub(1, Ordering::Relaxed);
            let _ = session.close().await;
            continue;
        }

        ctx.set_conn_remote_addr(conn_id, peer);

        ctx.spawn_connection_task(
            "kcp connection task in context",
            conn_id,
            super::connection::run_kcp_connection_with_sink(
                conn_id,
                mc_stream,
                session,
                cancel_rx,
                to_transport_rx,
                to_java_tx,
                state,
                false,
                Arc::clone(&ctx),
            ),
        );
    }
    // Accept loop has stopped accepting new connections.
    // Signal stop completion immediately so stop_server unblocks.
    let (lock, cvar) = &*stopped_pair;
    if let Ok(mut g) = lock.lock() {
        *g = true;
        cvar.notify_all();
    }

    // If there are active adopted sessions, keep listener alive in background until
    // all active sessions disconnect (conn_count drops to 0) or context shuts down.
    while conn_count.load(Ordering::SeqCst) > 0 {
        tokio::select! {
            _ = tokio::time::sleep(Duration::from_millis(20)) => {},
            _ = listener.accept() => {},
        }
    }
    drop(listener);
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
