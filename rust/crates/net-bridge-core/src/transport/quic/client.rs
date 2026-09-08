//! Client QUIC connection: performs the handshake asynchronously and returns the connection ID immediately.

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use crate::error::{BridgeError, Transport};
use crate::report_error;
use crate::socket_util;
use crate::{Command, ConnHandle, STATE_CLOSED, STATE_CONNECTING};

/// Starts a QUIC client connection through NativeContext.
pub fn connect_in_context(
    ctx: &Arc<crate::context::NativeContext>,
    host: &str,
    port: u16,
) -> Result<u64, BridgeError> {
    let (to_transport_tx, to_transport_rx) = mpsc::channel::<Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Bytes>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
    let cancel = CancellationToken::new();
    let data_plane_cancel = cancel.child_token();
    let conn_id = ctx.allocate_id()?;
    ctx.conns().insert(
        conn_id,
        Arc::new(ConnHandle::client(
            state.clone(),
            to_java_rx,
            to_transport_tx.clone(),
            cancel,
            true,
            None,
        )),
    );

    let host = host.to_string();
    let ctx_task = Arc::clone(ctx);
    ctx.spawn_connection_task("quic connect task in context", conn_id, async move {
        if let Some((conn, send, recv)) = establish(&ctx_task, &host, port, conn_id, &state).await {
            ctx_task.set_conn_remote_addr(conn_id, conn.remote_address());
            ctx_task.emit_connected(conn_id);
            let counters = ctx_task
                .conns()
                .get(&conn_id)
                .map(|handle| handle.counters())
                .unwrap_or_default();
            super::connection::QuicDataPlane {
                conn_id,
                conn,
                cancel: data_plane_cancel,
                send,
                recv,
                to_transport_rx,
                to_java_tx,
                state,
                ctx: Arc::clone(&ctx_task),
                counters,
                empty_reads: 0,
            }
            .run()
            .await;
        } else {
            ctx_task.emit_terminal(conn_id);
        }
    });
    Ok(conn_id)
}

/// Establishes a connection: resolve → bind → endpoint → cancellation-aware handshake → bidirectional
/// stream.
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
    _state: &Arc<AtomicU32>,
    err: BridgeError,
) -> Option<T> {
    let reason = err.reason_code();
    report_error(err.message());
    ctx.fail_connection_with_reason(conn_id, reason);
    None
}

fn cancel(ctx: &crate::context::NativeContext, conn_id: u64, _state: &Arc<AtomicU32>) {
    ctx.fail_connection_with_reason(conn_id, crate::event::NB_REASON_CANCELLED);
}
