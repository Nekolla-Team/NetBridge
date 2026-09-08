package top.tangge233.netbridge.nativebridge.fake;

import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.nativebridge.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.*;

class FakeNativeTransportBackendTest {

    @Test
    void connectDeliversAcceptedAndConnected() throws Exception {
        try (var backend = new FakeNativeTransportBackend()) {
            var server = backend.startServer(NativeServerRequest.quic(
                    25565,
                    64
            ));
            assertEquals(
                    25565,
                    server.localPort()
            );

            var acceptedLatch = new CountDownLatch(1);
            var acceptedRef = new AtomicReference<@Nullable NativeConnection>();
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(NativeConnection connection) {
                    acceptedRef.set(connection);
                    acceptedLatch.countDown();
                }
            });

            var client = backend.connect(NativeConnectRequest.quic(
                    "127.0.0.1",
                    25565
            ));
            assertEquals(
                    NativeConnectionState.CONNECTED,
                    client.state(),
                    "fake handshake completes synchronously"
            );

            var connectedLatch = new CountDownLatch(1);
            client.setListener(new NativeConnectionListener() {
                @Override
                public void onStateChanged(NativeConnectionState state) {
                    if (state == NativeConnectionState.CONNECTED) {
                        connectedLatch.countDown();
                    }
                }
            });
            assertTrue(
                    connectedLatch.await(1, TimeUnit.SECONDS),
                    "listener attach reconcile should report CONNECTED"
            );
            assertTrue(
                    acceptedLatch.await(1, TimeUnit.SECONDS),
                    "server should receive accepted connection"
            );
            var accepted = Objects.requireNonNull(acceptedRef.get());
            assertEquals(
                    NativeConnectionState.CONNECTED,
                    accepted.state()
            );
        }
    }

    @Test
    void writeReadEcho() throws Exception {
        try (var backend = new FakeNativeTransportBackend()) {
            var server = backend.startServer(NativeServerRequest.quic(
                    25566,
                    64
            ));
            var acceptedLatch = new CountDownLatch(1);
            var acceptedRef = new AtomicReference<@Nullable NativeConnection>();
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(NativeConnection connection) {
                    acceptedRef.set(connection);
                    acceptedLatch.countDown();
                }
            });
            var client = backend.connect(NativeConnectRequest.quic(
                    "127.0.0.1",
                    25566
            ));
            client.setListener(new NativeConnectionListener() {
            });
            assertTrue(acceptedLatch.await(1, TimeUnit.SECONDS));
            var serverConn = Objects.requireNonNull(acceptedRef.get());

            var dataAvailable = new CountDownLatch(1);
            serverConn.setListener(new NativeConnectionListener() {
                @Override
                public void onDataAvailable() {
                    dataAvailable.countDown();
                }
            });

            var msg = "fake roundtrip payload".getBytes(StandardCharsets.UTF_8);
            var write = client.write(ByteBuffer.wrap(msg));
            assertEquals(
                    NativeIoResult.progressed(msg.length),
                    write
            );
            assertTrue(
                    dataAvailable.await(1, TimeUnit.SECONDS),
                    "DATA_AVAILABLE event expected"
            );

            var readBuf = ByteBuffer.allocate(1024);
            var read = serverConn.read(readBuf);
            assertTrue(read.progressed());
            assertEquals(msg.length, read.bytes());
            readBuf.flip();
            var got = new byte[read.bytes()];
            readBuf.get(got);
            assertArrayEquals(msg, got);
        }
    }

    @Test
    void wouldBlockThenWritableAfterPeerDrain() throws Exception {
        try (var backend = new FakeNativeTransportBackend()) {
            var server = backend.startServer(NativeServerRequest.quic(
                    25567,
                    64
            ));
            var acceptedLatch = new CountDownLatch(1);
            var acceptedRef = new AtomicReference<@Nullable NativeConnection>();
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(NativeConnection connection) {
                    acceptedRef.set(connection);
                    acceptedLatch.countDown();
                }
            });
            var client = backend.connect(NativeConnectRequest.quic(
                    "127.0.0.1",
                    25567
            ));
            client.setListener(new NativeConnectionListener() {
            });
            assertTrue(acceptedLatch.await(1, TimeUnit.SECONDS));
            var serverConn = Objects.requireNonNull(acceptedRef.get());

            var fakeClient = (FakeNativeConnection) client;
            fakeClient.setOutboundCapacity(8);
            var small = client.write(ByteBuffer.wrap(new byte[6]));
            assertEquals(NativeIoResult.progressed(6), small);

            var big = new byte[100];
            var src = ByteBuffer.wrap(big);
            var first = client.write(src);
            assertEquals(NativeIoResult.WOULD_BLOCK, first);
            assertEquals(
                    0,
                    src.position(),
                    "WOULD_BLOCK must not consume input"
            );
            assertTrue(fakeClient.writerBlocked());

            var writableLatch = new CountDownLatch(1);
            client.setListener(new NativeConnectionListener() {
                @Override
                public void onWritable() {
                    writableLatch.countDown();
                }
            });
            var drain = ByteBuffer.allocate(1024);
            var drained = serverConn.read(drain);
            assertTrue(drained.progressed());
            assertTrue(
                    writableLatch.await(1, TimeUnit.SECONDS),
                    "WRITABLE event expected after peer drains"
            );

            fakeClient.setOutboundCapacity(1024);
            var second = client.write(ByteBuffer.wrap(big));
            assertEquals(NativeIoResult.progressed(big.length), second);
        }
    }

    @Test
    void serverStopDoesNotKillAdoptedConnections() throws Exception {
        try (var backend = new FakeNativeTransportBackend()) {
            var server = backend.startServer(
                    NativeServerRequest.quic(25569, 64)
            );
            var acceptedLatch = new CountDownLatch(1);
            var acceptedRef = new AtomicReference<@Nullable NativeConnection>();
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(NativeConnection connection) {
                    acceptedRef.set(connection);
                    acceptedLatch.countDown();
                }
            });
            var client = backend.connect(
                    NativeConnectRequest.quic("127.0.0.1", 25569)
            );
            assertTrue(acceptedLatch.await(1, TimeUnit.SECONDS));
            var serverConn = Objects.requireNonNull(acceptedRef.get());

            // Stop server
            server.close();

            // Existing connection remains alive and can do roundtrip I/O
            var dataAvailable = new CountDownLatch(1);
            serverConn.setListener(new NativeConnectionListener() {
                @Override
                public void onDataAvailable() {
                    dataAvailable.countDown();
                }
            });
            var msg = "alive after server stop".getBytes(StandardCharsets.UTF_8);
            var write = client.write(ByteBuffer.wrap(msg));
            assertEquals(NativeIoResult.progressed(msg.length), write);
            assertTrue(dataAvailable.await(1, TimeUnit.SECONDS));

            var readBuf = ByteBuffer.allocate(1024);
            var read = serverConn.read(readBuf);
            assertEquals(msg.length, read.bytes());

            // New connections are rejected
            assertThrows(
                    NativeException.class,
                    () -> backend.connect(NativeConnectRequest.quic("127.0.0.1", 25569))
            );
        }
    }

    @Test
    void backendCloseClosesEverything() {
        var backend = new FakeNativeTransportBackend();
        var closed = new CountDownLatch(1);
        backend.addCloseHook(closed::countDown);
        backend.startServer(NativeServerRequest.quic(25568, 64));
        backend.connect(NativeConnectRequest.quic("127.0.0.1", 25568));

        backend.close();
        assertEquals(
                0,
                closed.getCount(),
                "close hooks should run"
        );
        assertThrows(
                NativeException.class,
                () -> backend.connect(NativeConnectRequest.quic("127.0.0.1", 25568))
        );
    }

}
