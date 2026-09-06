//! 服务端 QUIC acceptor：endpoint 生命周期与连接 accept。

use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use crate::error::{BridgeError, Transport};
use crate::socket_util;
use crate::{ServerHandle, TransportEndpoint, try_admit};

/// 经 NativeContext 启动服务端 QUIC acceptor。
pub fn start_server_in_context(
    ctx: &Arc<crate::context::NativeContext>,
    port: u16,
    max_connections: usize,
    bind: Option<IpAddr>,
) -> Result<u64, BridgeError> {
    let server_config = quinn_plaintext::server_config();
    let endpoint = {
        let _guard = ctx.handle().enter();
        let (socket, _local) =
            socket_util::bind_server(port, bind).map_err(|source| BridgeError::Bind {
                transport: Transport::Quic,
                port,
                source,
            })?;
        quinn::Endpoint::new(
            quinn::EndpointConfig::default(),
            Some(server_config),
            socket,
            Arc::new(quinn::TokioRuntime),
        )
        .map_err(|source| BridgeError::Setup {
            transport: Transport::Quic,
            stage: "endpoint",
            source,
        })?
    };
    let actual_port = endpoint
        .local_addr()
        .map_err(|source| BridgeError::Setup {
            transport: Transport::Quic,
            stage: "local addr",
            source,
        })?
        .port();

    let server_id = ctx.allocate_id()?;
    let conn_count = Arc::new(AtomicUsize::new(0));
    let state = Arc::new(std::sync::atomic::AtomicU8::new(
        crate::SERVER_STATE_RUNNING,
    ));
    let commit_lock = Arc::new(std::sync::Mutex::new(()));
    let stopped_pair = Arc::new((std::sync::Mutex::new(false), std::sync::Condvar::new()));
    let (stop_tx, mut stop_rx) = tokio::sync::mpsc::channel::<()>(1);
    let accept_endpoint = endpoint.clone();
    let accept_counter = Arc::clone(&conn_count);
    let accept_stopped = Arc::clone(&stopped_pair);
    ctx.servers_map().insert(
        server_id,
        Arc::new(ServerHandle {
            endpoint: TransportEndpoint::Quic(stop_tx),
            port: actual_port,
            max_connections,
            conn_count,
            state,
            commit_lock,
            stopped_pair,
        }),
    );
    ctx.emit_server_state(server_id, crate::SERVER_STATE_RUNNING);

    let ctx_clone = Arc::clone(ctx);
    ctx.spawn_server_task("quic accept loop in context", server_id, async move {
        let mut incoming_set = tokio::task::JoinSet::new();
        loop {
            tokio::select! {
                _ = stop_rx.recv() => break,
                acc = accept_endpoint.accept() => {
                    let Some(incoming) = acc else {
                        break;
                    };
                    if accept_counter.load(Ordering::Relaxed) >= max_connections {
                        drop(incoming);
                        continue;
                    }
                    let conn_counter = Arc::clone(&accept_counter);
                    let c = Arc::clone(&ctx_clone);
                    incoming_set.spawn(serve_incoming_in_context(
                        c,
                        server_id,
                        incoming,
                        conn_counter,
                        max_connections,
                    ));
                }
                Some(_) = incoming_set.join_next(), if !incoming_set.is_empty() => {}
            }
        }
        incoming_set.abort_all();
        while incoming_set.join_next().await.is_some() {}
        let (lock, cvar) = &*accept_stopped;
        if let Ok(mut g) = lock.lock() {
            *g = true;
            cvar.notify_all();
        }
    });
    Ok(server_id)
}

async fn serve_incoming_in_context(
    ctx: Arc<crate::context::NativeContext>,
    server_id: u64,
    incoming: quinn::Incoming,
    conn_counter: Arc<AtomicUsize>,
    max_connections: usize,
) {
    let peer = incoming.remote_address();
    let Ok(conn) = incoming.await else {
        return;
    };
    if !try_admit(&conn_counter, max_connections) {
        return;
    }

    // Await bidirectional data stream before committing and emitting ACCEPTED
    let (send, mut recv) = match conn.accept_bi().await {
        Ok(pair) => pair,
        Err(_) => {
            conn_counter.fetch_sub(1, Ordering::Relaxed);
            return;
        }
    };
    // Consume the 1-byte handshake probe
    let mut probe = [0u8; 1];
    if recv.read_exact(&mut probe).await.is_err() {
        conn_counter.fetch_sub(1, Ordering::Relaxed);
        return;
    }

    let Ok(conn_id) = ctx.allocate_id() else {
        conn_counter.fetch_sub(1, Ordering::Relaxed);
        return;
    };

    let state = Arc::new(std::sync::atomic::AtomicU32::new(crate::STATE_CONNECTED));
    let (cancel_tx, cancel_rx) = tokio::sync::watch::channel(false);
    let (to_transport_tx, to_transport_rx) = tokio::sync::mpsc::channel::<crate::Command>(4096);
    let (to_java_tx, to_java_rx) = tokio::sync::mpsc::channel::<bytes::Bytes>(8192);

    let handle = crate::ConnHandle::new(
        state.clone(),
        to_java_rx,
        to_transport_tx.clone(),
        cancel_tx,
        Some(server_id),
        Some(conn_counter.clone()),
        true,
        Some(peer),
    );

    // Linearized commit check with server lifecycle
    if !ctx.try_commit_accept(server_id, conn_id, Arc::new(handle)) {
        conn_counter.fetch_sub(1, Ordering::Relaxed);
        conn.close(0u32.into(), b"server stopped");
        return;
    }

    ctx.set_conn_remote_addr(conn_id, peer);
    let accept_ctx = Arc::clone(&ctx);
    let to_transport_tx_runner = to_transport_tx;
    ctx.spawn_connection_task("quic stream accept and drive", conn_id, async move {
        super::connection::run_connection_with_sink(
            conn_id,
            conn,
            cancel_rx,
            send,
            recv,
            to_transport_rx,
            to_java_tx,
            to_transport_tx_runner,
            state,
            accept_ctx,
        )
        .await;
    });
}
