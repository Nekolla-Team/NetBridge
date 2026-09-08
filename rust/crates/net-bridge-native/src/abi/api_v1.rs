//! NbApiV1 function-table implementation and netbridge_get_api export.

use std::mem::size_of;
use std::slice;
use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use net_bridge_core::context::CONTEXT_STATE_CLOSED;
use net_bridge_core::event::abi_connection_state;
use net_bridge_core::{BridgeError, NativeContext};

use super::codec::*;
use super::decode::{
    decode_callbacks, decode_connect_options, decode_context_options, decode_server_options,
};
use super::event_sink::CAbiEventSink;
use super::guard::ffi_guard;
use super::status::*;
use super::types::*;

pub static API_V1: NbApiV1 = NbApiV1 {
    abi_major: NB_ABI_MAJOR,
    abi_minor: NB_ABI_MINOR,
    struct_size: size_of::<NbApiV1>() as u32,
    reserved0: 0,
    feature_bits: NB_FEATURE_QUIC
        | NB_FEATURE_KCP
        | NB_FEATURE_WRITABLE_EVENT
        | NB_FEATURE_BINARY_SOCKET_ADDRESS
        | NB_FEATURE_SERVER_STATE_EVENT,

    context_create: Some(context_create),
    context_shutdown: Some(context_shutdown),
    context_destroy: Some(context_destroy),

    connect: Some(connect),
    connection_state: Some(connection_state),
    connection_remote_address: Some(connection_remote_address),
    connection_write: Some(connection_write),
    connection_read: Some(connection_read),
    connection_close: Some(connection_close),

    server_start: Some(server_start),
    server_port: Some(server_port),
    server_stop: Some(server_stop),

    reserved: [0; 8],
};

/// Returns the net-bridge C ABI v1 function table.
///
/// # Safety
///
/// `out_api` must be NULL or point to writable, correctly aligned storage for
/// a `*const nb_api_v1_t`. A NULL `out_api` yields `NB_INVALID_ARGUMENT`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn netbridge_get_api(
    requested_major: u32,
    minimum_minor: u32,
    out_api: *mut *const NbApiV1,
) -> NbStatus {
    ffi_guard(|| {
        if out_api.is_null() {
            return NB_INVALID_ARGUMENT;
        }
        if requested_major != NB_ABI_MAJOR || minimum_minor > NB_ABI_MINOR {
            return NB_ABI_MISMATCH;
        }
        unsafe {
            *out_api = &API_V1;
        }
        NB_OK
    })
}

unsafe extern "C" fn context_create(
    options: *const NbContextOptionsV1,
    callbacks: *const NbCallbacksV1,
    out_context: *mut *mut NbContext,
) -> NbStatus {
    ffi_guard(|| {
        if out_context.is_null() {
            return NB_INVALID_ARGUMENT;
        }
        let event_callback = match unsafe { decode_callbacks(callbacks) } {
            Ok(callback) => callback,
            Err(status) => return status,
        };
        let worker_threads = match unsafe { decode_context_options(options) } {
            Ok(worker_threads) => worker_threads,
            Err(status) => return status,
        };

        let sink = Arc::new(CAbiEventSink::new(event_callback));
        match NativeContext::new(worker_threads, Some(sink)) {
            Ok(ctx) => {
                unsafe {
                    *out_context = Box::into_raw(Box::new(NbContext(ctx)));
                }
                NB_OK
            }
            Err(e) => map_error(e),
        }
    })
}

unsafe extern "C" fn context_shutdown(context: *mut NbContext, timeout_millis: u32) -> NbStatus {
    ffi_guard(|| {
        if context.is_null() {
            return NB_INVALID_ARGUMENT;
        }
        let ctx = unsafe { &(*context).0 };
        match ctx.shutdown(Duration::from_millis(timeout_millis as u64)) {
            Ok(()) => NB_OK,
            Err(e) => map_error(e),
        }
    })
}

