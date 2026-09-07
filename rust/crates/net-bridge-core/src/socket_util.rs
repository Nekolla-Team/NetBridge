//! Shared UDP foundation built on socket2.

use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};

use socket2::{Domain, Protocol, Socket, Type};

/// Target send/receive buffer size: 4 MB.
const BUF_SIZE: usize = 4 * 1024 * 1024;

fn warn(msg: String) {
    eprintln!("[net-bridge-native] warn: {msg}");
}

/// Creates a UDP socket and binds it to `addr`, configuring dual-stack behavior and best-effort buffer
/// sizing; `reuse_addr` is enabled only for servers because client port reuse would introduce ambiguity.
fn bind_socket(addr: SocketAddr, reuse_addr: bool) -> io::Result<UdpSocket> {
    let socket = Socket::new(Domain::for_address(addr), Type::DGRAM, Some(Protocol::UDP))?;
    if addr.is_ipv6() {
        // Explicit dual stack: accept v4-mapped connections. On failure, warn and degrade to IPv6-only.
        if let Err(e) = socket.set_only_v6(false) {
            warn(format!("set IPV6_V6ONLY=false on {addr}: {e}"));
        }
    }
    if reuse_addr {
        // Best effort: ignore unsupported buffer tuning on individual platforms without affecting
        // correctness.
        let _ = socket.set_reuse_address(true);
        #[cfg(all(unix, not(target_os = "solaris"), not(target_os = "illumos")))]
        let _ = socket.set_reuse_port(true);
    }
    let _ = socket.set_recv_buffer_size(BUF_SIZE);
    let _ = socket.set_send_buffer_size(BUF_SIZE);
    socket.bind(&addr.into())?;
    Ok(socket.into())
}

/// Server listening socket. When `bind` is `None`, prefer IPv6 dual-stack `[::]:port` (also accepting
/// v4-mapped addresses); if the system disables dual stack, fall back to IPv4-only `0.0.0.0:port`;
/// `Some(ip)` binds only that address and returns an error immediately on failure. Returns
/// `(socket, actual_bound_address)`.
pub fn bind_server(port: u16, bind: Option<IpAddr>) -> io::Result<(UdpSocket, SocketAddr)> {
    match bind {
        Some(ip) => {
            let addr = SocketAddr::new(ip, port);
            let s = bind_socket(addr, true)?;
            let local = s.local_addr()?;
            Ok((s, local))
        }
        None => {
            let v6 = SocketAddr::from((Ipv6Addr::UNSPECIFIED, port));
            match bind_socket(v6, true) {
                Ok(s) => {
                    let local = s.local_addr()?;
                    Ok((s, local))
                }
                Err(v6_err) => {
                    let v4 = SocketAddr::from((Ipv4Addr::UNSPECIFIED, port));
                    let s = bind_socket(v4, true).map_err(|v4_err| {
                        // Combine both causes so upper-layer logging shows the complete failure in one message, matching prior behavior.
                        io::Error::new(v4_err.kind(), format!("v6: {v6_err}; v4: {v4_err}"))
                    })?;
                    let local = s.local_addr()?;
                    Ok((s, local))
                }
            }
        }
    }
}

/// Client socket: bind the unspecified address matching the remote address family (port 0 is allocated
/// by the system), with no REUSEADDR. IPv6 targets also attempt dual stack so v4-mapped targets remain
/// usable.
pub fn bind_client(remote_is_ipv6: bool) -> io::Result<UdpSocket> {
    let addr: SocketAddr = if remote_is_ipv6 {
        (Ipv6Addr::UNSPECIFIED, 0).into()
    } else {
        (Ipv4Addr::UNSPECIFIED, 0).into()
    };
    bind_socket(addr, false)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    #[test]
    fn server_bind_ephemeral_dual_stack() {
        // Port 0 is allocated by the system; the default path should succeed because CI supports
        // at least one of v4 or v6.
        let (_sock, addr) = bind_server(0, None).expect("bind server");
        assert_ne!(addr.port(), 0);
    }

    #[test]
    fn server_bind_specific_v4() {
        let (_sock, addr) = bind_server(0, Some(IpAddr::V4(Ipv4Addr::LOCALHOST))).expect("bind v4");
        assert_eq!(addr.ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
    }

    #[test]
    fn client_bind_matches_family() {
        let s4 = bind_client(false).expect("client v4");
        assert!(s4.local_addr().expect("local").is_ipv4());
        // The v6 path fails on systems without an IPv6 stack, so assert only when a stack is
        // available.
        if let Ok(s6) = bind_client(true) {
            assert!(s6.local_addr().expect("local").is_ipv6());
        }
    }
}
