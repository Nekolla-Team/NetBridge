package team.nekolla.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import team.nekolla.netbridge.config.SharedIoMode;
import team.nekolla.netbridge.nativebridge.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-language integration tests for the Phase 4 Java direct shared data plane: both transports,
 * the {@code off} rollback path, configured capacities, and close-versus-IO stress.
 */
class FfmSharedDirectDataPlaneTest {

    private static Path nativeLibPath;

    @BeforeAll
    static void setUp() {
        nativeLibPath = FfmTestSupport.requireNativeLibraryOrSkip();
    }

    @Test
    void quicDirectDataPlaneRoundTrip() throws Exception {
        runDirectRoundTrip(NativeTransportKind.QUIC, 0, 0);
    }

    private void runDirectRoundTrip(
            NativeTransportKind kind,
            int txCapacity,
            int rxCapacity
    ) throws Exception {
        try (
                var backend = FfmNativeTransportBackend.load(
                        nativeLibPath,
                        2,
                        SharedIoMode.ON,
                        txCapacity,
                        rxCapacity
                )
        ) {
            var server = backend.startServer(switch (kind) {
                case QUIC -> NativeServerRequest.quic(0, 16);
                case KCP -> NativeServerRequest.kcp(0, 16, NativeKcpProfile.BALANCED);
            });
            var acceptedRef = new AtomicReference<NativeConnection>();
            var accepted = new CountDownLatch(1);
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(@NonNull NativeConnection connection) {
                    acceptedRef.set(connection);
                    accepted.countDown();
                }
            });

            var client = backend.connect(switch (kind) {
                case QUIC -> NativeConnectRequest.quic("127.0.0.1", server.localPort());
                case KCP -> NativeConnectRequest.kcp(
                        "127.0.0.1",
                        server.localPort(),
                        NativeKcpProfile.BALANCED
                );
            });
            awaitConnected(client, "client");
            assertTrue(accepted.await(10, TimeUnit.SECONDS), "server must accept");
            var serverConn = acceptedRef.get();
            awaitConnected(serverConn, "server");

            var message = ("direct-" + kind).getBytes(StandardCharsets.UTF_8);
            var write = client.write(ByteBuffer.wrap(message));
            assertEquals(NativeIoResult.progressed(message.length), write);

            var directClient = (FfmNativeConnection) client;
            assertTrue(directClient.directDataPlaneActive(), "ON mode must map the direct plane");
            var region = directClient.directRegion();
            assertNotNull(region);
            assertEquals(
                    txCapacity == 0
                            ? 128 * 1024
                            : txCapacity,
                    region.txCapacity()
            );
            assertEquals(
                    rxCapacity == 0
                            ? 128 * 1024
                            : rxCapacity,
                    region.rxCapacity()
            );

            var received = readFully(serverConn, message.length);
            assertArrayEquals(message, received);
            assertTrue(
                    ((FfmNativeConnection) serverConn).directDataPlaneActive(),
                    "the accepted side must also use the direct plane"
            );

            // Echo back to exercise the reverse ring direction.
            var echo = serverConn.write(ByteBuffer.wrap(message));
            assertEquals(NativeIoResult.progressed(message.length), echo);
            assertArrayEquals(message, readFully(client, message.length));

