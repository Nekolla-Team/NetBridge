//! C ABI v1 type and struct definitions.
//!
//! This is the authoritative source for `netbridge.h`: cbindgen generates `include/netbridge.h` from
//! this module (see `cargo xtask abi-header update`).

use super::status::NbStatus;
use net_bridge_core::NativeContext;
use std::sync::Arc;

/// C ABI major version.
pub const NB_ABI_MAJOR: u32 = 1;
/// C ABI minor version.
pub const NB_ABI_MINOR: u32 = 0;

/// Supports the QUIC transport.
pub const NB_FEATURE_QUIC: u64 = 1 << 0;
/// Supports the KCP transport.
pub const NB_FEATURE_KCP: u64 = 1 << 1;
/// Emits `NB_EVENT_WRITABLE` when a connection can accept more data.
pub const NB_FEATURE_WRITABLE_EVENT: u64 = 1 << 2;
/// Reports remote addresses as a fixed binary socket address struct.
pub const NB_FEATURE_BINARY_SOCKET_ADDRESS: u64 = 1 << 3;
/// Emits `NB_EVENT_SERVER_STATE` for server lifecycle transitions.
pub const NB_FEATURE_SERVER_STATE_EVENT: u64 = 1 << 4;

/// QUIC transport kind selector.
pub const NB_TRANSPORT_QUIC: u32 = 1;
/// KCP transport kind selector.
pub const NB_TRANSPORT_KCP: u32 = 2;

/// Connection is being established.
pub const NB_CONNECTION_CONNECTING: u32 = 1;
/// Connection is established and usable.
pub const NB_CONNECTION_CONNECTED: u32 = 2;
/// Connection reached a closed terminal state.
pub const NB_CONNECTION_CLOSED: u32 = 3;
/// Connection failed and reached a terminal state.
pub const NB_CONNECTION_FAILED: u32 = 4;

/// Connection state changed (`arg0`/`arg1` carry the state and reason).
pub const NB_EVENT_CONNECTION_STATE: u32 = 1;
/// Readable data arrived for an accepted/server connection (`object_id`).
pub const NB_EVENT_DATA_AVAILABLE: u32 = 2;
/// A previously blocked connection can accept more data.
pub const NB_EVENT_WRITABLE: u32 = 3;
/// A server accepted a new connection (`object_id` is the connection id).
pub const NB_EVENT_ACCEPTED: u32 = 4;
/// Server lifecycle state changed (`arg0` is a `NB_SERVER_STATE_*` value).
pub const NB_EVENT_SERVER_STATE: u32 = 5;

/// Server is running and accepting.
pub const NB_SERVER_STATE_RUNNING: u32 = 1;
/// Server stopped cleanly.
pub const NB_SERVER_STATE_STOPPED: u32 = 2;
/// Server failed and stopped.
pub const NB_SERVER_STATE_FAILED: u32 = 3;

/// Generic failure reason.
pub const NB_REASON_GENERIC: i64 = 0;
/// DNS resolution failed.
pub const NB_REASON_DNS: i64 = 1;
/// Local setup failed.
pub const NB_REASON_SETUP: i64 = 2;
/// The peer refused the connection.
pub const NB_REASON_REFUSED: i64 = 3;
/// The connection timed out.
pub const NB_REASON_TIMEOUT: i64 = 4;
/// A protocol error occurred.
pub const NB_REASON_PROTOCOL: i64 = 5;
/// The operation was cancelled.
pub const NB_REASON_CANCELLED: i64 = 6;
/// An internal error occurred.
pub const NB_REASON_INTERNAL: i64 = 7;

/// Minimum `struct_size` for `nb_context_options_v1_t`.
pub const NB_CONTEXT_OPTIONS_V1_MIN_SIZE: u32 = 16;
/// Minimum `struct_size` for `nb_callbacks_v1_t`.
pub const NB_CALLBACKS_V1_MIN_SIZE: u32 = 16;
/// Minimum `struct_size` for `nb_connect_options_v1_t`.
pub const NB_CONNECT_OPTIONS_V1_MIN_SIZE: u32 = 40;
/// Minimum `struct_size` for `nb_server_options_v1_t`.
pub const NB_SERVER_OPTIONS_V1_MIN_SIZE: u32 = 48;

