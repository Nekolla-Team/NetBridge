//! net-bridge-core: Pure Rust transport core for QUIC and KCP.
#![forbid(unsafe_code)]

mod connection;
pub mod context;
pub mod error;
pub mod event;
mod server;
pub mod socket_util;
pub mod transport;

#[cfg(test)]
mod tests;

pub use context::NativeContext;
pub use error::BridgeError;
pub use event::{EventSink, NoopEventSink};
pub use transport::TransportKind;

use std::any::Any;
use std::sync::atomic::{AtomicUsize, Ordering};

use bytes::Bytes;

pub(crate) use connection::ConnHandle;
pub(crate) use server::ServerHandle;

/// Generic control command sent to a transport write task.
#[derive(Debug)]
pub(crate) enum Command {
    Write(Bytes),
    Close,
}

/// Internal connection-state constants; core values are mapped to ABI values 1..4 through
/// `abi_connection_state` when exposed.
pub(crate) const STATE_CONNECTING: u32 = 0;
pub(crate) const STATE_CONNECTED: u32 = 1;
pub(crate) const STATE_CLOSED: u32 = 2;
pub(crate) const STATE_FAILED: u32 = 3;

/// Internal server-state constants.
pub(crate) const SERVER_STATE_RUNNING: u8 = 1;
pub(crate) const SERVER_STATE_STOPPED: u8 = 2;
pub(crate) const SERVER_STATE_FAILED: u8 = 3;

/// Default per-connection outbound/inbound byte-budget limit (4 MiB)
pub(crate) const DEFAULT_MAX_BUFFERED_BYTES: usize = 4 * 1024 * 1024;
/// Maximum size of a single I/O chunk (64 KiB)
pub(crate) const MAX_IO_CHUNK: usize = 64 * 1024;

/// Report errors immediately to stderr, which the Minecraft launcher redirects to logs/latest.log.
pub(crate) fn report_error(msg: String) {
    eprintln!("[net-bridge-native] error: {msg}");
}

/// Attempts to count a new connection against the server instance; rolls back and rejects it if the
/// limit is exceeded.
pub(crate) fn try_admit(count: &AtomicUsize, max: usize) -> bool {
    let prev = count.fetch_add(1, Ordering::Relaxed);
    if prev >= max {
        count.fetch_sub(1, Ordering::Relaxed);
        return false;
    }
    true
}

/// Extracts readable text from a panic payload; non-string payloads use a placeholder.
fn describe_panic(payload: &Box<dyn Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic payload".to_string()
    }
}
