//! C ABI 状态码常量与错误映射。

use net_bridge_core::error::BridgeError;

pub type NbStatus = i32;

pub const NB_OK: NbStatus = 0;
pub const NB_WOULD_BLOCK: NbStatus = 1;
pub const NB_NOT_FOUND: NbStatus = 2;
pub const NB_CLOSED: NbStatus = 3;
pub const NB_INVALID_ARGUMENT: NbStatus = 4;
pub const NB_INVALID_STATE: NbStatus = 5;
pub const NB_ABI_MISMATCH: NbStatus = 6;
pub const NB_NATIVE_UNAVAILABLE: NbStatus = 7;
pub const NB_BIND_FAILED: NbStatus = 8;
pub const NB_DNS_FAILED: NbStatus = 9;
pub const NB_CONNECT_FAILED: NbStatus = 10;
pub const NB_SHUTTING_DOWN: NbStatus = 11;
pub const NB_TIMEOUT: NbStatus = 12;
pub const NB_INTERNAL: NbStatus = 13;
pub const NB_PANIC: NbStatus = 14;
pub const NB_UNSUPPORTED: NbStatus = 15;

pub fn map_error(err: BridgeError) -> NbStatus {
    match err {
        BridgeError::RuntimeUnavailable => NB_NATIVE_UNAVAILABLE,
        BridgeError::Bind { .. } => NB_BIND_FAILED,
        BridgeError::Setup { .. } => NB_INTERNAL,
        BridgeError::Dns { .. } => NB_DNS_FAILED,
        BridgeError::Connect { .. } => NB_CONNECT_FAILED,
        BridgeError::NoSuchConnection => NB_NOT_FOUND,
        BridgeError::ConnectionClosed => NB_CLOSED,
        BridgeError::Timeout => NB_TIMEOUT,
        BridgeError::Protocol(_) => NB_INTERNAL,
        BridgeError::Cancelled => NB_CLOSED,
        BridgeError::Internal(_) => NB_INTERNAL,
        BridgeError::IdOverflow => NB_INTERNAL,
        BridgeError::Other(_) => NB_INTERNAL,
    }
}
