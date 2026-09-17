//! Selects the atomic implementation: the real one by default, loom's when model checking.

#[cfg(not(loom))]
pub(crate) use core::sync::atomic::{AtomicU64, Ordering, fence};
#[cfg(loom)]
pub(crate) use loom::sync::atomic::{AtomicU64, Ordering, fence};

/// Compile-time guard: the ring protocol requires lock-free 64-bit atomics.
const _: () = {
    #[cfg(not(target_has_atomic = "64"))]
    compile_error!("NetBridge shared IO requires lock-free 64-bit atomics");
};
