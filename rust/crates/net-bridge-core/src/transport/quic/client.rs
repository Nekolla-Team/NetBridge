//! 客户端 QUIC 连接：异步握手，立即返回连接 id。

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use tokio::sync::mpsc;

use crate::error::{BridgeError, Transport};
use crate::report_error;
use crate::socket_util;
use crate::{Command, ConnHandle, STATE_CLOSED, STATE_CONNECTED, STATE_CONNECTING};

/// 经 NativeContext 发起 QUIC 客户端连接。
pub fn connect_in_context(
    ctx: &Arc<crate::context::NativeContext>,
    host: &str,
    port: u16,
) -> Result<u64, BridgeError> {
    let (to_transport_tx, to_transport_rx) = mpsc::channel::<Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Bytes>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
    let (cancel_tx, cancel_rx) = tokio::sync::watch::channel(false);
    let conn_id = ctx.allocate_id()?;
    ctx.conns().insert(
        conn_id,
        ConnHandle::new(
            state.clone(),
            to_java_rx,
            to_transport_tx.clone(),
            cancel_tx,
            None,
            None,
            true,
            None,
        ),
    );

    let host = host.to_string();
    let ctx_task = Arc::clone(ctx);
    ctx.spawn_connection_task("quic connect task in context", conn_id, async move {
        if let Some((conn, send, recv)) = establish(&ctx_task, &host, port, conn_id, &state).await {
            ctx_task.set_conn_remote_addr(conn_id, conn.remote_address());
            state.store(STATE_CONNECTED, Ordering::SeqCst);
            ctx_task.event_sink().on_event(
                crate::event::NB_EVENT_CONNECTION_STATE,
                conn_id,
                crate::event::abi_connection_state(STATE_CONNECTED) as i64,
                0,
            );
            super::connection::run_connection_with_sink(
                conn_id,
                conn,
                cancel_rx,
                send,
                recv,
                to_transport_rx,
                to_java_tx,
                to_transport_tx,
                state,
                Arc::clone(&ctx_task),
            )
            .await;
        } else {
            ctx_task.emit_terminal(conn_id);
        }
    });
    Ok(conn_id)
}

/// 建立连接：解析 → 绑定 → endpoint → 取消感知握手 → 双向流。
async fn establish(
    ctx: &crate::context::NativeContext,
    host: &str,
    port: u16,
    conn_id: u64,
    state: &Arc<AtomicU32>,
) -> Option<(quinn::Connection, quinn::SendStream, quinn::RecvStream)> {
    let addr = resolve_addr(ctx, host, port, conn_id, state).await?;
    let socket = match socket_util::bind_client(addr.is_ipv6()) {
        Ok(s) => s,
        Err(e) => {
            return fail(
                ctx,
                conn_id,
                state,
                BridgeError::Setup {
                    transport: Transport::Quic,
                    stage: "client bind",
                    source: e,
                },
            );
        }
    };
    let mut endpoint = match quinn::Endpoint::new(
        quinn::EndpointConfig::default(),
        None,
        socket,
        Arc::new(quinn::TokioRuntime),
    ) {
        Ok(ep) => ep,
        Err(e) => {
            return fail(
                ctx,
                conn_id,
                state,
                BridgeError::Setup {
                    transport: Transport::Quic,
                    stage: "endpoint",
                    source: e,
                },
            );
        }
    };
    endpoint.set_default_client_config(quinn_plaintext::client_config());

    let connecting = match endpoint.connect(addr, "plaintext.test") {
        Ok(connecting) => connecting,
        Err(e) => return fail(ctx, conn_id, state, connect_error(addr, e)),
    };
    let conn = match connecting.await {
        Ok(conn) => conn,
        Err(e) => return fail(ctx, conn_id, state, connect_error(addr, e)),
    };
    let (mut send, recv) = match conn.open_bi().await {
        Ok(pair) => pair,
        Err(e) => return fail(ctx, conn_id, state, connect_error(addr, e)),
    };
    // In Quinn / QUIC, opening a bidirectional stream is lazy until bytes are written.
    // Send 1-byte handshake probe (0x00) so peer's accept_bi() unblocks and discovers the stream!
    if let Err(e) = send.write_all(&[0x00]).await {
        return fail(ctx, conn_id, state, connect_error(addr, e));
    }
    if state.load(Ordering::SeqCst) == STATE_CLOSED {
        cancel(ctx, conn_id, state);
        return None;
    }
    Some((conn, send, recv))
}

async fn resolve_addr(
    ctx: &crate::context::NativeContext,
    host: &str,
    port: u16,
    conn_id: u64,
    state: &Arc<AtomicU32>,
) -> Option<SocketAddr> {
    match tokio::net::lookup_host((host, port)).await {
        Ok(mut addrs) => addrs.next().or_else(|| {
            fail(
                ctx,
                conn_id,
                state,
                BridgeError::Dns {
                    host: host.to_owned(),
                    port,
                    source: io_no_address(),
                },
            )
        }),
        Err(source) => fail(
            ctx,
            conn_id,
            state,
            BridgeError::Dns {
                host: host.to_owned(),
                port,
                source,
            },
        ),
    }
}

fn connect_error(
    addr: SocketAddr,
    source: impl std::error::Error + Send + Sync + 'static,
) -> BridgeError {
    BridgeError::Connect {
        transport: Transport::Quic,
        addr,
        source: Box::new(source),
    }
}

fn io_no_address() -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::NotFound, "no address resolved")
}

fn fail<T>(
    ctx: &crate::context::NativeContext,
    conn_id: u64,
    state: &Arc<AtomicU32>,
    err: BridgeError,
) -> Option<T> {
    state.store(crate::STATE_FAILED, Ordering::SeqCst);
    ctx.emit_terminal(conn_id);
    report_error(err.message());
    None
}

fn cancel(ctx: &crate::context::NativeContext, conn_id: u64, state: &Arc<AtomicU32>) {
    state.store(STATE_CLOSED, Ordering::SeqCst);
    ctx.emit_terminal(conn_id);
}