unsafe extern "C" fn context_destroy(context: *mut NbContext) -> NbStatus {
    ffi_guard(|| {
        if context.is_null() {
            return NB_INVALID_ARGUMENT;
        }
        let ctx_ref = unsafe { &(*context).0 };
        if ctx_ref.state() != CONTEXT_STATE_CLOSED {
            return NB_INVALID_STATE;
        }
        unsafe {
            drop(Box::from_raw(context));
        }
        NB_OK
    })
}

unsafe extern "C" fn connect(
    context: *mut NbContext,
    options: *const NbConnectOptionsV1,
    out_connection: *mut u64,
) -> NbStatus {
    ffi_guard(|| {
        if context.is_null() || out_connection.is_null() {
            return NB_INVALID_ARGUMENT;
        }
        let options = match unsafe { decode_connect_options(options) } {
            Ok(options) => options,
            Err(status) => return status,
        };

        let kind = match decode_transport_kind(options.transport_kind) {
            Ok(k) => k,
            Err(s) => return s,
        };
        let profile = match decode_kcp_profile(options.kcp_profile) {
            Ok(p) => p,
            Err(s) => return s,
        };
        let host = match unsafe { bytes_view_to_str(&options.host) } {
            Ok(h) => h,
            Err(s) => return s,
        };
        if options.port == 0 || host.is_empty() {
            return NB_INVALID_ARGUMENT;
        }

        let ctx = unsafe { &(*context).0 };
        match ctx.connect(kind, host, options.port, profile) {
            Ok(id) => {
                unsafe {
                    *out_connection = id;
                }
                NB_OK
            }
            Err(e) => map_error(e),
        }
    })
}

unsafe extern "C" fn connection_state(
    context: *mut NbContext,
    connection: u64,
    out_state: *mut u32,
) -> NbStatus {
    ffi_guard(|| {
        if context.is_null() || out_state.is_null() || connection == 0 {
            return NB_INVALID_ARGUMENT;
        }
        let ctx = unsafe { &(*context).0 };
        match ctx.connection_state(connection) {
            Some(s) => {
                unsafe {
                    *out_state = abi_connection_state(s);
                }
                NB_OK
            }
            None => NB_NOT_FOUND,
        }
    })
}

unsafe extern "C" fn connection_remote_address(
    context: *mut NbContext,
    connection: u64,
    out_address: *mut NbSocketAddressV1,
) -> NbStatus {
    ffi_guard(|| {
        if context.is_null() || out_address.is_null() || connection == 0 {
            return NB_INVALID_ARGUMENT;
        }
        let ctx = unsafe { &(*context).0 };
        match ctx.connection_remote_addr(connection) {
            Some(addr) => {
                encode_socket_addr(addr, unsafe { &mut *out_address });
                NB_OK
            }
            None => NB_NOT_FOUND,
        }
    })
}

unsafe extern "C" fn connection_write(
    context: *mut NbContext,
    connection: u64,
    data: *const u8,
    length: u32,
    out_written: *mut u32,
) -> NbStatus {
    ffi_guard(|| {
        if context.is_null() || out_written.is_null() || connection == 0 {
            return NB_INVALID_ARGUMENT;
        }
        if length > 0 && data.is_null() {
            return NB_INVALID_ARGUMENT;
        }
        if length > 65536 {
            return NB_INVALID_ARGUMENT;
        }
        let bytes = if length == 0 {
            Bytes::new()
        } else {
            let slice = unsafe { slice::from_raw_parts(data, length as usize) };
            Bytes::copy_from_slice(slice)
        };

        let ctx = unsafe { &(*context).0 };
        match ctx.write_chunk(connection, bytes) {
            Ok(n) => {
                unsafe {
                    *out_written = n as u32;
                }
                if n == 0 && length > 0 {
                    NB_WOULD_BLOCK
                } else {
                    NB_OK
                }
            }
            Err(e) => map_error(e),
        }
    })
}

