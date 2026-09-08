//! KCP client: establishes connections asynchronously using the built-in kcp-rs SYN handshake and
//! returns the connection ID immediately.

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use kcp::{KcpStream, KcpUdpStream};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use super::config::{KcpProfile, build_config};
use super::fec_stream::FecStream;
use crate::error::{BridgeError, Transport};
use crate::report_error;
use crate::socket_util;
use crate::{Command, ConnHandle, STATE_CONNECTING};

/// Starts a KCP client connection through NativeContext.
pub fn connect_in_context(
    ctx: &Arc<crate::context::NativeContext>,
    host: &str,
    port: u16,
    profile: KcpProfile,
) -> Result<u64, BridgeError> {
    let (to_transport_tx, to_transport_rx) = mpsc::channel::<Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Bytes>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
    let cancel = CancellationToken::new();
    let data_plane_cancel = cancel.clone();
    let Ok(conn_id) = ctx.allocate_id() else {
        return Err(BridgeError::IdOverflow);
    };
    ctx.conns().insert(
        conn_id,
        Arc::new(ConnHandle::client(
            state.clone(),
            to_java_rx,
            to_transport_tx,
            cancel,
            true,
            None,
        )),
    );
    let host = host.to_string();
    let ctx_task = Arc::clone(ctx);
    ctx.spawn_connection_task("kcp connect task in context", conn_id, async move {
        let Some(addr) = resolve_host(&ctx_task, conn_id, &state, &host, port).await else {
            return;
        };
        let Some(udp) = client_udp(&ctx_task, conn_id, &state, addr) else {
            return;
        };
        let Some(stream) = establish_kcp(&ctx_task, conn_id, &state, addr, udp, profile).await
        else {
            return;
        };
        ctx_task.set_conn_remote_addr(conn_id, addr);
        let (mc_stream, session) =
            match super::connection::prepare_kcp_data_plane(FecStream::new(stream), true).await {
                Ok(data_plane) => data_plane,
                Err(error) => {
                    fail(&ctx_task, conn_id, &state, error);
                    return;
                }
            };
        if state.load(Ordering::SeqCst) != crate::STATE_CLOSED {
            ctx_task.emit_connected(conn_id);
        }
        let counters = ctx_task
            .conns()
            .get(&conn_id)
            .map(|handle| handle.counters())
            .unwrap_or_default();
        super::connection::KcpDataPlane {
            conn_id,
            stream: mc_stream,
            session,
            cancel: data_plane_cancel,
            to_transport_rx,
            to_java_tx,
            state,
            ctx: Arc::clone(&ctx_task),
            counters,
        }
        .run()
        .await;
    });
    Ok(conn_id)
}

async fn resolve_host(
    ctx: &crate::context::NativeContext,
    conn_id: u64,
    state: &Arc<AtomicU32>,
    host: &str,
    port: u16,
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
                    source: std::io::Error::new(
                        std::io::ErrorKind::NotFound,
                        "no address resolved",
                    ),
                },
            );
            None
        }),
        Err(source) => {
            fail(
                ctx,
                conn_id,
                state,
                BridgeError::Dns {
                    host: host.to_owned(),
                    port,
                    source,
                },
            );
            None
        }
    }
}

fn client_udp(
    ctx: &crate::context::NativeContext,
    conn_id: u64,
    state: &Arc<AtomicU32>,
    addr: SocketAddr,
) -> Option<UdpSocket> {
    let socket = match socket_util::bind_client(addr.is_ipv6()) {
        Ok(s) => s,
        Err(e) => {
            fail(
                ctx,
                conn_id,
                state,
                BridgeError::Setup {
                    transport: Transport::Kcp,
                    stage: "client bind",
                    source: e,
                },
            );
            return None;
        }
    };
    match socket
        .set_nonblocking(true)
        .and_then(|()| UdpSocket::from_std(socket))
    {
        Ok(udp) => Some(udp),
        Err(e) => {
            fail(
                ctx,
                conn_id,
                state,
                BridgeError::Setup {
                    transport: Transport::Kcp,
                    stage: "from_std",
                    source: e,
                },
            );
            None
        }
    }
}

async fn establish_kcp(
    ctx: &crate::context::NativeContext,
    conn_id: u64,
    state: &Arc<AtomicU32>,
    addr: SocketAddr,
    udp: UdpSocket,
    profile: KcpProfile,
) -> Option<KcpStream> {
    let config = Arc::new(build_config(profile));
    match KcpUdpStream::socket_connect(config, addr, udp).await {
        Ok((stream, _)) => Some(stream),
        Err(e) => {
            fail(
                ctx,
                conn_id,
                state,
                BridgeError::Connect {
                    transport: Transport::Kcp,
                    addr,
                    source: Box::new(e),
                },
            );
            None
        }
    }
}

fn fail(
    ctx: &crate::context::NativeContext,
    conn_id: u64,
    _state: &Arc<AtomicU32>,
    err: BridgeError,
) {
    let reason = err.reason_code();
    report_error(err.message());
    ctx.fail_connection_with_reason(conn_id, reason);
}
