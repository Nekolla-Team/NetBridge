//! KCP facade integration tests: real KCP round trips and close propagation, including the probe-frame path.

use bytes::Bytes;
use std::time::{Duration, Instant};

use crate::context::NativeContext;
use crate::event::{EventSink, NB_EVENT_ACCEPTED};
use crate::transport::TransportKind;
use crate::transport::kcp::config::KcpProfile;
use crate::*;

use std::sync::{Arc, Mutex};

fn wait_state(ctx: &NativeContext, conn: u64, want: u32) {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if ctx.connection_state(conn) == Some(want) {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "timeout waiting for state {want}"
        );
        std::thread::sleep(Duration::from_millis(5));
    }
}

fn wait_disconnected(ctx: &NativeContext, conn: u64) {
    let deadline = Instant::now() + Duration::from_secs(40);
    loop {
        match ctx.connection_state(conn) {
            Some(STATE_CONNECTED) => {}
            _ => return,
        }
        assert!(Instant::now() < deadline, "timeout waiting for disconnect");
        std::thread::sleep(Duration::from_millis(5));
    }
}

fn wait_read(ctx: &NativeContext, conn: u64, want: usize) -> Vec<u8> {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if let Ok(data) = ctx.read_chunk(conn, 65536)
            && data.len() >= want
        {
            return data.to_vec();
        }
        assert!(Instant::now() < deadline, "timeout waiting for read");
        std::thread::sleep(Duration::from_millis(5));
    }
}

struct RecordingSink(Mutex<Vec<(u32, u64, i64, i64)>>);

impl EventSink for RecordingSink {
    fn on_event(&self, kind: u32, object_id: u64, arg0: i64, arg1: i64) {
        self.0.lock().unwrap().push((kind, object_id, arg0, arg1));
    }
}

fn test_ctx() -> (Arc<NativeContext>, Arc<RecordingSink>) {
    let sink = Arc::new(RecordingSink(Mutex::new(Vec::new())));
    let ctx = NativeContext::new(2, Some(sink.clone())).expect("native context");
    (ctx, sink)
}

fn wait_accepted(sink: &RecordingSink, server: u64) -> u64 {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let hit = sink
            .0
            .lock()
            .unwrap()
            .iter()
            .find(|(k, o, _, _)| *k == NB_EVENT_ACCEPTED && *o == server)
            .map(|(_, _, a, _)| *a as u64);
        if let Some(conn) = hit {
            return conn;
        }
        assert!(Instant::now() < deadline, "timeout waiting for ACCEPTED");
        std::thread::sleep(Duration::from_millis(5));
    }
}

#[test]
fn kcp_loopback_roundtrip() {
    let (ctx, sink) = test_ctx();
    let server = ctx
        .start_server(TransportKind::Kcp, 0, 256, None, KcpProfile::Balanced)
        .expect("start kcp server");
    let port = ctx.server_port(server).expect("kcp server port");
    let client = ctx
        .connect(TransportKind::Kcp, "127.0.0.1", port, KcpProfile::Balanced)
        .expect("kcp connect");

    let payload: Vec<u8> = (0..8192u32).map(|i| (i % 251) as u8).collect();
    assert_eq!(
        ctx.write_chunk(client, Bytes::from(payload.clone()))
            .expect("client write"),
        payload.len(),
        "kcp client must accept writes while connecting"
    );

    let server_conn = wait_accepted(&sink, server);

    // client -> server
    let mut got = Vec::with_capacity(payload.len());
    while got.len() < payload.len() {
        got.extend_from_slice(&wait_read(&ctx, server_conn, 1));
    }
    assert_eq!(got, payload);

    // server -> client
    let reply = b"pong over kcp";
    assert_eq!(
        ctx.write_chunk(server_conn, Bytes::copy_from_slice(reply))
            .expect("server write"),
        reply.len()
    );
    assert_eq!(wait_read(&ctx, client, reply.len()), reply);
    wait_state(&ctx, client, STATE_CONNECTED);

    ctx.close_connection(client);
    let _ = ctx.stop_server(server);
}

