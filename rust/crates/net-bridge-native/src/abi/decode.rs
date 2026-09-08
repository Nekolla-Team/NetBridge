//! Prefix-safe decoding of versioned C ABI option structs.

use std::mem::size_of;
use std::ptr;
use std::slice;

use super::status::{NB_INVALID_ARGUMENT, NB_UNSUPPORTED, NbStatus};
use super::types::{
    NB_CALLBACKS_V1_MIN_SIZE, NB_CONNECT_OPTIONS_V1_MIN_SIZE, NB_CONTEXT_OPTIONS_V1_MIN_SIZE,
    NB_SERVER_OPTIONS_V1_MIN_SIZE, NbBytesViewV1, NbCallbacksV1, NbConnectOptionsV1,
    NbContextOptionsV1, NbEventCallbackV1, NbServerOptionsV1,
};

pub(crate) struct ConnectOptions {
    pub(crate) transport_kind: u32,
    pub(crate) host: NbBytesViewV1,
    pub(crate) port: u16,
    pub(crate) kcp_profile: u32,
}

pub(crate) struct ServerOptions {
    pub(crate) transport_kind: u32,
    pub(crate) bind_host: NbBytesViewV1,
    pub(crate) port: u16,
    pub(crate) max_connections: u32,
    pub(crate) kcp_profile: u32,
}

/// Decodes the callback table prefix.
///
/// # Safety
///
/// `callbacks` must point to readable memory for at least the declared `struct_size`.
pub(crate) unsafe fn decode_callbacks(
    callbacks: *const NbCallbacksV1,
) -> Result<NbEventCallbackV1, NbStatus> {
    if callbacks.is_null() {
        return Err(NB_INVALID_ARGUMENT);
    }
    let base = callbacks.cast::<u8>();
    let size = unsafe { read_u32(base, 0) };
    if size < NB_CALLBACKS_V1_MIN_SIZE {
        return Err(NB_INVALID_ARGUMENT);
    }
    if unsafe { read_u32(base, 4) } != 0 {
        return Err(NB_INVALID_ARGUMENT);
    }
    let callback_ptr = unsafe { read_usize(base, 8) };
    if callback_ptr == 0 {
        return Err(NB_INVALID_ARGUMENT);
    }
    if size >= size_of::<NbCallbacksV1>() as u32 && !unsafe { reserved_words_are_zero(base, 16, 4) }
    {
        return Err(NB_INVALID_ARGUMENT);
    }
    let callback: NbEventCallbackV1 = unsafe { std::mem::transmute(callback_ptr) };
    Ok(callback)
}

/// Decodes context options, returning zero worker threads for a null options pointer.
///
/// # Safety
///
/// `options` must point to readable memory for at least the declared `struct_size`.
pub(crate) unsafe fn decode_context_options(
    options: *const NbContextOptionsV1,
) -> Result<usize, NbStatus> {
    if options.is_null() {
        return Ok(0);
    }
    let base = options.cast::<u8>();
    let size = unsafe { read_u32(base, 0) };
    if size < NB_CONTEXT_OPTIONS_V1_MIN_SIZE {
        return Err(NB_INVALID_ARGUMENT);
    }
    if unsafe { read_u32(base, 4) } != 0 {
        return Err(NB_UNSUPPORTED);
    }
    let worker_threads = unsafe { read_u32(base, 8) } as usize;
    if unsafe { read_u32(base, 12) } != 0 {
        return Err(NB_INVALID_ARGUMENT);
    }
    if size >= size_of::<NbContextOptionsV1>() as u32
        && !unsafe { reserved_words_are_zero(base, 16, 4) }
    {
        return Err(NB_INVALID_ARGUMENT);
    }
    Ok(worker_threads)
}

/// Decodes outbound connection options.
///
/// # Safety
///
/// `options` must point to readable memory for at least the declared `struct_size`.
pub(crate) unsafe fn decode_connect_options(
    options: *const NbConnectOptionsV1,
) -> Result<ConnectOptions, NbStatus> {
    if options.is_null() {
        return Err(NB_INVALID_ARGUMENT);
    }
    let base = options.cast::<u8>();
    let size = unsafe { read_u32(base, 0) };
    if size < NB_CONNECT_OPTIONS_V1_MIN_SIZE {
        return Err(NB_INVALID_ARGUMENT);
    }
    let transport_kind = unsafe { read_u32(base, 4) };
    let host = unsafe { read_bytes_view(base, 8) };
    let port = unsafe { read_u16(base, 24) };
    if unsafe { read_u16(base, 26) } != 0 {
        return Err(NB_INVALID_ARGUMENT);
    }
    let kcp_profile = unsafe { read_u32(base, 28) };
    if unsafe { read_u32(base, 32) } != 0 {
        return Err(NB_UNSUPPORTED);
    }
    if size >= size_of::<NbConnectOptionsV1>() as u32
        && !unsafe { reserved_words_are_zero(base, 40, 4) }
    {
        return Err(NB_INVALID_ARGUMENT);
    }
    Ok(ConnectOptions {
        transport_kind,
        host,
        port,
        kcp_profile,
    })
}

/// Decodes server bind options.
///
/// # Safety
///
/// `options` must point to readable memory for at least the declared `struct_size`.
pub(crate) unsafe fn decode_server_options(
    options: *const NbServerOptionsV1,
) -> Result<ServerOptions, NbStatus> {
    if options.is_null() {
        return Err(NB_INVALID_ARGUMENT);
    }
    let base = options.cast::<u8>();
    let size = unsafe { read_u32(base, 0) };
    if size < NB_SERVER_OPTIONS_V1_MIN_SIZE {
        return Err(NB_INVALID_ARGUMENT);
    }
    let transport_kind = unsafe { read_u32(base, 4) };
    let bind_host = unsafe { read_bytes_view(base, 8) };
    let port = unsafe { read_u16(base, 24) };
    if unsafe { read_u16(base, 26) } != 0 {
        return Err(NB_INVALID_ARGUMENT);
    }
    let max_connections = unsafe { read_u32(base, 28) };
    let kcp_profile = unsafe { read_u32(base, 32) };
    if unsafe { read_u32(base, 36) } != 0 {
        return Err(NB_UNSUPPORTED);
    }
    if unsafe { read_u32(base, 40) } != 0 {
        return Err(NB_INVALID_ARGUMENT);
    }
    if size >= size_of::<NbServerOptionsV1>() as u32
        && !unsafe { reserved_words_are_zero(base, 48, 4) }
    {
        return Err(NB_INVALID_ARGUMENT);
    }
    Ok(ServerOptions {
        transport_kind,
        bind_host,
        port,
        max_connections,
        kcp_profile,
    })
}

unsafe fn read_u16(base: *const u8, offset: usize) -> u16 {
    unsafe { ptr::read_unaligned(base.add(offset).cast::<u16>()) }
}

unsafe fn read_u32(base: *const u8, offset: usize) -> u32 {
    unsafe { ptr::read_unaligned(base.add(offset).cast::<u32>()) }
}

unsafe fn read_usize(base: *const u8, offset: usize) -> usize {
    unsafe { ptr::read_unaligned(base.add(offset).cast::<usize>()) }
}

unsafe fn read_bytes_view(base: *const u8, offset: usize) -> NbBytesViewV1 {
    unsafe { ptr::read_unaligned(base.add(offset).cast::<NbBytesViewV1>()) }
}

unsafe fn reserved_words_are_zero(base: *const u8, offset: usize, count: usize) -> bool {
    let words = unsafe { slice::from_raw_parts(base.add(offset).cast::<u64>(), count) };
    words.iter().all(|word| *word == 0)
}
