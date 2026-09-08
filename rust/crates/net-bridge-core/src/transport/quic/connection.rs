//! Single-connection QUIC data plane.

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use bytes::Bytes;
use quinn::{Connection, ReadError, RecvStream, SendStream};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use crate::connection::ConnectionCounters;
use crate::context::NativeContext;
use crate::event::{NB_EVENT_DATA_AVAILABLE, NB_EVENT_WRITABLE, NB_REASON_PROTOCOL};
use crate::{Command, DEFAULT_MAX_BUFFERED_BYTES, STATE_CLOSED, STATE_CONNECTED, STATE_CONNECTING};

const READ_CHUNK_SIZE: usize = 64 * 1024;
const MAX_EMPTY_READS: u32 = 16;

/// Owns the bidirectional QUIC data plane for one connection.
pub(crate) struct QuicDataPlane {
    pub(crate) conn_id: u64,
    pub(crate) conn: Connection,
    pub(crate) cancel: CancellationToken,
    pub(crate) send: SendStream,
    pub(crate) recv: RecvStream,
    pub(crate) to_transport_rx: mpsc::Receiver<Command>,
    pub(crate) to_java_tx: mpsc::Sender<Bytes>,
    pub(crate) state: Arc<AtomicU32>,
    pub(crate) ctx: Arc<NativeContext>,
    pub(crate) counters: ConnectionCounters,
    pub(crate) empty_reads: u32,
}

impl QuicDataPlane {
    /// Runs until either peer closes, cancellation fires, or a protocol error occurs.
    pub(crate) async fn run(mut self) {
        loop {
            if self.should_stop() {
                self.finish_send();
                break;
            }
            if !self.step().await {
                break;
            }
        }
        self.finish();
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
            result = self.recv.read_chunk(READ_CHUNK_SIZE, true), if can_read => {
                self.on_read(result).await
            }
            _ = self.counters.read_waker.notified(), if !can_read => true,
            command = self.to_transport_rx.recv() => self.on_command(command).await,
            _ = self.conn.closed() => false,
        }
    }

    async fn on_read(&mut self, result: Result<Option<quinn::Chunk>, ReadError>) -> bool {
        match result {
            Ok(Some(chunk)) if chunk.bytes.is_empty() => self.on_empty_read(),
            Ok(Some(chunk)) => self.on_chunk(chunk.bytes).await,
            Ok(None) => false,
            Err(error) => self.on_read_error(error),
        }
    }

    fn on_empty_read(&mut self) -> bool {
        self.empty_reads = self.empty_reads.saturating_add(1);
        self.empty_reads < MAX_EMPTY_READS
    }

    async fn on_chunk(&mut self, bytes: Bytes) -> bool {
        self.empty_reads = 0;
        let len = bytes.len();
        self.counters.inbound_bytes.fetch_add(len, Ordering::SeqCst);
        if self.to_java_tx.send(bytes).await.is_err() {
            self.counters.inbound_bytes.fetch_sub(len, Ordering::SeqCst);
            return false;
        }
        self.ctx
            .emit_non_terminal(self.conn_id, NB_EVENT_DATA_AVAILABLE, 0, 0);
        true
    }

    fn on_read_error(&mut self, error: ReadError) -> bool {
        if is_graceful_read_close(&error) {
            return false;
        }
        if self.state.load(Ordering::SeqCst) != STATE_CLOSED {
            crate::report_error(format!("quic conn {}: read error: {error}", self.conn_id));
            self.ctx
                .fail_connection_with_reason(self.conn_id, NB_REASON_PROTOCOL);
        }
        false
    }

    async fn on_command(&mut self, command: Option<Command>) -> bool {
        match command {
            Some(Command::Write(bytes)) => self.write(bytes).await,
            Some(Command::Close) => {
                self.finish_send();
                false
            }
            None => false,
        }
    }

    async fn write(&mut self, bytes: Bytes) -> bool {
        let len = bytes.len();
        let result = self.send.write_all(&bytes).await;
        self.counters
            .outbound_bytes
            .fetch_sub(len, Ordering::SeqCst);
        if result.is_err() {
            self.ctx
                .fail_connection_with_reason(self.conn_id, NB_REASON_PROTOCOL);
            return false;
        }
        if self.counters.write_blocked.swap(false, Ordering::SeqCst) {
            self.ctx
                .emit_non_terminal(self.conn_id, NB_EVENT_WRITABLE, 0, 0);
        }
        true
    }

    fn finish_send(&mut self) {
        let _ = self.send.finish();
    }

    fn finish(self) {
        if matches!(
            self.state.load(Ordering::SeqCst),
            STATE_CONNECTING | STATE_CONNECTED
        ) {
            self.state.store(STATE_CLOSED, Ordering::SeqCst);
            self.ctx.emit_terminal(self.conn_id);
        }
        self.conn.close(0u32.into(), b"net-bridge close");
    }
}

fn is_graceful_read_close(error: &ReadError) -> bool {
    matches!(
        error,
        ReadError::ClosedStream
            | ReadError::ConnectionLost(quinn::ConnectionError::ApplicationClosed(_))
            | ReadError::ConnectionLost(quinn::ConnectionError::LocallyClosed)
    )
}
