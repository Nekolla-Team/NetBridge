//! Task supervision for connection and server futures.

use std::future::Future;
use std::sync::Arc;
use std::sync::atomic::Ordering;

use futures_util::FutureExt;

use super::NativeContext;

impl NativeContext {
    /// Spawns a connection future with panic isolation and registry cleanup.
    pub(crate) fn spawn_connection_task<F>(
        self: &Arc<Self>,
        what: &'static str,
        conn_id: u64,
        fut: F,
    ) where
        F: Future<Output = ()> + Send + 'static,
    {
        let ctx = Arc::clone(self);
        let (start_tx, start_rx) = tokio::sync::oneshot::channel::<()>();
        let join_handle = self.handle.spawn(async move {
            struct ConnTaskGuard {
                ctx: Arc<NativeContext>,
                id: u64,
            }

            impl Drop for ConnTaskGuard {
                fn drop(&mut self) {
                    self.ctx.conn_tasks.remove(&self.id);
                }
            }

            let _guard = ConnTaskGuard {
                ctx: Arc::clone(&ctx),
                id: conn_id,
            };
            let _ = start_rx.await;
            if let Err(payload) = std::panic::AssertUnwindSafe(fut).catch_unwind().await {
                crate::report_error(format!(
                    "{what} panicked: {}",
                    crate::describe_panic(&payload)
                ));
                ctx.fail_connection_with_reason(conn_id, crate::event::NB_REASON_INTERNAL);
            }
        });
        self.conn_tasks.insert(conn_id, join_handle);
        let _ = start_tx.send(());
    }

    /// Spawns a server future with panic isolation and failure-state publication.
    pub(crate) fn spawn_server_task<F>(self: &Arc<Self>, what: &'static str, server_id: u64, fut: F)
    where
        F: Future<Output = ()> + Send + 'static,
    {
        let ctx = Arc::clone(self);
        let (start_tx, start_rx) = tokio::sync::oneshot::channel::<()>();
        let join_handle = self.handle.spawn(async move {
            struct ServerTaskGuard {
                ctx: Arc<NativeContext>,
                id: u64,
            }

            impl Drop for ServerTaskGuard {
                fn drop(&mut self) {
                    self.ctx.server_tasks.remove(&self.id);
                }
            }

            let _guard = ServerTaskGuard {
                ctx: Arc::clone(&ctx),
                id: server_id,
            };
            let _ = start_rx.await;
            if let Err(payload) = std::panic::AssertUnwindSafe(fut).catch_unwind().await {
                crate::report_error(format!(
                    "{what} panicked: {}",
                    crate::describe_panic(&payload)
                ));
                mark_server_failed(&ctx, server_id);
            }
        });
        self.server_tasks.insert(server_id, join_handle);
        let _ = start_tx.send(());
    }
}

fn mark_server_failed(ctx: &NativeContext, server_id: u64) {
    if let Some(server) = ctx.servers.get(&server_id) {
        server
            .state
            .store(crate::SERVER_STATE_FAILED, Ordering::SeqCst);
    }
    ctx.emit_server_state(server_id, crate::SERVER_STATE_FAILED);
    ctx.servers.remove(&server_id);
}
