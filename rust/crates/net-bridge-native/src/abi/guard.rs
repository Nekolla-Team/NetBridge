//! FFI-boundary exception-safety guard preventing panics from crossing the extern "C" boundary.

use super::status::{NB_PANIC, NbStatus};
use std::panic::{AssertUnwindSafe, catch_unwind};

#[inline]
pub fn ffi_guard<F>(f: F) -> NbStatus
where
    F: FnOnce() -> NbStatus,
{
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| {
        eprintln!("[net-bridge-native] panic occurred in FFI call");
        NB_PANIC
    })
}
