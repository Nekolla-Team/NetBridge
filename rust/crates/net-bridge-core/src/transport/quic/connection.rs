//! 单连接 QUIC 数据面：读写循环与关闭传播。

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use crate::context::NativeContext;
use crate::event::NB_EVENT_DATA_AVAILABLE;
use crate::{Command, STATE_CLOSED, STATE_CONNECTED, STATE_CONNECTING};

/// 单连接读写循环：读侧推入 Java 队列，写侧消费 Java 命令。
/// 终态事件经 `ctx.emit_terminal` 恰好一次；entry 为 tombstone 直到 Java release。
#[allow(clippy::too_many_arguments)]
pub async fn run_connection_with_sink(
    conn_id: u64,
    conn: quinn::Connection,
    cts: CancellationToken,
    mut send: quinn::SendStream,
    mut recv: quinn::RecvStream,
    mut to_transport_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
    _to_transport_tx: mpsc::Sender<Command>,
    state: Arc<AtomicU32>,
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

    let mut empty_streak = 0u32;
    loop {
        if state.load(Ordering::SeqCst) == STATE_CLOSED || cts.is_cancelled() {
            let _ = send.finish();
            break;
        }
        let can_read = inbound_bytes.load(Ordering::SeqCst) < crate::DEFAULT_MAX_BUFFERED_BYTES;
        tokio::select! {
            biased;
            _ = cts.cancelled() => {
                break;
            }
            res = recv.read_chunk(65536, true), if can_read => {
                match res {
                    Ok(Some(chunk)) => {
                        if chunk.bytes.is_empty() {
                            empty_streak = empty_streak.saturating_add(1);
                            if empty_streak >= 16 {
                                break;
                            }
                            continue;
                        }
                        empty_streak = 0;
                        let chunk_len = chunk.bytes.len();
                        inbound_bytes.fetch_add(chunk_len, Ordering::SeqCst);
                        if to_java_tx.send(chunk.bytes).await.is_err() {
                            inbound_bytes.fetch_sub(chunk_len, Ordering::SeqCst);
                            break;
                        }
                        ctx.emit_non_terminal(conn_id, NB_EVENT_DATA_AVAILABLE, 0, 0);
                    }
                    Ok(None) => break,
                    Err(e) => {
                        match &e {
                            quinn::ReadError::ClosedStream
                            | quinn::ReadError::ConnectionLost(quinn::ConnectionError::ApplicationClosed(_))
                            | quinn::ReadError::ConnectionLost(quinn::ConnectionError::LocallyClosed) => {
                                // 远端正常关闭/应用级关闭/本地关闭
                                break;
                            }
                            _ => {
                                if state.load(Ordering::SeqCst) != STATE_CLOSED {
                                    crate::report_error(format!("quic conn {conn_id}: read error: {e}"));
                                    ctx.fail_connection_with_reason(conn_id, crate::event::NB_REASON_PROTOCOL);
                                }
                                break;
                            }
                        }
                    }
                }
            }
            _ = read_waker.notified(), if !can_read => {
                // Java 侧消费了入站数据，唤醒重新检查 can_read
            }
            cmd = to_transport_rx.recv() => match cmd {
                Some(Command::Write(bytes)) => {
                    let bytes_len = bytes.len();
                    if send.write_all(&bytes).await.is_err() {
                        outbound_bytes.fetch_sub(bytes_len, Ordering::SeqCst);
                        ctx.fail_connection_with_reason(conn_id, crate::event::NB_REASON_PROTOCOL);
                        break;
                    }
                    outbound_bytes.fetch_sub(bytes_len, Ordering::SeqCst);
                    if write_blocked.swap(false, Ordering::SeqCst) {
                        ctx.emit_non_terminal(conn_id, crate::event::NB_EVENT_WRITABLE, 0, 0);
                    }
                }
                Some(Command::Close) => {
                    let _ = send.finish();
                    break;
                }
                None => break,
            },
            _ = conn.closed() => break,
        }
    }

    match state.load(Ordering::SeqCst) {
        STATE_CONNECTING | STATE_CONNECTED => {
            state.store(STATE_CLOSED, Ordering::SeqCst);
            ctx.emit_terminal(conn_id);
        }
        _ => {}
    }
    conn.close(0u32.into(), b"net-bridge close");
}
