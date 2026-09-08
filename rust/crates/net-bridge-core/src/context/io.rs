//! Connection data-plane I/O and backpressure accounting.

use std::collections::VecDeque;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use bytes::{Bytes, BytesMut};
use tokio::sync::mpsc;

use super::NativeContext;
use crate::connection::ConnHandle;
use crate::event::NB_EVENT_WRITABLE;
use crate::{BridgeError, Command, DEFAULT_MAX_BUFFERED_BYTES, MAX_IO_CHUNK, STATE_CONNECTED};

impl NativeContext {
    /// Queues one outbound chunk.
    ///
    /// Returns `Ok(0)` when the connection is not writable or its queue/budget is full; the caller
    /// must wait for a WRITABLE event before retrying.
    pub fn write_chunk(&self, conn: u64, data: Bytes) -> Result<usize, BridgeError> {
        if data.is_empty() {
            return Ok(0);
        }
        if data.len() > MAX_IO_CHUNK {
            return Err(BridgeError::InvalidArgument(
                "chunk size exceeds MAX_IO_CHUNK",
            ));
        }

        let handle = self
            .connections
            .get(&conn)
            .map(|entry| Arc::clone(&*entry))
            .ok_or(BridgeError::NoSuchConnection)?;
        if !is_writable(&handle) {
            return Ok(0);
        }

        let len = data.len();
        if !try_reserve(&handle.outbound_bytes, len) {
            handle.write_blocked.store(true, Ordering::SeqCst);
            self.emit_writable_if_unblocked(conn, &handle);
            return Ok(0);
        }

        match handle.to_transport.try_send(Command::Write(data)) {
            Ok(()) => Ok(len),
            Err(mpsc::error::TrySendError::Full(_)) => {
                handle.outbound_bytes.fetch_sub(len, Ordering::SeqCst);
                handle.write_blocked.store(true, Ordering::SeqCst);
                if handle.to_transport.capacity() > 0 {
                    self.emit_writable_if_unblocked(conn, &handle);
                }
                Ok(0)
            }
            Err(mpsc::error::TrySendError::Closed(_)) => {
                handle.outbound_bytes.fetch_sub(len, Ordering::SeqCst);
                Err(BridgeError::ConnectionClosed)
            }
        }
    }

    /// Dequeues up to `max_bytes` from the Java read queue.
    pub fn read_chunk(&self, conn: u64, max_bytes: usize) -> Result<Bytes, BridgeError> {
        let handle = self
            .connections
            .get(&conn)
            .map(|entry| Arc::clone(&*entry))
            .ok_or(BridgeError::NoSuchConnection)?;
        let mut guard = handle
            .to_java
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let (rx, pending) = &mut *guard;

        let Some(first) = take_chunk(rx, pending) else {
            return Ok(Bytes::new());
        };
        let out = if first.len() > max_bytes {
            pending.push_front(first.slice(max_bytes..));
            first.slice(..max_bytes)
        } else {
            collect_chunks(rx, pending, first, max_bytes)
        };
        handle.inbound_bytes.fetch_sub(out.len(), Ordering::SeqCst);
        handle.read_waker.notify_one();
        Ok(out)
    }

    fn emit_writable_if_unblocked(&self, conn: u64, handle: &ConnHandle) {
        let budget_available =
            handle.outbound_bytes.load(Ordering::SeqCst) < DEFAULT_MAX_BUFFERED_BYTES;
        if budget_available && handle.write_blocked.swap(false, Ordering::SeqCst) {
            self.emit_non_terminal(conn, NB_EVENT_WRITABLE, 0, 0);
        }
    }
}

fn is_writable(handle: &ConnHandle) -> bool {
    handle.state.load(Ordering::SeqCst) == STATE_CONNECTED || handle.early_write
}

fn try_reserve(outbound_bytes: &AtomicUsize, len: usize) -> bool {
    let mut current = outbound_bytes.load(Ordering::SeqCst);
    loop {
        if current.saturating_add(len) > DEFAULT_MAX_BUFFERED_BYTES {
            return false;
        }
        match outbound_bytes.compare_exchange_weak(
            current,
            current + len,
            Ordering::SeqCst,
            Ordering::SeqCst,
        ) {
            Ok(_) => return true,
            Err(actual) => current = actual,
        }
    }
}

fn take_chunk(rx: &mut mpsc::Receiver<Bytes>, pending: &mut VecDeque<Bytes>) -> Option<Bytes> {
    pending.pop_front().or_else(|| rx.try_recv().ok())
}

fn collect_chunks(
    rx: &mut mpsc::Receiver<Bytes>,
    pending: &mut VecDeque<Bytes>,
    first: Bytes,
    max_bytes: usize,
) -> Bytes {
    let mut out = BytesMut::with_capacity(max_bytes);
    append_chunk(&mut out, pending, first, max_bytes);
    while out.len() < max_bytes {
        let Some(chunk) = take_chunk(rx, pending) else {
            break;
        };
        append_chunk(&mut out, pending, chunk, max_bytes);
    }
    out.freeze()
}

fn append_chunk(
    out: &mut BytesMut,
    pending: &mut VecDeque<Bytes>,
    mut chunk: Bytes,
    max_bytes: usize,
) {
    let remaining = max_bytes - out.len();
    if chunk.len() > remaining {
        pending.push_front(chunk.slice(remaining..));
        chunk = chunk.slice(..remaining);
    }
    out.extend_from_slice(&chunk);
}
