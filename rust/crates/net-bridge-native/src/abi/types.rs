//! C ABI v1 类型与结构体定义（与 include/netbridge.h 严格一致）。

use super::status::NbStatus;
use net_bridge_core::NativeContext;
use std::sync::Arc;

pub const NB_ABI_MAJOR: u32 = 1;
pub const NB_ABI_MINOR: u32 = 0;

pub const NB_FEATURE_QUIC: u64 = 1 << 0;
pub const NB_FEATURE_KCP: u64 = 1 << 1;
pub const NB_FEATURE_WRITABLE_EVENT: u64 = 1 << 2;
pub const NB_FEATURE_BINARY_SOCKET_ADDRESS: u64 = 1 << 3;
pub const NB_FEATURE_SERVER_STATE_EVENT: u64 = 1 << 4;

pub const NB_TRANSPORT_QUIC: u32 = 1;
pub const NB_TRANSPORT_KCP: u32 = 2;

pub const NB_CONNECTION_CONNECTING: u32 = 1;
pub const NB_CONNECTION_CONNECTED: u32 = 2;
pub const NB_CONNECTION_CLOSED: u32 = 3;
pub const NB_CONNECTION_FAILED: u32 = 4;

pub const NB_EVENT_CONNECTION_STATE: u32 = 1;
pub const NB_EVENT_DATA_AVAILABLE: u32 = 2;
pub const NB_EVENT_WRITABLE: u32 = 3;
pub const NB_EVENT_ACCEPTED: u32 = 4;
pub const NB_EVENT_SERVER_STATE: u32 = 5;

pub const NB_SERVER_STATE_RUNNING: u32 = 1;
pub const NB_SERVER_STATE_STOPPED: u32 = 2;
pub const NB_SERVER_STATE_FAILED: u32 = 3;

pub const NB_REASON_GENERIC: i64 = 0;
pub const NB_REASON_DNS: i64 = 1;
pub const NB_REASON_SETUP: i64 = 2;
pub const NB_REASON_REFUSED: i64 = 3;
pub const NB_REASON_TIMEOUT: i64 = 4;
pub const NB_REASON_PROTOCOL: i64 = 5;
pub const NB_REASON_CANCELLED: i64 = 6;
pub const NB_REASON_INTERNAL: i64 = 7;

pub struct NbContext(pub Arc<NativeContext>);

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct NbBytesViewV1 {
    pub data: *const u8,
    pub length: u32,
    pub reserved0: u32,
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct NbSocketAddressV1 {
    pub family: u32,
    pub port: u16,
    pub reserved0: u16,
    pub address: [u8; 16],
    pub scope_id: u32,
    pub reserved1: u32,
}

pub const NB_CONTEXT_OPTIONS_V1_MIN_SIZE: u32 = 16;
pub const NB_CALLBACKS_V1_MIN_SIZE: u32 = 16;
pub const NB_CONNECT_OPTIONS_V1_MIN_SIZE: u32 = 40;
pub const NB_SERVER_OPTIONS_V1_MIN_SIZE: u32 = 48;

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct NbContextOptionsV1 {
    pub struct_size: u32,
    pub flags: u32,
    pub worker_threads: u32,
    pub reserved0: u32,
    pub reserved: [u64; 4],
}

pub type NbEventCallbackV1 =
    Option<unsafe extern "C" fn(event_kind: u32, object_id: u64, arg0: i64, arg1: i64)>;

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct NbCallbacksV1 {
    pub struct_size: u32,
    pub reserved0: u32,
    pub on_event: NbEventCallbackV1,
    pub reserved: [u64; 4],
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct NbConnectOptionsV1 {
    pub struct_size: u32,
    pub transport_kind: u32,
    pub host_utf8: NbBytesViewV1,
    pub port: u16,
    pub reserved0: u16,
    pub kcp_profile: u32,
    pub flags: u32,
    pub reserved: [u64; 4],
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct NbServerOptionsV1 {
    pub struct_size: u32,
    pub transport_kind: u32,
    pub bind_host_utf8: NbBytesViewV1,
    pub port: u16,
    pub reserved0: u16,
    pub max_connections: u32,
    pub kcp_profile: u32,
    pub flags: u32,
    pub reserved1: u32,
    pub reserved: [u64; 4],
}

#[repr(C)]
pub struct NbApiV1 {
    pub abi_major: u32,
    pub abi_minor: u32,
    pub struct_size: u32,
    pub reserved0: u32,
    pub feature_bits: u64,

    pub context_create: Option<
        unsafe extern "C" fn(
            options: *const NbContextOptionsV1,
            callbacks: *const NbCallbacksV1,
            out_context: *mut *mut NbContext,
        ) -> NbStatus,
    >,

    pub context_shutdown:
        Option<unsafe extern "C" fn(context: *mut NbContext, timeout_millis: u32) -> NbStatus>,

    pub context_destroy: Option<unsafe extern "C" fn(context: *mut NbContext) -> NbStatus>,

    pub connect: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            options: *const NbConnectOptionsV1,
            out_connection: *mut u64,
        ) -> NbStatus,
    >,

    pub connection_state: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            connection: u64,
            out_state: *mut u32,
        ) -> NbStatus,
    >,

    pub connection_remote_address: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            connection: u64,
            out_address: *mut NbSocketAddressV1,
        ) -> NbStatus,
    >,

    pub connection_write: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            connection: u64,
            data: *const u8,
            length: u32,
            out_written: *mut u32,
        ) -> NbStatus,
    >,

    pub connection_read: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            connection: u64,
            data: *mut u8,
            capacity: u32,
            out_read: *mut u32,
        ) -> NbStatus,
    >,

    pub connection_close:
        Option<unsafe extern "C" fn(context: *mut NbContext, connection: u64) -> NbStatus>,

    pub server_start: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            options: *const NbServerOptionsV1,
            out_server: *mut u64,
        ) -> NbStatus,
    >,

    pub server_port: Option<
        unsafe extern "C" fn(context: *mut NbContext, server: u64, out_port: *mut u16) -> NbStatus,
    >,

    pub server_stop: Option<unsafe extern "C" fn(context: *mut NbContext, server: u64) -> NbStatus>,

    pub reserved: [u64; 8],
}

unsafe impl Sync for NbApiV1 {}
unsafe impl Send for NbApiV1 {}
