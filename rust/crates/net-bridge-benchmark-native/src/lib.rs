//! net-bridge-benchmark-native: minimal C ABI probe for L0 raw-FFM benchmarks.
//!
//! Deliberately not part of the production ABI: no versioned function table, no
//! `netbridge_get_api`, no cbindgen integration, no Tokio. The measured probes
//! do not allocate or touch the production context.
//!
//! All probes return an `i32` status (0 = OK) so a broken binding can never
//! crash the JVM silently across the boundary.

/// Status constant returned by every probe (always 0 today).
pub const NB_BENCH_OK: i32 = 0;

/// No-op probe: measures the pure Java->native call overhead.
///
/// # Safety
///
/// Always safe to call; takes no pointers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nb_bench_noop() -> i32 {
    NB_BENCH_OK
}

/// Echoes the incoming `u64` through an out pointer.
///
/// # Safety
///
/// `out` must point to writable, correctly aligned storage for a `u64`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nb_bench_echo_u64(value: u64, out: *mut u64) -> i32 {
    if out.is_null() {
        return -1;
    }
    unsafe { *out = value };
    NB_BENCH_OK
}

/// Writes a fixed sentinel to an out pointer (pointer-store microbenchmark).
///
/// # Safety
///
/// `out` must point to writable, correctly aligned storage for a `u32`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nb_bench_out_u32(out: *mut u32) -> i32 {
    if out.is_null() {
        return -1;
    }
    unsafe { *out = 0x5EED_1234 };
    NB_BENCH_OK
}

/// Sums `len` bytes of a read-only buffer into `*out_sum`.
///
/// # Safety
///
/// `src` must be readable for `len` bytes; `out_sum` must point to writable,
/// correctly aligned storage for a `u64`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nb_bench_read_buffer(
    src: *const u8,
    len: usize,
    out_sum: *mut u64,
) -> i32 {
    if src.is_null() || out_sum.is_null() {
        return -1;
    }
    let bytes = unsafe { core::slice::from_raw_parts(src, len) };
    let mut sum: u64 = 0;
    for &b in bytes {
        sum = sum.wrapping_add(b as u64);
    }
    unsafe { *out_sum = sum };
    NB_BENCH_OK
}

/// Fills `len` bytes of a writable buffer with a constant (native write probe).
///
/// # Safety
///
/// `dst` must be writable for `len` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nb_bench_write_buffer(dst: *mut u8, len: usize, value: u8) -> i32 {
    if dst.is_null() {
        return -1;
    }
    let bytes = unsafe { core::slice::from_raw_parts_mut(dst, len) };
    for b in bytes.iter_mut() {
        *b = value;
    }
    NB_BENCH_OK
}

/// Calls back into Java once: `cb(arg)` is invoked and the result written out.
///
/// # Safety
///
/// `cb` must be a valid C ABI function pointer valid to call with one `u64`;
/// `out` must point to writable, correctly aligned storage for a `u64`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nb_bench_invoke_callback(
    cb: Option<unsafe extern "C" fn(u64) -> u64>,
    arg: u64,
    out: *mut u64,
) -> i32 {
    if out.is_null() {
        return -1;
    }
    let result = match cb {
        Some(callback) => unsafe { callback(arg) },
        None => arg,
    };
    unsafe { *out = result };
    NB_BENCH_OK
}
