//! KCP single-connection data plane: an smux session carries the MC byte stream, driven by shared
//! rings instead of byte queues.

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use kcp::KcpStream;
use net_bridge_shared_io::{RingConsumer, RingProducer};
use smux::{Config, ConfigBuilder, Session};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use super::fec_stream::FecStream;
use crate::context::NativeContext;
use crate::event::{NB_EVENT_DATA_AVAILABLE, NB_EVENT_WRITABLE};
use crate::report_error;
use crate::shared_io::{SharedConnectionIo, SharedIoDriver};
use crate::{MAX_IO_CHUNK, STATE_CLOSED, STATE_FAILED};

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
    driver: SharedIoDriver,
    io: Arc<SharedConnectionIo>,
    state: Arc<AtomicU32>,
    ctx: Arc<NativeContext>,
) {
    let SharedIoDriver {
        tx_consumer,
        rx_producer,
    } = driver;
    let (mut stream_r, mut stream_w) = tokio::io::split(stream);

    let tx = drive_tx(
        conn_id,
        &mut stream_w,
        tx_consumer,
        &io,
        &ctx,
        cancel_rx.clone(),
    );
    let rx = drive_rx(
        conn_id,
        &mut stream_r,
        rx_producer,
        &io,
        &ctx,
        cancel_rx.clone(),
    );

    tokio::select! {
        biased;
        _ = cancel_rx.changed() => {}
        _ = tx => {}
        _ = rx => {}
    }

    if state.load(Ordering::SeqCst) != STATE_FAILED {
        state.store(STATE_CLOSED, Ordering::SeqCst);
        ctx.emit_terminal(conn_id);
    }
    graceful_close(&mut stream_w, &session).await;
}

async fn drive_tx(
    conn_id: u64,
    stream_w: &mut (impl AsyncWrite + Unpin),
    mut tx_consumer: RingConsumer,
    io: &SharedConnectionIo,
    ctx: &NativeContext,
    mut cancel: tokio::sync::watch::Receiver<bool>,
) {
    loop {
        let mut progressed = false;
        while let Some(grant) = tx_consumer.try_acquire(MAX_IO_CHUNK) {
            let len = grant.len();
            if let Err(e) = stream_w.write_all(grant.as_ref()).await {
                if ctx.connection_state(conn_id) != Some(STATE_CLOSED) && !is_session_closed(&e) {
                    report_error(format!("kcp conn {conn_id}: write error: {e}"));
                    ctx.fail_connection_with_reason(conn_id, crate::event::NB_REASON_PROTOCOL);
                }
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
            }
        }
    }
}

async fn drive_rx(
    conn_id: u64,
    stream_r: &mut (impl AsyncRead + Unpin),
    mut rx_producer: RingProducer,
    io: &SharedConnectionIo,
    ctx: &NativeContext,
    mut cancel: tokio::sync::watch::Receiver<bool>,
) {
    loop {
        if let Some(mut grant) = rx_producer.try_acquire(MAX_IO_CHUNK) {
            match stream_r.read(grant.as_mut()).await {
                Ok(0) => return,
                Ok(n) => {
                    let wake = grant.commit(n);
                    if wake {
                        ctx.emit_non_terminal(conn_id, NB_EVENT_DATA_AVAILABLE, 0, 0);
                    }
                }
                Err(e) => {
                    if ctx.connection_state(conn_id) != Some(STATE_CLOSED) && !is_session_closed(&e)
                    {
                        report_error(format!("kcp conn {conn_id}: read error: {e}"));
                        ctx.fail_connection_with_reason(conn_id, crate::event::NB_REASON_PROTOCOL);
                    }
                    return;
                }
            }
        } else if rx_producer.park_if_full() {
            tokio::select! {
                _ = cancel.changed() => return,
                _ = io.rx_space_notify.notified() => rx_producer.resume_after_notification(),
            }
        }
    }
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
