//! Single-connection QUIC data plane driven by shared rings: TX feeds the transport writer from the
//! Java-produced ring, RX reads the transport directly into the Java-consumed ring.

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use net_bridge_shared_io::{RingConsumer, RingProducer};

use crate::context::NativeContext;
use crate::event::{NB_EVENT_DATA_AVAILABLE, NB_EVENT_WRITABLE};
use crate::shared_io::{SharedConnectionIo, SharedIoDriver};
use crate::{MAX_IO_CHUNK, STATE_CLOSED, STATE_CONNECTED, STATE_CONNECTING};

/// Single-connection supervisor: runs the TX and RX ring drivers concurrently and propagates close.
///
/// Terminal events are emitted exactly once through `ctx.emit_terminal`; the entry remains a
/// tombstone until Java releases it.
#[allow(clippy::too_many_arguments)]
pub async fn run_connection_with_sink(
    conn_id: u64,
    conn: quinn::Connection,
    mut cancel_rx: tokio::sync::watch::Receiver<bool>,
    mut send: quinn::SendStream,
    mut recv: quinn::RecvStream,
    driver: SharedIoDriver,
    io: Arc<SharedConnectionIo>,
    state: Arc<AtomicU32>,
    ctx: Arc<NativeContext>,
) {
    let SharedIoDriver {
        tx_consumer,
        rx_producer,
    } = driver;
    let tx = drive_tx(
        conn_id,
        &mut send,
        tx_consumer,
        &io,
        &ctx,
        cancel_rx.clone(),
        &conn,
    );
    let rx = drive_rx(
        conn_id,
        &mut recv,
        rx_producer,
        &io,
        &ctx,
        cancel_rx.clone(),
        &conn,
    );

    tokio::select! {
        biased;
        _ = cancel_rx.changed() => {}
        _ = tx => {}
        _ = rx => {}
        _ = conn.closed() => {}
    }

    match state.load(Ordering::SeqCst) {
        STATE_CONNECTING | STATE_CONNECTED => {
            state.store(STATE_CLOSED, Ordering::SeqCst);
            ctx.emit_terminal(conn_id);
        }
        _ => {}
    }
    let _ = send.finish();
    conn.close(0u32.into(), b"net-bridge close");
}

/// Drains the TX ring into the QUIC send stream, edge-triggering WRITABLE when the Java producer
/// was parked and space is released.
async fn drive_tx(
    conn_id: u64,
    send: &mut quinn::SendStream,
    mut tx_consumer: RingConsumer,
    io: &SharedConnectionIo,
    ctx: &NativeContext,
    mut cancel: tokio::sync::watch::Receiver<bool>,
    conn: &quinn::Connection,
) {
    loop {
        let mut progressed = false;
        while let Some(grant) = tx_consumer.try_acquire(MAX_IO_CHUNK) {
            let len = grant.len();
            if send.write_all(grant.as_ref()).await.is_err() {
                return;
            }
            let wake = grant.commit(len);
            if wake {
                ctx.emit_non_terminal(conn_id, NB_EVENT_WRITABLE, 0, 0);
            }
            progressed = true;
        }
        if progressed {
            continue;
        }
        if tx_consumer.park_if_empty() {
            tokio::select! {
                _ = cancel.changed() => return,
                _ = io.tx_data_notify.notified() => tx_consumer.resume_after_notification(),
                _ = conn.closed() => return,
            }
        }
    }
}

/// Reads the QUIC receive stream directly into the RX ring, edge-triggering DATA_AVAILABLE when the
/// Java consumer was parked.
async fn drive_rx(
    conn_id: u64,
    recv: &mut quinn::RecvStream,
    mut rx_producer: RingProducer,
    io: &SharedConnectionIo,
    ctx: &NativeContext,
    mut cancel: tokio::sync::watch::Receiver<bool>,
    conn: &quinn::Connection,
) {
    let mut empty_streak = 0u32;
    loop {
        if let Some(mut grant) = rx_producer.try_acquire(MAX_IO_CHUNK) {
            match recv.read(grant.as_mut()).await {
                Ok(Some(0)) => {
                    empty_streak = empty_streak.saturating_add(1);
                    if empty_streak >= 16 {
                        return;
                    }
                }
                Ok(Some(n)) => {
                    empty_streak = 0;
                    let wake = grant.commit(n);
                    if wake {
                        ctx.emit_non_terminal(conn_id, NB_EVENT_DATA_AVAILABLE, 0, 0);
                    }
                }
                Ok(None) => return,
                Err(e) => {
                    match &e {
                        quinn::ReadError::ClosedStream
                        | quinn::ReadError::ConnectionLost(
                            quinn::ConnectionError::ApplicationClosed(_),
                        )
                        | quinn::ReadError::ConnectionLost(quinn::ConnectionError::LocallyClosed) =>
                        {
                            // Remote graceful close / application close / local close
                        }
                        _ => {
                            if state_is_live(ctx, conn_id) {
                                crate::report_error(format!(
                                    "quic conn {conn_id}: read error: {e}"
                                ));
                                ctx.fail_connection_with_reason(
                                    conn_id,
                                    crate::event::NB_REASON_PROTOCOL,
                                );
                            }
                        }
                    }
                    return;
                }
            }
        } else if rx_producer.park_if_full() {
            tokio::select! {
                _ = cancel.changed() => return,
                _ = io.rx_space_notify.notified() => rx_producer.resume_after_notification(),
                _ = conn.closed() => return,
            }
        }
    }
}

fn state_is_live(ctx: &NativeContext, conn_id: u64) -> bool {
    ctx.connection_state(conn_id)
        .map(|s| s != STATE_CLOSED)
        .unwrap_or(false)
}
