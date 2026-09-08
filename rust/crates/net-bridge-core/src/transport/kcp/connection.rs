//! KCP single-connection data plane: an smux session carries the MC byte stream, with read/write loops and
//! close propagation.

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::{Bytes, BytesMut};
use kcp::KcpStream;
use smux::{Config, ConfigBuilder, Session};
use tokio::io::{AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc;

use super::fec_stream::FecStream;
use crate::context::NativeContext;
use crate::event::{NB_EVENT_DATA_AVAILABLE, NB_EVENT_WRITABLE};
use crate::report_error;
use crate::{Command, STATE_CLOSED, STATE_FAILED};

type KcpFec = FecStream<KcpStream>;

fn smux_config() -> Result<Config, String> {
    ConfigBuilder::new()
        .max_frame_size(64 * 1024)
        .build()
        .map_err(|e| format!("smux config build failed: {e}"))
}

/// Terminal events are emitted exactly once through `ctx.emit_terminal`; the entry remains a tombstone
/// until Java releases it. Single-layer task with no nested spawn and no detached reader (INV-6).
#[allow(clippy::too_many_arguments)]
pub async fn run_kcp_connection_with_sink(
    conn_id: u64,
    stream: smux::Stream,
    session: Arc<Session>,
    mut cancel_rx: tokio::sync::watch::Receiver<bool>,
    mut to_kcp_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
    state: Arc<AtomicU32>,
    _client_side: bool,
    ctx: Arc<NativeContext>,
) {
    let (write_blocked, outbound_bytes, inbound_bytes, read_waker) = ctx
        .conns()
        .get(&conn_id)
        .map(|h| {
            (
                h.write_blocked.clone(),
                h.outbound_bytes.clone(),
                h.inbound_bytes.clone(),
                h.read_waker.clone(),
            )
        })
        .unwrap_or_else(|| {
            (
                Arc::new(std::sync::atomic::AtomicBool::new(false)),
                Arc::new(std::sync::atomic::AtomicUsize::new(0)),
                Arc::new(std::sync::atomic::AtomicUsize::new(0)),
                Arc::new(tokio::sync::Notify::new()),
            )
        });
    let (mut stream_r, mut stream_w) = tokio::io::split(stream);
    let mut payload = BytesMut::with_capacity(64 * 1024);

    loop {
        if state.load(Ordering::SeqCst) == STATE_CLOSED || *cancel_rx.borrow() {
            break;
        }
        let can_read = inbound_bytes.load(Ordering::SeqCst) < crate::DEFAULT_MAX_BUFFERED_BYTES;
        tokio::select! {
            biased;
            _ = cancel_rx.changed() => {
                break;
            }
            res = stream_r.read_buf(&mut payload), if can_read => {
                match res {
                    Ok(0) => {
                        // EOF
                        break;
                    }
                    Ok(_) => {
                        let chunk = payload.split().freeze();
                        let chunk_len = chunk.len();
                        inbound_bytes.fetch_add(chunk_len, Ordering::SeqCst);
                        if to_java_tx.send(chunk).await.is_err() {
                            inbound_bytes.fetch_sub(chunk_len, Ordering::SeqCst);
                            break;
                        }
                        ctx.emit_non_terminal(conn_id, NB_EVENT_DATA_AVAILABLE, 0, 0);
                    }
                    Err(e) => {
                        if state.load(Ordering::SeqCst) != STATE_CLOSED && !is_session_closed(&e) {
                            report_error(format!("kcp conn {conn_id}: read error: {e}"));
                            ctx.fail_connection_with_reason(conn_id, crate::event::NB_REASON_PROTOCOL);
                        }
                        break;
                    }
                }
            }
            _ = read_waker.notified(), if !can_read => {
                // Java consumed inbound data; wake the reader to recheck can_read
            }
            cmd = to_kcp_rx.recv() => {
                let closed = match cmd {
                    Some(Command::Write(bytes)) if !bytes.is_empty() => {
                        let bytes_len = bytes.len();
                        if let Err(e) = stream_w.write_all(&bytes).await {
                            outbound_bytes.fetch_sub(bytes_len, Ordering::SeqCst);
                            if is_session_closed(&e) {
                                true
                            } else {
                                report_error(format!("kcp conn {conn_id}: write error: {e}"));
                                ctx.fail_connection_with_reason(conn_id, crate::event::NB_REASON_PROTOCOL);
                                true
                            }
                        } else {
                            outbound_bytes.fetch_sub(bytes_len, Ordering::SeqCst);
                            if write_blocked.swap(false, Ordering::SeqCst) {
                                ctx.emit_non_terminal(conn_id, NB_EVENT_WRITABLE, 0, 0);
                            }
                            false
                        }
                    }
                    Some(Command::Close) | None => true,
                    _ => false,
                };
                if closed {
                    break;
                }
            }
        }
    }

    if state.load(Ordering::SeqCst) != STATE_FAILED {
        state.store(STATE_CLOSED, Ordering::SeqCst);
        ctx.emit_terminal(conn_id);
    }
    graceful_close(&mut stream_w, &session).await;
}

pub async fn prepare_kcp_data_plane(
    conn_id: u64,
    fec: KcpFec,
    client_side: bool,
    state: &AtomicU32,
    ctx: &Arc<NativeContext>,
) -> Option<(smux::Stream, Arc<Session>)> {
    let config = match smux_config() {
        Ok(config) => config,
        Err(msg) => return request_fail(conn_id, state, Err(msg), ctx),
    };
    let session_res = if client_side {
        Session::client(fec, config).await
    } else {
        Session::server(fec, config).await
    };
    let session = match request_fail(
        conn_id,
        state,
        session_res.map_err(|e| format!("smux session setup failed: {e}")),
        ctx,
    ) {
        Some(s) => Arc::new(s),
        None => return None,
    };
    let stream_res = if client_side {
        session.open_stream().await
    } else {
        session.accept_stream().await
    };
    let stream = match request_fail(
        conn_id,
        state,
        stream_res.map_err(|e| format!("smux stream setup failed: {e}")),
        ctx,
    ) {
        Some(st) => st,
        None => return None,
    };
    Some((stream, session))
}

fn request_fail<T>(
    conn_id: u64,
    _state: &AtomicU32,
    result: Result<T, String>,
    ctx: &Arc<NativeContext>,
) -> Option<T> {
    match result {
        Ok(value) => Some(value),
        Err(msg) => {
            report_error(format!("kcp conn {conn_id}: {msg}"));
            ctx.fail_connection_with_reason(conn_id, crate::event::NB_REASON_PROTOCOL);
            None
        }
    }
}

async fn graceful_close(stream_w: &mut (impl AsyncWrite + Unpin), session: &Session) {
    let _ = stream_w.shutdown().await;
    let _ = session.close().await;
}

fn is_session_closed(e: &std::io::Error) -> bool {
    e.kind() == std::io::ErrorKind::BrokenPipe
}
