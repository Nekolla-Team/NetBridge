//! QUIC facade integration tests: real QUIC round trips, close propagation, and native quinn loopback.

use bytes::Bytes;
use std::time::{Duration, Instant};

use crate::context::NativeContext;
use crate::event::{EventSink, NB_EVENT_ACCEPTED};
use crate::transport::TransportKind;
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

fn wait_terminal(ctx: &NativeContext, conn: u64) {
    match ctx.connection_state(conn) {
        Some(STATE_CLOSED) | None => {}
        other => panic!("unexpected terminal state {other:?}"),
    }
}

fn wait_read(ctx: &NativeContext, conn: u64, want: usize) -> Vec<u8> {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let res = ctx.read_chunk(conn, 65536);
        if let Ok(data) = res
            && !data.is_empty()
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
fn quic_loopback_roundtrip() {
    let (ctx, sink) = test_ctx();
    let server = ctx
        .start_server(TransportKind::Quic, 0, 256, None, Default::default())
        .expect("start server");
    let port = ctx.server_port(server).expect("server port");
    let client = ctx
        .connect(TransportKind::Quic, "127.0.0.1", port, Default::default())
        .expect("connect");
    let payload = b"net-bridge hello over bridge";
    wait_state(&ctx, client, STATE_CONNECTED);
    let server_conn = wait_accepted(&sink, server);
    wait_state(&ctx, server_conn, STATE_CONNECTED);
    assert_eq!(
        ctx.write_chunk(client, Bytes::copy_from_slice(payload))
            .expect("client write"),
        payload.len()
    );
    assert_eq!(ctx.connection_state(server_conn), Some(STATE_CONNECTED));
    assert_eq!(wait_read(&ctx, server_conn, payload.len()), payload);

    // server -> client
    let reply = b"pong from server";
    assert_eq!(
        ctx.write_chunk(server_conn, Bytes::copy_from_slice(reply))
            .expect("server write"),
        reply.len()
    );
    assert_eq!(wait_read(&ctx, client, reply.len()), reply);

    ctx.close_connection(client);
    let _ = ctx.stop_server(server);
    wait_terminal(&ctx, client);
}

#[tokio::test]
async fn quic_server_stop_does_not_kill_adopted_connections() {
    let (ctx, sink) = test_ctx();
    let server = ctx
        .start_server(TransportKind::Quic, 0, 256, None, Default::default())
        .expect("start server");
    let port = ctx.server_port(server).expect("server port");
    let client = ctx
        .connect(TransportKind::Quic, "127.0.0.1", port, Default::default())
        .expect("connect");
    wait_state(&ctx, client, STATE_CONNECTED);
    let server_conn = wait_accepted(&sink, server);
    wait_state(&ctx, server_conn, STATE_CONNECTED);

    // Stop server
    assert!(ctx.stop_server(server).is_ok(), "stop server must succeed");

    // Established connections already owned by Java must remain alive and support normal I/O
    let payload = b"data after server stopped";
    assert_eq!(
        ctx.write_chunk(client, Bytes::copy_from_slice(payload))
            .expect("client write"),
        payload.len()
    );
    assert_eq!(wait_read(&ctx, server_conn, payload.len()), payload);

    let reply = b"server reply after server stopped";
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

#[tokio::test]
async fn quic_accepted_guarantees_stream_readiness_and_no_accepted_on_stream_failure() {
    use quinn::Endpoint;

    let (ctx, sink) = test_ctx();
    let server = ctx
        .start_server(TransportKind::Quic, 0, 256, None, Default::default())
        .expect("start server");
    let port = ctx.server_port(server).expect("server port");

    // Client connects via raw quinn WITHOUT opening any bidi stream
    let mut client_endpoint =
        Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
    client_endpoint.set_default_client_config(quinn_plaintext::client_config());
    let conn = client_endpoint
        .connect(
            format!("127.0.0.1:{port}").parse().unwrap(),
            "plaintext.test",
        )
        .expect("connect")
        .await
        .expect("connected");

    // Wait some time; because no stream is opened, server MUST NOT emit ACCEPTED
    tokio::time::sleep(Duration::from_millis(200)).await;
    let accepted_events: Vec<_> = sink
        .0
        .lock()
        .unwrap()
        .iter()
        .filter(|(k, o, _, _)| *k == NB_EVENT_ACCEPTED && *o == server)
        .cloned()
        .collect();
    assert!(
        accepted_events.is_empty(),
        "The server must never emit ACCEPTED before the bidirectional stream is established!"
    );

    // Now client opens a bidi stream and sends a small message (probe + trigger)
    let (mut send, mut recv) = conn.open_bi().await.expect("open bi");
    send.write_all(b"\x00trigger").await.expect("write");

    // Now ACCEPTED must be emitted
    tokio::time::sleep(Duration::from_millis(100)).await;
    let server_conn = wait_accepted(&sink, server);
    assert_eq!(ctx.connection_state(server_conn), Some(STATE_CONNECTED));
    assert!(ctx.connection_remote_addr(server_conn).is_some());

    // Server reads the trigger data
    assert_eq!(wait_read(&ctx, server_conn, 7), b"trigger");

    // Server replies
    assert_eq!(
        ctx.write_chunk(server_conn, Bytes::copy_from_slice(b"ack"))
            .expect("server write"),
        3
    );
    let mut buf = [0u8; 3];
    recv.read_exact(&mut buf).await.expect("recv ack");
    assert_eq!(&buf, b"ack");

    conn.close(0u32.into(), b"done");
    ctx.close_connection(server_conn);
    let _ = ctx.stop_server(server);
}

#[test]
fn quic_peer_close_propagates_to_client() {
    let (ctx, sink) = test_ctx();
    let server = ctx
        .start_server(TransportKind::Quic, 0, 256, None, Default::default())
        .expect("start server");
    let port = ctx.server_port(server).expect("server port");
    let client = ctx
        .connect(TransportKind::Quic, "127.0.0.1", port, Default::default())
        .expect("connect");
    wait_state(&ctx, client, STATE_CONNECTED);

    let server_conn = wait_accepted(&sink, server);

    assert!(ctx.close_connection(server_conn));
    wait_disconnected(&ctx, client);
    wait_terminal(&ctx, client);
}

#[tokio::test]
async fn plaintext_bidi_echo() {
    use quinn::Endpoint;

    let server_config = quinn_plaintext::server_config();
    let server =
        Endpoint::server(server_config, "127.0.0.1:0".parse().unwrap()).expect("server endpoint");
    let server_addr = server.local_addr().expect("server local addr");

    let mut client = Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
    client.set_default_client_config(quinn_plaintext::client_config());

    let server_task = tokio::spawn(async move {
        let conn = server
            .accept()
            .await
            .expect("accept incoming")
            .await
            .expect("connect");
        let (mut send, mut recv) = conn.accept_bi().await.expect("accept bi");
        let msg = recv.read_to_end(1024).await.expect("read request");
        send.write_all(&msg).await.expect("echo write");
        send.finish().unwrap();
        let _ = conn.closed().await;
    });

    let conn = client
        .connect(server_addr, "plaintext.test")
        .expect("connect")
        .await
        .expect("connect await");

    let (mut send, mut recv) = conn.open_bi().await.expect("open bi");
    let request = b"net-bridge hello";
    send.write_all(request).await.expect("write");
    send.finish().unwrap();
    let response = recv.read_to_end(1024).await.expect("read response");

    assert_eq!(response, request, "echo mismatch");
    conn.close(0u32.into(), b"done");
    server_task.await.unwrap();
}
