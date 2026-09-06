//! KCP 单连接数据面：smux 会话承载 MC 字节流，读写循环与关闭传播。

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::{Bytes, BytesMut};
use kcp::KcpStream;
use smux::{Config, ConfigBuilder, Session};
use tokio::io::{AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadHalf, WriteHalf};
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

/// 终态事件经 `ctx.emit_terminal` 恰好一次；entry 为 tombstone 直到 Java release。
#[allow(clippy::too_many_arguments)]
pub async fn run_kcp_connection_with_sink(
    conn_id: u64,
    stream: smux::Stream,
    session: Arc<Session>,
    cancel_rx: tokio::sync::watch::Receiver<bool>,
    to_kcp_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
    state: Arc<AtomicU32>,
    _client_side: bool,
    ctx: Arc<NativeContext>,
) {
    let (write_blocked, outbound_bytes, inbound_bytes) = ctx
        .conns()
        .get(&conn_id)
        .map(|h| {
            (
                h.write_blocked.clone(),
                h.outbound_bytes.clone(),
                h.inbound_bytes.clone(),
            )
        })
        .unwrap_or_else(|| {
            (
                Arc::new(std::sync::atomic::AtomicBool::new(false)),
                Arc::new(std::sync::atomic::AtomicUsize::new(0)),
                Arc::new(std::sync::atomic::AtomicUsize::new(0)),
            )
        });
    let (stream_r, stream_w) = tokio::io::split(stream);
    let (reader_done_tx, mut reader_done_rx) = mpsc::channel::<bool>(1);
    let done_guard = reader_done_tx.clone();
    let reader_state = state.clone();
    let reader_ctx = Arc::clone(&ctx);
    let reader_inbound_bytes = inbound_bytes.clone();
    let reader = tokio::spawn(async move {
        reader_loop(
            conn_id,
            stream_r,
            to_java_tx,
            reader_state,
            reader_done_tx,
            reader_ctx,
            reader_inbound_bytes,
        )
        .await;
    });
    let _ = done_guard.clone();

    drive(
        conn_id,
        stream_w,
        &session,
        cancel_rx,
        to_kcp_rx,
        &mut reader_done_rx,
        &state,
        &ctx,
        &write_blocked,
        &outbound_bytes,
    )
    .await;

    reader.abort();
    let _ = reader.await;
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

async fn reader_loop(
    conn_id: u64,
    mut stream_r: ReadHalf<smux::Stream>,
    to_java_tx: mpsc::Sender<Bytes>,
    state: Arc<AtomicU32>,
    done_tx: mpsc::Sender<bool>,
    ctx: Arc<NativeContext>,
    inbound_bytes: Arc<std::sync::atomic::AtomicUsize>,
) {
    let mut payload = BytesMut::with_capacity(64 * 1024);
    let clean = loop {
        while inbound_bytes.load(Ordering::SeqCst) >= crate::DEFAULT_MAX_BUFFERED_BYTES {
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }

        payload.clear();
        match stream_r.read_buf(&mut payload).await {
            Ok(0) => break true,
            Ok(_) => {
                let chunk = payload.split().freeze();
                let chunk_len = chunk.len();
                inbound_bytes.fetch_add(chunk_len, Ordering::SeqCst);
                if to_java_tx.send(chunk).await.is_err() {
                    inbound_bytes.fetch_sub(chunk_len, Ordering::SeqCst);
                    break true;
                }
                // Linearized emit: suppressed if in terminal state
                ctx.emit_non_terminal(conn_id, NB_EVENT_DATA_AVAILABLE, 0, 0);
            }
            Err(_) if state.load(Ordering::SeqCst) == STATE_CLOSED => break true,
            Err(e) if is_session_closed(&e) => break true,
            Err(e) => {
                report_error(format!("kcp conn {conn_id}: read error: {e}"));
                break false;
            }
        }
    };
    let _ = done_tx.send(clean).await;
}

#[allow(clippy::too_many_arguments)]
async fn drive(
    conn_id: u64,
    mut stream_w: WriteHalf<smux::Stream>,
    session: &Session,
    mut cancel_rx: tokio::sync::watch::Receiver<bool>,
    mut cmds: mpsc::Receiver<Command>,
    reader_done: &mut mpsc::Receiver<bool>,
    state: &AtomicU32,
    ctx: &Arc<NativeContext>,
    write_blocked: &Arc<std::sync::atomic::AtomicBool>,
    outbound_bytes: &Arc<std::sync::atomic::AtomicUsize>,
) {
    loop {
        if state.load(Ordering::SeqCst) == STATE_CLOSED {
            break;
        }
        tokio::select! {
            _ = cancel_rx.changed() => {
                break;
            }
            done = reader_done.recv() => {
                if done == Some(false) {
                    ctx.fail_connection_with_reason(conn_id, crate::event::NB_REASON_PROTOCOL);
                }
                break;
            }
            cmd = cmds.recv() => {
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
    graceful_close(&mut stream_w, session).await;
}

async fn graceful_close(stream_w: &mut (impl AsyncWrite + Unpin), session: &Session) {
    let _ = stream_w.shutdown().await;
    let _ = session.close().await;
}

fn is_session_closed(e: &std::io::Error) -> bool {
    e.kind() == std::io::ErrorKind::BrokenPipe
}
