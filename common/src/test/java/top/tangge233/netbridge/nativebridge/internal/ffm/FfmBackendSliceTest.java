package top.tangge233.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.nativebridge.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;

import static org.junit.jupiter.api.Assertions.*;

class FfmBackendSliceTest {

    private static Path nativeLibPath;

    @BeforeAll
    static void setUp() {
        nativeLibPath = FfmTestSupport.requireNativeLibraryOrSkip();
    }

    @Test
    void quicLoopbackAcceptDataAndClose() throws Exception {
        runSlice(NativeTransportKind.QUIC);
    }

    private void runSlice(
            NativeTransportKind kind
    ) throws Exception {
        try (
                var backend = FfmNativeTransportBackend.load(
                        nativeLibPath,
                        2
                )
        ) {
            assertTrue(backend.availability().available());

            var server = backend.startServer(
                    switch (kind) {
                        case QUIC -> NativeServerRequest.quic(0, 64);
                        case KCP -> NativeServerRequest.kcp(
                                0,
                                64,
                                NativeKcpProfile.BALANCED
                        );
                    }
            );
            assertEquals(kind, server.transport());
            assertTrue(server.localPort() > 0);

            var acceptedLatch = new CountDownLatch(1);
            var acceptedRef = new AtomicReference<NativeConnection>();
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(@NonNull NativeConnection connection) {
                    acceptedRef.set(connection);
                    acceptedLatch.countDown();
                }
            });

            var client = backend.connect(
                    switch (kind) {
                        case QUIC -> NativeConnectRequest.quic(
                                "127.0.0.1",
                                server.localPort()
                        );
                        case KCP -> NativeConnectRequest.kcp(
                                "127.0.0.1",
                                server.localPort(),
                                NativeKcpProfile.BALANCED
                        );
                    }
            );
            assertEquals(kind, client.transport());

            var connectedLatch = new CountDownLatch(1);
            client.setListener(new NativeConnectionListener() {
                @Override
                public void onStateChanged(@NonNull NativeConnectionState state) {
                    if (state == NativeConnectionState.CONNECTED) {
                        connectedLatch.countDown();
                    }
                }
            });

            assertTrue(
                    connectedLatch.await(10, TimeUnit.SECONDS),
                    "client should reach CONNECTED via event"
            );
            assertEquals(
                    NativeConnectionState.CONNECTED,
                    client.state()
            );

            assertTrue(
                    acceptedLatch.await(10, TimeUnit.SECONDS),
                    "server should receive ACCEPTED event"
            );
            var serverConn = acceptedRef.get();
            assertNotNull(serverConn);
            assertEquals(
                    NativeConnectionState.CONNECTED,
                    serverConn.state()
            );

            var dataAvailable = new CountDownLatch(1);
            serverConn.setListener(new NativeConnectionListener() {
                @Override
                public void onDataAvailable() {
                    dataAvailable.countDown();
                }
            });

            var msg = ("hello via " + kind).getBytes(StandardCharsets.UTF_8);
            var writeRes = client.write(ByteBuffer.wrap(msg));
            assertEquals(NativeIoResult.progressed(msg.length), writeRes);
            assertTrue(
                    dataAvailable.await(10, TimeUnit.SECONDS),
                    "server-side DATA_AVAILABLE event expected"
            );

            var readBuf = ByteBuffer.allocate(65536);
            var deadline = Instant.now().plus(Duration.ofSeconds(5));
            NativeIoResult readRes = null;
            while (Instant.now().isBefore(deadline)) {
                readRes = serverConn.read(readBuf);
                if (readRes.progressed() && readRes.bytes() > 0) {
                    break;
                }
                Thread.sleep(5);
            }
            assertNotNull(readRes);
            assertTrue(readRes.progressed());
            assertEquals(msg.length, readRes.bytes());
            readBuf.flip();
            var got = new byte[readRes.bytes()];
            readBuf.get(got);
            assertArrayEquals(msg, got);

            var echoRes = serverConn.write(ByteBuffer.wrap(msg));
            assertEquals(NativeIoResult.progressed(msg.length), echoRes);

            var clientReadBuf = ByteBuffer.allocate(65536);
            var cDeadline = Instant.now().plus(Duration.ofSeconds(5));
            NativeIoResult clientRead = null;
            while (Instant.now().isBefore(cDeadline)) {
                clientRead = client.read(clientReadBuf);
                if (clientRead.progressed() && clientRead.bytes() > 0) {
                    break;
                }
                Thread.sleep(5);
            }
            assertNotNull(clientRead);
            assertEquals(msg.length, clientRead.bytes());
            clientReadBuf.flip();
            var echoGot = new byte[clientRead.bytes()];
            clientReadBuf.get(echoGot);
            assertArrayEquals(msg, echoGot);

            server.close();
            client.close();
            serverConn.close();
        }
    }

    @Test
    void kcpLoopbackAcceptDataAndClose() throws Exception {
        runSlice(NativeTransportKind.KCP);
    }

    @Test
    void backendCloseShutsContextDown() {
        var backend = FfmNativeTransportBackend.load(
                nativeLibPath,
                1
        );
        assertTrue(backend.availability().available());
        var server = backend.startServer(NativeServerRequest.quic(
                0,
                8
        ));
        assertTrue(server.localPort() > 0);

        backend.close();
        assertEquals(
                NativeBackendState.CLOSED,
                backend.availability().state()
        );
        assertThrows(
                NativeException.class,
                () -> backend.connect(NativeConnectRequest.quic("127.0.0.1", 1))
        );
    }

}