unsafe extern "C" fn connection_read(
    context: *mut NbContext,
    connection: u64,
    data: *mut u8,
    capacity: u32,
    out_read: *mut u32,
) -> NbStatus {
    ffi_guard(|| {
        if context.is_null() || out_read.is_null() || connection == 0 {
            return NB_INVALID_ARGUMENT;
        }
        if capacity > 0 && data.is_null() {
            return NB_INVALID_ARGUMENT;
        }
        if capacity > 65536 {
            return NB_INVALID_ARGUMENT;
        }

        let ctx = unsafe { &(*context).0 };
        match ctx.read_chunk(connection, capacity as usize) {
            Ok(bytes) => {
                let n = bytes.len();
                if n > 0 {
                    unsafe {
                        slice::from_raw_parts_mut(data, n).copy_from_slice(&bytes);
                    }
                }
                unsafe {
                    *out_read = n as u32;
                }
                if n == 0 && capacity > 0 {
                    NB_WOULD_BLOCK
                } else {
                    NB_OK
                }
            }
            Err(e) => map_error(e),
        }
    })
}

unsafe extern "C" fn connection_close(context: *mut NbContext, connection: u64) -> NbStatus {
    ffi_guard(|| {
        if context.is_null() || connection == 0 {
            return NB_INVALID_ARGUMENT;
        }
        let ctx = unsafe { &(*context).0 };
        if ctx.close_connection(connection) {
            NB_OK
        } else {
            NB_NOT_FOUND
        }
    })
}

unsafe extern "C" fn server_start(
    context: *mut NbContext,
    options: *const NbServerOptionsV1,
    out_server: *mut u64,
) -> NbStatus {
    ffi_guard(|| {
        if context.is_null() || out_server.is_null() {
            return NB_INVALID_ARGUMENT;
        }
        let options = match unsafe { decode_server_options(options) } {
            Ok(options) => options,
            Err(status) => return status,
        };

        let kind = match decode_transport_kind(options.transport_kind) {
            Ok(k) => k,
            Err(s) => return s,
        };
        let profile = match decode_kcp_profile(options.kcp_profile) {
            Ok(p) => p,
            Err(s) => return s,
        };
        let bind_str = match unsafe { bytes_view_to_str(&options.bind_host) } {
            Ok(s) => s,
            Err(s) => return s,
        };
        let bind = if bind_str.trim().is_empty() {
            None
        } else {
            match bind_str.trim().parse() {
                Ok(ip) => Some(ip),
                Err(_) => return NB_INVALID_ARGUMENT,
            }
        };

        let ctx = unsafe { &(*context).0 };
        match ctx.start_server(
            kind,
            options.port,
            options.max_connections as usize,
            bind,
            profile,
        ) {
            Ok(id) => {
                unsafe {
                    *out_server = id;
                }
                NB_OK
            }
            Err(e) => map_error(e),
        }
    })
}

unsafe extern "C" fn server_port(
    context: *mut NbContext,
    server: u64,
    out_port: *mut u16,
) -> NbStatus {
    ffi_guard(|| {
        if context.is_null() || out_port.is_null() || server == 0 {
            return NB_INVALID_ARGUMENT;
        }
        let ctx = unsafe { &(*context).0 };
        match ctx.server_port(server) {
            Some(port) => {
                unsafe {
                    *out_port = port;
                }
                NB_OK
            }
            None => NB_NOT_FOUND,
        }
    })
}

unsafe extern "C" fn server_stop(context: *mut NbContext, server: u64) -> NbStatus {
    ffi_guard(|| {
        if context.is_null() || server == 0 {
            return NB_INVALID_ARGUMENT;
        }
        let ctx = unsafe { &(*context).0 };
        match ctx.stop_server(server) {
            Ok(()) => NB_OK,
            Err(BridgeError::NoSuchConnection) => NB_NOT_FOUND,
            Err(e) => map_error(e),
        }
    })
}