#[test]
fn kcp_sustained_and_idle_phase() {
    let (ctx, sink) = test_ctx();
    let server = ctx
        .start_server(TransportKind::Kcp, 0, 256, None, KcpProfile::Balanced)
        .expect("start kcp server");
    let port = ctx.server_port(server).expect("kcp server port");
    let client = ctx
        .connect(TransportKind::Kcp, "127.0.0.1", port, KcpProfile::Balanced)
        .expect("kcp connect");

    let payload: Vec<u8> = (0..8192u32).map(|i| (i % 251) as u8).collect();
    assert_eq!(
        ctx.write_chunk(client, Bytes::from(payload.clone()))
            .expect("client write"),
        payload.len()
    );
    let server_conn = wait_accepted(&sink, server);

    let mut round = 0u32;
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(15) {
        std::thread::sleep(Duration::from_millis(1000));
        round += 1;
        let msg = format!("active-{round}").into_bytes();
        assert_eq!(
            ctx.write_chunk(client, Bytes::copy_from_slice(&msg))
                .expect("cli write"),
            msg.len()
        );
        wait_read(&ctx, server_conn, msg.len());
        assert_eq!(
            ctx.write_chunk(server_conn, Bytes::copy_from_slice(&msg))
                .expect("srv write"),
            msg.len()
        );
        wait_read(&ctx, client, msg.len());
        assert_eq!(
            ctx.connection_state(client),
            Some(STATE_CONNECTED),
            "client died at round {round}"
        );
        assert_eq!(
            ctx.connection_state(server_conn),
            Some(STATE_CONNECTED),
            "server died at round {round}"
        );
    }

    std::thread::sleep(Duration::from_secs(45));
    assert_eq!(
        ctx.connection_state(client),
        Some(STATE_CONNECTED),
        "client died during idle phase"
    );
    assert_eq!(
        ctx.connection_state(server_conn),
        Some(STATE_CONNECTED),
        "server died during idle phase"
    );

    let msg = b"resume-after-idle";
    assert_eq!(
        ctx.write_chunk(client, Bytes::copy_from_slice(msg))
            .expect("cli resume"),
        msg.len()
    );
    wait_read(&ctx, server_conn, msg.len());
    assert_eq!(
        ctx.write_chunk(server_conn, Bytes::copy_from_slice(msg))
            .expect("srv resume"),
        msg.len()
    );
    wait_read(&ctx, client, msg.len());

    ctx.close_connection(client);
    let _ = ctx.stop_server(server);
}

#[test]
fn kcp_peer_close_propagates_to_client() {
    eprintln!("KCP TEST START");
    let (ctx, sink) = test_ctx();
    eprintln!("KCP CTX READY");
    let server = ctx
        .start_server(TransportKind::Kcp, 0, 256, None, KcpProfile::Balanced)
        .expect("start kcp server");
    eprintln!("KCP SERVER STARTED: {server}");
    let port = ctx.server_port(server).expect("kcp server port");
    eprintln!("KCP PORT: {port}");
    let client = ctx
        .connect(TransportKind::Kcp, "127.0.0.1", port, KcpProfile::Balanced)
        .expect("kcp connect");
    eprintln!("KCP CLIENT CONNECT: {client}");

    assert_eq!(
        ctx.write_chunk(client, Bytes::copy_from_slice(b"warm-up"))
            .expect("warm-up write"),
        7
    );
    eprintln!("KCP WARMUP WRITTEN");

    wait_state(&ctx, client, STATE_CONNECTED);
    eprintln!("KCP CLIENT STATE CONNECTED");
    let server_conn = wait_accepted(&sink, server);
    eprintln!("KCP SERVER ACCEPTED: {server_conn}");
    wait_state(&ctx, server_conn, STATE_CONNECTED);
    eprintln!("KCP SERVER STATE CONNECTED");

    assert!(ctx.close_connection(server_conn));
    eprintln!("KCP SERVER CONN CLOSED");
    wait_disconnected(&ctx, client);
    eprintln!("KCP CLIENT DISCONNECTED");

    ctx.close_connection(client);
    eprintln!("KCP TEST END");
}

#[test]
fn kcp_server_stop_does_not_kill_adopted_connections() {
    let (ctx, sink) = test_ctx();
    let server = ctx
        .start_server(TransportKind::Kcp, 0, 256, None, KcpProfile::Balanced)
        .expect("start kcp server");
    let port = ctx.server_port(server).expect("kcp server port");
    let client = ctx
        .connect(TransportKind::Kcp, "127.0.0.1", port, KcpProfile::Balanced)
        .expect("kcp connect");

    assert_eq!(
        ctx.write_chunk(client, Bytes::copy_from_slice(b"warm-up"))
            .expect("warm-up write"),
        7
    );

    let server_conn = wait_accepted(&sink, server);
    wait_state(&ctx, server_conn, STATE_CONNECTED);
    assert_eq!(wait_read(&ctx, server_conn, 7), b"warm-up");

    // Stop server
    assert!(ctx.stop_server(server).is_ok(), "stop server must succeed");

    // Established connections already owned by Java must remain alive and support normal I/O
    let payload = b"kcp data after server stopped";
    assert_eq!(
        ctx.write_chunk(client, Bytes::copy_from_slice(payload))
            .expect("client write"),
        payload.len()
    );
    assert_eq!(wait_read(&ctx, server_conn, payload.len()), payload);

    let reply = b"kcp server reply after server stopped";
    assert_eq!(
        ctx.write_chunk(server_conn, Bytes::copy_from_slice(reply))
            .expect("server write"),
        reply.len()
    );
    assert_eq!(wait_read(&ctx, client, reply.len()), reply);

    // Clean up the connection
    ctx.close_connection(client);
    ctx.close_connection(server_conn);
}
