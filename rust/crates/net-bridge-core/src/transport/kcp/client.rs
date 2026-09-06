//! KCP 客户端：异步建连（kcp-rs 内建 SYN 握手），立即返回连接 id。

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use kcp::{KcpStream, KcpUdpStream};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;

use super::config::{KcpProfile, build_config};
use super::fec_stream::FecStream;
use crate::error::{BridgeError, Transport};
use crate::report_error;
use crate::socket_util;
use crate::{Command, ConnHandle, STATE_CONNECTING};

/// 经 NativeContext 发起 KCP 客户端连接。
pub fn connect_in_context(
    ctx: &Arc<crate::context::NativeContext>,
    host: &str,
    port: u16,
    profile: KcpProfile,
) -> Result<u64, BridgeError> {
    let (to_transport_tx, to_transport_rx) = mpsc::channel::<Command>(4096);
    let (to_java_tx, to_java_rx) = mpsc::channel::<Bytes>(8192);
    let state = Arc::new(AtomicU32::new(STATE_CONNECTING));
    let (cancel_tx, cancel_rx) = tokio::sync::watch::channel(false);
    let Ok(conn_id) = ctx.allocate_id() else {
        return Err(BridgeError::IdOverflow);
    };
    ctx.conns().insert(
        conn_id,
        Arc::new(ConnHandle::new(
            state.clone(),
            to_java_rx,
            to_transport_tx,
            cancel_tx,
            None,
            None,
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
        let Some((mc_stream, session)) = super::connection::prepare_kcp_data_plane(
            conn_id,
            FecStream::new(stream),
            true,
            &state,
            &ctx_task,
        )
        .await
        else {
            return;
        };
        if state.load(Ordering::SeqCst) != crate::STATE_CLOSED {
            ctx_task.emit_connected(conn_id);
        }
        super::connection::run_kcp_connection_with_sink(
            conn_id,
            mc_stream,
            session,
            cancel_rx,
            to_transport_rx,
            to_java_tx,
            state,
            true,
            Arc::clone(&ctx_task),
        )
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
