//! C ABI struct encoding/decoding helpers.

use std::net::SocketAddr;
use std::slice;
use std::str;

use super::status::*;
use super::types::{NB_TRANSPORT_KCP, NB_TRANSPORT_QUIC, NbBytesViewV1, NbSocketAddressV1};
use net_bridge_core::TransportKind;
use net_bridge_core::transport::kcp::config::KcpProfile;

/// Decodes `NbBytesViewV1` into a UTF-8 string slice.
///
/// # Safety
///
/// When `view.length > 0`, `view.data` must point to at least `view.length` bytes of valid initialized
/// memory.
pub unsafe fn bytes_view_to_str<'a>(view: &NbBytesViewV1) -> Result<&'a str, NbStatus> {
    if view.reserved0 != 0 {
        return Err(NB_INVALID_ARGUMENT);
    }
    if view.length == 0 {
        return Ok("");
    }
    if view.data.is_null() {
        return Err(NB_INVALID_ARGUMENT);
    }
    let s = unsafe { slice::from_raw_parts(view.data, view.length as usize) };
    str::from_utf8(s).map_err(|_| NB_INVALID_ARGUMENT)
}

pub fn decode_transport_kind(val: u32) -> Result<TransportKind, NbStatus> {
    match val {
        NB_TRANSPORT_QUIC => Ok(TransportKind::Quic),
        NB_TRANSPORT_KCP => Ok(TransportKind::Kcp),
        _ => Err(NB_INVALID_ARGUMENT),
    }
}

pub fn decode_kcp_profile(val: u32) -> Result<KcpProfile, NbStatus> {
    match val {
        0 | 1 => Ok(KcpProfile::Balanced),
        2 => Ok(KcpProfile::Aggressive),
        _ => Err(NB_INVALID_ARGUMENT),
    }
}

pub fn encode_socket_addr(addr: SocketAddr, out: &mut NbSocketAddressV1) {
    match addr {
        SocketAddr::V4(v4) => {
            out.family = 4;
            out.port = v4.port();
            out.reserved0 = 0;
            out.address = [0u8; 16];
            out.address[0..4].copy_from_slice(&v4.ip().octets());
            out.scope_id = 0;
            out.reserved1 = 0;
        }
        SocketAddr::V6(v6) => {
            out.family = 6;
            out.port = v6.port();
            out.reserved0 = 0;
            out.address = v6.ip().octets();
            out.scope_id = v6.scope_id();
            out.reserved1 = 0;
        }
    }
}
