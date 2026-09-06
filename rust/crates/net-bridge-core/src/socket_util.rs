//! socket2 统一 UDP 底座。

use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};

use socket2::{Domain, Protocol, Socket, Type};

/// 收发缓冲区目标大小：4MB。
const BUF_SIZE: usize = 4 * 1024 * 1024;

fn warn(msg: String) {
    eprintln!("[net-bridge-native] warn: {msg}");
}

/// 创建 UDP socket 并绑定到 `addr`：双栈选项、尽力而为的缓冲区设置；
/// `reuse_addr` 仅服务端启用（客户端端口复用会引入歧义）。
fn bind_socket(addr: SocketAddr, reuse_addr: bool) -> io::Result<UdpSocket> {
    let socket = Socket::new(Domain::for_address(addr), Type::DGRAM, Some(Protocol::UDP))?;
    if addr.is_ipv6() {
        // 显式双栈：接受 v4-mapped 连接。失败仅告警降级为 IPv6-only。
        if let Err(e) = socket.set_only_v6(false) {
            warn(format!("set IPV6_V6ONLY=false on {addr}: {e}"));
        }
    }
    if reuse_addr {
        // 尽力而为：个别平台不支持时忽略，不影响正确性。
        let _ = socket.set_reuse_address(true);
        #[cfg(all(unix, not(target_os = "solaris"), not(target_os = "illumos")))]
        let _ = socket.set_reuse_port(true);
    }
    let _ = socket.set_recv_buffer_size(BUF_SIZE);
    let _ = socket.set_send_buffer_size(BUF_SIZE);
    socket.bind(&addr.into())?;
    Ok(socket.into())
}

/// 服务端监听 socket。`bind` 为 `None` 时优先 IPv6 双栈 `[::]:port`
/// （同时接受 v4-mapped），系统禁用双栈回退 IPv4-only `0.0.0.0:port`；
/// `Some(ip)` 则仅绑定该地址，失败直接返回错误。
/// 返回 `(socket, 实际绑定地址)`。
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
                        // 合并两个原因：上层日志一次可见全貌（与原实现一致）。
                        io::Error::new(v4_err.kind(), format!("v6: {v6_err}; v4: {v4_err}"))
                    })?;
                    let local = s.local_addr()?;
                    Ok((s, local))
                }
            }
        }
    }
}

/// 客户端 socket：按远端地址族绑定对应未指定地址（端口 0 由系统分配），
/// 无 REUSEADDR。IPv6 目标同样尝试双栈（连接 v4-mapped 目标仍可用）。
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
        // 端口 0：系统分配；默认路径应成功（CI 环境至少支持 v4 或 v6 之一）。
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
        // v6 路径在无 IPv6 栈的环境会失败：仅在有栈时断言。
        if let Ok(s6) = bind_client(true) {
            assert!(s6.local_addr().expect("local").is_ipv6());
        }
    }
}
