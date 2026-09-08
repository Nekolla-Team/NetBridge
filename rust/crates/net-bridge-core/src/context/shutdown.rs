//! Context shutdown sequencing and runtime teardown.

use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use super::NativeContext;
use crate::error::BridgeError;

impl NativeContext {
    /// Stops servers and connections, reaps tasks, and tears down the Tokio runtime.
    pub fn shutdown(&self, timeout: Duration) -> Result<(), BridgeError> {
        let deadline = Instant::now() + timeout;
        match self.state.compare_exchange(
            super::CONTEXT_STATE_RUNNING,
            super::CONTEXT_STATE_SHUTTING_DOWN,
            Ordering::SeqCst,
            Ordering::SeqCst,
        ) {
            Ok(_) => {}
            Err(super::CONTEXT_STATE_CLOSED) => return Ok(()),
            Err(_) => {}
        }

        let mut first_error = None;
        self.stop_all_servers(deadline, &mut first_error);
        self.close_all_connections();
        self.reap_tasks(deadline, &mut first_error);

        if let Some(error) = first_error {
            return Err(error);
        }

        self.clear_registries();
        self.shutdown_runtime(deadline);
        self.state
            .store(super::CONTEXT_STATE_CLOSED, Ordering::SeqCst);
        Ok(())
    }

    fn stop_all_servers(&self, deadline: Instant, first_error: &mut Option<BridgeError>) {
        let server_ids: Vec<u64> = self.servers.iter().map(|entry| *entry.key()).collect();
        for server_id in server_ids {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if let Err(error) = self.stop_server_with_timeout(server_id, remaining)
                && first_error.is_none()
            {
                *first_error = Some(error);
            }
        }
    }

    fn close_all_connections(&self) {
        let conn_ids: Vec<u64> = self.connections.iter().map(|entry| *entry.key()).collect();
        for conn_id in conn_ids {
            self.close_connection(conn_id);
        }
    }

    fn reap_tasks(&self, deadline: Instant, first_error: &mut Option<BridgeError>) {
        while !self.conn_tasks.is_empty() || !self.server_tasks.is_empty() {
            if Instant::now() >= deadline {
                if first_error.is_none() {
                    *first_error = Some(BridgeError::Timeout);
                }
                return;
            }
            abort_tasks(&self.conn_tasks);
            abort_tasks(&self.server_tasks);
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    fn clear_registries(&self) {
        let conn_ids: Vec<u64> = self.connections.iter().map(|entry| *entry.key()).collect();
        for conn_id in conn_ids {
            self.remove_conn(conn_id);
        }
        self.conn_tasks.clear();
        self.server_tasks.clear();
    }

    fn shutdown_runtime(&self, deadline: Instant) {
        if let Ok(mut guard) = self.runtime.lock()
            && let Some(runtime) = guard.take()
        {
            runtime.shutdown_timeout(deadline.saturating_duration_since(Instant::now()));
        }
    }
}

impl Drop for NativeContext {
    fn drop(&mut self) {
        let Ok(mut runtime) = self.runtime.lock() else {
            return;
        };
        let Some(runtime) = runtime.take() else {
            return;
        };
        if tokio::runtime::Handle::try_current().is_ok() {
            std::thread::spawn(move || drop(runtime));
        } else {
            drop(runtime);
        }
    }
}

fn abort_tasks(tasks: &dashmap::DashMap<u64, tokio::task::JoinHandle<()>>) {
    for task in tasks.iter() {
        task.value().abort();
    }
}