            client.close();
            serverConn.close();
            server.close();
        }
    }

    private static void awaitConnected(
            NativeConnection connection,
            String label
    ) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000;
        while (connection.state() != NativeConnectionState.CONNECTED
                && System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10);
        }
        assertEquals(
                NativeConnectionState.CONNECTED,
                connection.state(),
                label + " never reached CONNECTED"
        );
    }

    private static byte[] readFully(
            NativeConnection connection,
            int expected
    ) throws InterruptedException {
        var buffer = ByteBuffer.allocate(Math.max(expected, 1));
        var collected = new byte[expected];
        var offset = 0;
        var deadline = System.currentTimeMillis() + 10_000;
        while (offset < expected
                && System.currentTimeMillis() < deadline
        ) {
            buffer.clear();
            buffer.limit(expected - offset);
            var result = connection.read(buffer);
            if (result.progressed() && result.bytes() > 0) {
                buffer.flip();
                buffer.get(collected, offset, result.bytes());
                offset += result.bytes();
            } else {
                Thread.sleep(2);
            }
        }
        assertEquals(expected, offset, "read timed out");
        return collected;
    }

    @Test
    void kcpDirectDataPlaneRoundTrip() throws Exception {
        runDirectRoundTrip(NativeTransportKind.KCP, 0, 0);
    }

    @Test
    void configuredCapacitiesAreHonored() throws Exception {
        runDirectRoundTrip(NativeTransportKind.QUIC, 64 * 1024, 256 * 1024);
    }

    @Test
    void offModeKeepsTheLegacyDataPlane() throws Exception {
        try (
                var backend = FfmNativeTransportBackend.load(
                        nativeLibPath,
                        2,
                        SharedIoMode.OFF,
                        0,
                        0
                )
        ) {
            var server = backend.startServer(NativeServerRequest.quic(0, 16));
            var acceptedRef = new AtomicReference<NativeConnection>();
            var accepted = new CountDownLatch(1);
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(@NonNull NativeConnection connection) {
                    acceptedRef.set(connection);
                    accepted.countDown();
                }
            });

            var client = backend.connect(
                    NativeConnectRequest.quic("127.0.0.1", server.localPort())
            );
            awaitConnected(client, "client");
            assertTrue(accepted.await(10, TimeUnit.SECONDS));
            var serverConn = acceptedRef.get();
            awaitConnected(serverConn, "server");

            var message = "legacy-path".getBytes(StandardCharsets.UTF_8);
            assertEquals(
                    NativeIoResult.progressed(message.length),
                    client.write(ByteBuffer.wrap(message))
            );
            assertFalse(
                    ((FfmNativeConnection) client).directDataPlaneActive(),
                    "OFF mode must not map the direct plane"
            );
            assertArrayEquals(message, readFully(serverConn, message.length));

            client.close();
            serverConn.close();
            server.close();
        }
    }

    @Test
    void closeVersusDirectIoStressDoesNotCorruptOrCrash() throws Exception {
        var backend = FfmNativeTransportBackend.load(
                nativeLibPath,
                2,
                SharedIoMode.ON,
                0,
                0
        );
        var server = backend.startServer(NativeServerRequest.quic(0, 32));
        var client = backend.connect(
                NativeConnectRequest.quic("127.0.0.1", server.localPort())
        );
        awaitConnected(client, "client");
        // Force the direct plane to be mapped before the race begins.
        client.write(ByteBuffer.wrap(new byte[1]));

        var stop = new AtomicBoolean(false);
        var failure = new AtomicReference<@Nullable Throwable>();
        var threads = new Thread[4];
        IntStream.range(0, threads.length)
                .forEach(t -> {
                    var writer = t % 2 == 0;
                    threads[t] = Thread.ofPlatform()
                            .start(() -> {
                                var scratch = ByteBuffer.allocate(64);
                                while (!stop.get()) {
                                    try {
                                        scratch.clear();
                                        if (writer) {
                                            client.write(scratch);
                                        } else {
                                            client.read(scratch);
                                        }
                                    } catch (NativeException _) {
                                        // expected while the backend is closing
                                    } catch (Throwable unexpected) {
                                        failure.compareAndSet(null, unexpected);
                                        return;
                                    }
                                }
                            });
                });

        Thread.sleep(50);
        var closer = Thread.ofPlatform()
                .start(() -> {
                    try {
                        client.close();
                        backend.close();
                    } catch (Throwable unexpected) {
                        failure.compareAndSet(null, unexpected);
                    }
                });

        closer.join(20_000);
        stop.set(true);
        for (var thread : threads) {
            thread.join(10_000);
        }

        assertFalse(closer.isAlive(), "closer did not finish");
        if (failure.get() != null) {
            fail("unexpected failure during close-vs-IO", failure.get());
        }
        assertEquals(NativeBackendState.CLOSED, backend.availability().state());
        server.close();
    }

}