/// Opaque native runtime context. Never dereferenced from C.
pub struct NbContext(pub Arc<NativeContext>);

/// Fixed-width connection handle, scoped to the owning native context.
pub type NbConnection = u64;
/// Fixed-width server handle, scoped to the owning native context.
pub type NbServer = u64;

/// Borrowed UTF-8 payload view. `data` is only valid for the duration of the downcall that produced it;
/// `length == 0` with `data == NULL` is empty.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct NbBytesViewV1 {
    /// Pointer to the payload bytes (may be NULL when `length == 0`).
    pub data: *const u8,
    /// Number of valid bytes behind `data`.
    pub length: u32,
    /// Reserved for ABI growth; must be zero.
    pub reserved0: u32,
}

/// Fixed binary socket address.
///
/// `family` is 4 (IPv4) or 6 (IPv6). `port` is in host byte order. For IPv4 the first 4 bytes of
/// `address` hold the address and `scope_id` is always zero; for IPv6 all 16 bytes hold the address and
/// `scope_id` carries the interface scope id.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct NbSocketAddressV1 {
    /// Address family: 4 or 6.
    pub family: u32,
    /// Port in host byte order.
    pub port: u16,
    /// Reserved for ABI growth; must be zero.
    pub reserved0: u16,
    /// 16-byte address storage (IPv4 uses the first 4 bytes).
    pub address: [u8; 16],
    /// IPv6 scope id; always zero for IPv4.
    pub scope_id: u32,
    /// Reserved for ABI growth; must be zero.
    pub reserved1: u32,
}

/// Native context creation options.
///
/// A zero value for `worker_threads` selects the runtime default.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct NbContextOptionsV1 {
    /// Size of the caller-provided struct in bytes.
    pub struct_size: u32,
    /// Reserved for ABI growth; must be zero.
    pub flags: u32,
    /// Worker thread count; 0 selects the runtime default.
    pub worker_threads: u32,
    /// Reserved for ABI growth; must be zero.
    pub reserved0: u32,
    /// Reserved for ABI growth; must be zero.
    pub reserved: [u64; 4],
}

/// Event callback invoked on native worker threads.
///
/// It must decode its primitive arguments and return quickly without blocking. NULL is not a valid
/// `on_event`.
pub type NbEventCallbackV1 =
    Option<unsafe extern "C" fn(event_kind: u32, object_id: u64, arg0: i64, arg1: i64)>;

/// Callbacks supplied to `context_create`.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct NbCallbacksV1 {
    /// Size of the caller-provided struct in bytes.
    pub struct_size: u32,
    /// Reserved for ABI growth; must be zero.
    pub reserved0: u32,
    /// Required event callback; must not be NULL.
    pub on_event: NbEventCallbackV1,
    /// Reserved for ABI growth; must be zero.
    pub reserved: [u64; 4],
}

/// Outbound connection options for `connect`.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct NbConnectOptionsV1 {
    /// Size of the caller-provided struct in bytes.
    pub struct_size: u32,
    /// Transport selector: `NB_TRANSPORT_QUIC` or `NB_TRANSPORT_KCP`.
    pub transport_kind: u32,
    /// UTF-8 host name (not NUL-terminated, call-scoped).
    pub host_utf8: NbBytesViewV1,
    /// Destination port in host byte order.
    pub port: u16,
    /// Reserved for ABI growth; must be zero.
    pub reserved0: u16,
    /// KCP profile selector (0/1 = balanced, 2 = aggressive).
    pub kcp_profile: u32,
    /// Reserved for ABI growth; must be zero.
    pub flags: u32,
    /// Reserved for ABI growth; must be zero.
    pub reserved: [u64; 4],
}

