//! Server handle and lifecycle state.

use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Condvar, Mutex};

use tokio_util::sync::CancellationToken;

use crate::SERVER_STATE_RUNNING;

/// Server handle stored in the registry.
pub(crate) struct ServerHandle {
    /// Cancels the accept loop while preserving connections that were already handed to Java.
    pub(crate) shutdown: CancellationToken,
    pub(crate) port: u16,
    /// Server runtime state: RUNNING(1), STOPPED(2), FAILED(3).
    pub(crate) state: Arc<AtomicU8>,
    /// Serializes the linearization points for accept commit and stop.
    pub(crate) commit_lock: Arc<Mutex<()>>,
    /// Synchronization notification for stop completion.
    pub(crate) stopped_pair: Arc<(Mutex<bool>, Condvar)>,
}

impl ServerHandle {
    /// Returns true while the server is accepting new connections.
    pub(crate) fn is_running(&self) -> bool {
        self.state.load(Ordering::SeqCst) == SERVER_STATE_RUNNING
    }
}
