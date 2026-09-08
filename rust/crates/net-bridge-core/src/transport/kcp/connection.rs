//! KCP single-connection data plane over an FEC stream and smux session.

use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::{Bytes, BytesMut};
use kcp::KcpStream;
use smux::{Config, ConfigBuilder, Session};
use tokio::io::{AsyncReadExt, AsyncWriteExt, ReadHalf, WriteHalf};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use super::fec_stream::FecStream;
use crate::connection::ConnectionCounters;
use crate::context::NativeContext;
use crate::error::BridgeError;
use crate::event::{NB_EVENT_DATA_AVAILABLE, NB_EVENT_WRITABLE, NB_REASON_PROTOCOL};
use crate::{Command, DEFAULT_MAX_BUFFERED_BYTES, STATE_CLOSED, STATE_FAILED};

type KcpFec = FecStream<KcpStream>;
type KcpReader = ReadHalf<smux::Stream>;
type KcpWriter = WriteHalf<smux::Stream>;

/// Owns the bidirectional KCP data plane for one connection.
pub(crate) struct KcpDataPlane {
    pub(crate) conn_id: u64,
    pub(crate) stream: smux::Stream,
    pub(crate) session: Arc<Session>,
    pub(crate) cancel: CancellationToken,
    pub(crate) to_transport_rx: mpsc::Receiver<Command>,
    pub(crate) to_java_tx: mpsc::Sender<Bytes>,
    pub(crate) state: Arc<AtomicU32>,
    pub(crate) ctx: Arc<NativeContext>,
    pub(crate) counters: ConnectionCounters,
}

impl KcpDataPlane {
    /// Runs until either peer closes, cancellation fires, or a protocol error occurs.
    pub(crate) async fn run(self) {
        let (stream_r, stream_w) = tokio::io::split(self.stream);
        KcpIo {
            conn_id: self.conn_id,
            stream_r,
            stream_w,
            session: self.session,
            cancel: self.cancel,
            to_transport_rx: self.to_transport_rx,
            to_java_tx: self.to_java_tx,
            state: self.state,
            ctx: self.ctx,
            counters: self.counters,
            payload: BytesMut::with_capacity(64 * 1024),
        }
        .run()
        .await;
    }
}

struct KcpIo {
    conn_id: u64,
    stream_r: KcpReader,
    stream_w: KcpWriter,
    session: Arc<Session>,
    cancel: CancellationToken,
    to_transport_rx: mpsc::Receiver<Command>,
    to_java_tx: mpsc::Sender<Bytes>,
    state: Arc<AtomicU32>,
    ctx: Arc<NativeContext>,
    counters: ConnectionCounters,
    payload: BytesMut,
}

impl KcpIo {
    async fn run(mut self) {
        loop {
            if self.should_stop() || !self.step().await {
                break;
            }
        }
        self.finish().await;
    }

    fn should_stop(&self) -> bool {
        self.state.load(Ordering::SeqCst) == STATE_CLOSED || self.cancel.is_cancelled()
    }

    async fn step(&mut self) -> bool {
        let can_read =
            self.counters.inbound_bytes.load(Ordering::SeqCst) < DEFAULT_MAX_BUFFERED_BYTES;
        tokio::select! {
            biased;
            _ = self.cancel.cancelled() => false,
            result = self.stream_r.read_buf(&mut self.payload), if can_read => {
                self.on_read(result).await
            }
            _ = self.counters.read_waker.notified(), if !can_read => true,
            command = self.to_transport_rx.recv() => self.on_command(command).await,
        }
    }

    async fn on_read(&mut self, result: io::Result<usize>) -> bool {
        match result {
            Ok(0) => false,
            Ok(_) => self.forward_payload().await,
            Err(error) => self.on_read_error(error),
        }
    }

    async fn forward_payload(&mut self) -> bool {
        let chunk = self.payload.split().freeze();
        let len = chunk.len();
        self.counters.inbound_bytes.fetch_add(len, Ordering::SeqCst);
        if self.to_java_tx.send(chunk).await.is_err() {
            self.counters.inbound_bytes.fetch_sub(len, Ordering::SeqCst);
            return false;
        }
        self.ctx
            .emit_non_terminal(self.conn_id, NB_EVENT_DATA_AVAILABLE, 0, 0);
        true
    }

    fn on_read_error(&mut self, error: io::Error) -> bool {
        if self.state.load(Ordering::SeqCst) != STATE_CLOSED && !is_session_closed(&error) {
            crate::report_error(format!("kcp conn {}: read error: {error}", self.conn_id));
            self.ctx
                .fail_connection_with_reason(self.conn_id, NB_REASON_PROTOCOL);
        }
        false
    }

    async fn on_command(&mut self, command: Option<Command>) -> bool {
        match command {
            Some(Command::Write(bytes)) if !bytes.is_empty() => self.write(bytes).await,
            Some(Command::Close) | None => false,
            _ => true,
        }
    }

    async fn write(&mut self, bytes: Bytes) -> bool {
        let len = bytes.len();
        let result = self.stream_w.write_all(&bytes).await;
        self.counters
            .outbound_bytes
            .fetch_sub(len, Ordering::SeqCst);
        if let Err(error) = result {
            if !is_session_closed(&error) {
                crate::report_error(format!("kcp conn {}: write error: {error}", self.conn_id));
                self.ctx
                    .fail_connection_with_reason(self.conn_id, NB_REASON_PROTOCOL);
            }
            return false;
        }
        if self.counters.write_blocked.swap(false, Ordering::SeqCst) {
            self.ctx
                .emit_non_terminal(self.conn_id, NB_EVENT_WRITABLE, 0, 0);
        }
        true
    }

    async fn finish(mut self) {
        if self.state.load(Ordering::SeqCst) != STATE_FAILED {
            self.state.store(STATE_CLOSED, Ordering::SeqCst);
            self.ctx.emit_terminal(self.conn_id);
        }
        let _ = self.stream_w.shutdown().await;
        let _ = self.session.close().await;
    }
}

/// Creates the smux session and opens or accepts the Minecraft stream.
pub async fn prepare_kcp_data_plane(
    fec: KcpFec,
    client_side: bool,
) -> Result<(smux::Stream, Arc<Session>), BridgeError> {
    let config = smux_config().map_err(BridgeError::Protocol)?;
    let session = if client_side {
        Session::client(fec, config).await
    } else {
        Session::server(fec, config).await
    }
    .map_err(|error| BridgeError::Protocol(format!("smux session setup failed: {error}")))?;
    let session = Arc::new(session);
    let stream = if client_side {
        session.open_stream().await
    } else {
        session.accept_stream().await
    }
    .map_err(|error| BridgeError::Protocol(format!("smux stream setup failed: {error}")))?;
    Ok((stream, session))
}

fn smux_config() -> Result<Config, String> {
    ConfigBuilder::new()
        .max_frame_size(64 * 1024)
        .build()
        .map_err(|error| format!("smux config build failed: {error}"))
}

fn is_session_closed(error: &io::Error) -> bool {
    error.kind() == io::ErrorKind::BrokenPipe
}