/// Server bind options for `server_start`.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct NbServerOptionsV1 {
    /// Size of the caller-provided struct in bytes.
    pub struct_size: u32,
    /// Transport selector: `NB_TRANSPORT_QUIC` or `NB_TRANSPORT_KCP`.
    pub transport_kind: u32,
    /// UTF-8 bind host; empty length selects the unspecified address.
    pub bind_host_utf8: NbBytesViewV1,
    /// Bind port in host byte order (0 selects an ephemeral port).
    pub port: u16,
    /// Reserved for ABI growth; must be zero.
    pub reserved0: u16,
    /// Maximum number of accepted connections.
    pub max_connections: u32,
    /// KCP profile selector (0/1 = balanced, 2 = aggressive).
    pub kcp_profile: u32,
    /// Reserved for ABI growth; must be zero.
    pub flags: u32,
    /// Reserved for ABI growth; must be zero.
    pub reserved1: u32,
    /// Reserved for ABI growth; must be zero.
    pub reserved: [u64; 4],
}

/// Versioned function table returned by `netbridge_get_api`.
///
/// The struct is append-only: new functions are only ever added to the trailing reserved area across
/// minor revisions.
#[repr(C)]
pub struct NbApiV1 {
    /// ABI major version implemented by this table.
    pub abi_major: u32,
    /// ABI minor version implemented by this table.
    pub abi_minor: u32,
    /// Size of the full `NbApiV1` struct in bytes.
    pub struct_size: u32,
    /// Reserved for ABI growth; must be zero.
    pub reserved0: u32,
    /// Bit set of supported `NB_FEATURE_*` capabilities.
    pub feature_bits: u64,

    /// Create a native runtime context.
    pub context_create: Option<
        unsafe extern "C" fn(
            options: *const NbContextOptionsV1,
            callbacks: *const NbCallbacksV1,
            out_context: *mut *mut NbContext,
        ) -> NbStatus,
    >,
    /// Shut down a context, waiting up to `timeout_millis`.
    pub context_shutdown:
        Option<unsafe extern "C" fn(context: *mut NbContext, timeout_millis: u32) -> NbStatus>,
    /// Destroy a shut-down context.
    pub context_destroy: Option<unsafe extern "C" fn(context: *mut NbContext) -> NbStatus>,
    /// Open an outbound connection.
    pub connect: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            options: *const NbConnectOptionsV1,
            out_connection: *mut NbConnection,
        ) -> NbStatus,
    >,
    /// Query the state of a connection.
    pub connection_state: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            connection: NbConnection,
            out_state: *mut u32,
        ) -> NbStatus,
    >,
    /// Query the remote address of a connection.
    pub connection_remote_address: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            connection: NbConnection,
            out_address: *mut NbSocketAddressV1,
        ) -> NbStatus,
    >,
    /// Write up to `length` bytes to a connection.
    pub connection_write: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            connection: NbConnection,
            data: *const u8,
            length: u32,
            out_written: *mut u32,
        ) -> NbStatus,
    >,
    /// Read up to `capacity` bytes from a connection.
    pub connection_read: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            connection: NbConnection,
            data: *mut u8,
            capacity: u32,
            out_read: *mut u32,
        ) -> NbStatus,
    >,
    /// Close a connection.
    pub connection_close:
        Option<unsafe extern "C" fn(context: *mut NbContext, connection: NbConnection) -> NbStatus>,
    /// Start a server and return its handle.
    pub server_start: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            options: *const NbServerOptionsV1,
            out_server: *mut NbServer,
        ) -> NbStatus,
    >,
    /// Query the bound port of a server.
    pub server_port: Option<
        unsafe extern "C" fn(
            context: *mut NbContext,
            server: NbServer,
            out_port: *mut u16,
        ) -> NbStatus,
    >,
    /// Stop a server.
    pub server_stop:
        Option<unsafe extern "C" fn(context: *mut NbContext, server: NbServer) -> NbStatus>,

    /// Reserved for ABI growth; must be zero.
    pub reserved: [u64; 8],
}

unsafe impl Sync for NbApiV1 {}
unsafe impl Send for NbApiV1 {}
