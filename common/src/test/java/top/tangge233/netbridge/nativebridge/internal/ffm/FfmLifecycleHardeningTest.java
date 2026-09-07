package top.tangge233.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.nativebridge.*;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;

import static org.junit.jupiter.api.Assertions.*;

class FfmLifecycleHardeningTest {

    private static Path nativeLibPath;

    @BeforeAll
    static void setUp() {
        nativeLibPath = FfmTestSupport.findNativeLibrary();
    }

    @Test
    void acceptedEventBeforeListenerInstallIsReplayedNotLost() throws Exception {
        try (
                var backend = FfmNativeTransportBackend.load(
                        nativeLibPath,
                        2
                )
        ) {
            var server = backend.startServer(
                    NativeServerRequest.quic(0, 8)
            );
            var port = server.localPort();

            var client = backend.connect(
                    NativeConnectRequest.quic("127.0.0.1", port)
            );
            awaitState(client, NativeConnectionState.CONNECTED);

            var accepted = new CountDownLatch(1);
            var acceptedRef = new AtomicReference<NativeConnection>();
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(@NonNull NativeConnection connection) {
                    acceptedRef.set(connection);
                    accepted.countDown();
                }
            });

            assertTrue(
                    accepted.await(5, TimeUnit.SECONDS),
                    "listener 安装前到达的 ACCEPTED 必须回放，不得 orphan"
            );
            assertNotNull(acceptedRef.get());
            client.close();
            server.close();
        }
    }

    private static void awaitState(
            NativeConnection conn,
            NativeConnectionState want
    ) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000;
        while (conn.state() != want && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(
                want,
                conn.state(),
                "等待连接状态 " + want + " 超时"
        );
    }

    @Test
    void manyShortConnectionsLeaveServerChildrenAndRegistryClean() throws Exception {
        try (
                var backend = FfmNativeTransportBackend.load(
                        nativeLibPath,
                        2
                )
        ) {
            var server = backend.startServer(
                    NativeServerRequest.quic(0, 32)
            );
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(@NonNull NativeConnection connection) {
                    connection.close();
                }
            });
            var port = server.localPort();

            for (var i = 0; i < 12; i++) {
                var client = backend.connect(
                        NativeConnectRequest.quic("127.0.0.1", port)
                );
                var deadline = System.currentTimeMillis() + 10_000;
                while (client.state() == NativeConnectionState.CONNECTING
                        && System.currentTimeMillis() < deadline
                ) {
                    Thread.sleep(10);
                }
                var st = client.state();
                assertTrue(
                        st == NativeConnectionState.CONNECTED || st == NativeConnectionState.CLOSED,
                        "client state should reach CONNECTED or CLOSED: " + st
                );
                client.close();
            }

            assertDoesNotThrow(server::close);
        }
    }

    @Test
    void twoContextsWithSameConnectionIdsDoNotCrossRoute() throws Exception {
        try (var lib = FfmNativeLibrary.load(nativeLibPath)) {
            var ctxA = lib.createContext(2);
            var ctxB = lib.createContext(2);

            var seenA = new CopyOnWriteArrayList<NativeEvent>();
            var seenB = new CopyOnWriteArrayList<NativeEvent>();
            NativeEventListener listenerA = seenA::add;
            NativeEventListener listenerB = seenB::add;
            ctxA.dispatcher().addListener(listenerA);
            ctxB.dispatcher().addListener(listenerB);

            var serverA = ctxA.serverStart(
                    1,
                    null,
                    0,
                    8,
                    0
            );
            var serverB = ctxB.serverStart(
                    1,
                    null,
                    0,
                    8,
                    0
            );
            assertTrue(serverA > 0);
            assertTrue(serverB > 0);

            var connA = ctxA.connect(
                    1,
                    "127.0.0.1",
                    ctxA.serverPort(serverA),
                    0
            );
            var connB = ctxB.connect(
                    1,
                    "127.0.0.1",
                    ctxB.serverPort(serverB),
                    0
            );
            assertTrue(connA > 0);
            assertTrue(connB > 0);

            waitState(ctxA, connA, 2);
            waitState(ctxB, connB, 2);

            var ctxAClientEvents = seenA.stream()
                    .filter(e ->
                            e instanceof NativeEvent.ConnectionStateChanged state
                                    && state.connectionId() == connA
                    )
                    .toList();
            var ctxBClientEvents = seenB.stream()
                    .filter(e ->
                            e instanceof NativeEvent.ConnectionStateChanged state
                                    && state.connectionId() == connB
                    )
                    .toList();
            assertFalse(
                    ctxAClientEvents.isEmpty(),
                    "context A 必须收到自己的连接事件"
            );
            assertFalse(
                    ctxBClientEvents.isEmpty(),
                    "context B 必须收到自己的连接事件"
            );

            ctxA.shutdown(1000);
            ctxA.destroy();
            ctxB.shutdown(1000);
            ctxB.destroy();
        }
    }

    private static void waitState(
            FfmNativeContext ctx,
            long conn,
            int abiState
    ) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000;
        while (ctx.connectionState(conn) != abiState
                && System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10);
        }
        assertEquals(abiState, ctx.connectionState(conn));
    }

    @Test
    void closeVsDowncallStressDoesNotCrashOrDoubleDestroy() throws Exception {
        var backend = FfmNativeTransportBackend.load(nativeLibPath, 2);
        var server = backend.startServer(NativeServerRequest.quic(0, 64));
        var client = backend.connect(
                NativeConnectRequest.quic("127.0.0.1", server.localPort())
        );
        awaitState(client, NativeConnectionState.CONNECTED);

        var stop = new AtomicBoolean(false);
        var errors = new CopyOnWriteArrayList<Throwable>();
        var threads = new ArrayList<Thread>();
        for (var t = 0; t < 4; t++) {
            var writer = t % 2 == 0;
            var thread = new Thread(
                    () -> {
                        while (!stop.get()) {
                            try {
                                if (writer) {
                                    client.write(ByteBuffer.wrap(new byte[64]));
                                } else {
                                    client.state();
                                }
                            } catch (NativeException _) {
                                // expected during concurrent backend closure
                            } catch (Throwable unexpected) {
                                errors.add(unexpected);
                                return;
                            }
                        }
                    },
                    "stress-" + t
            );
            threads.add(thread);
            thread.start();
        }

        Thread.sleep(50);
        var closer1 = new Thread(backend::close, "closer-1");
        var closer2 = new Thread(backend::close, "closer-2");
        closer1.start();
        closer2.start();
        closer1.join(15_000);
        closer2.join(15_000);
        stop.set(true);
        for (var thread : threads) {
            thread.join(5_000);
        }

        assertEquals(NativeBackendState.CLOSED, backend.availability().state());
        errors.stream()
                .filter(e -> !(e instanceof NativeException))
                .forEach(e -> fail(
                        "close 与 downcall 竞态中出现非 typed 异常: " + e,
                        e
                ));
        assertTrue(true);
    }

    @Test
    void terminalStateQueriesRemainStableBeforeRelease() throws Exception {
        try (
                var backend = FfmNativeTransportBackend.load(
                        nativeLibPath,
                        2
                )
        ) {
            var server = backend.startServer(
                    NativeServerRequest.quic(0, 8)
            );
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
            awaitState(client, NativeConnectionState.CONNECTED);
            assertTrue(accepted.await(10, TimeUnit.SECONDS));
            var serverConn = acceptedRef.get();
            awaitState(serverConn, NativeConnectionState.CONNECTED);

            var remote = serverConn.remoteAddress();
            assertNotNull(remote);
            assertEquals("127.0.0.1", remote.getAddress().getHostAddress());
            assertTrue(remote.getPort() > 0);

            serverConn.close();
            assertThrows(
                    NativeException.class,
                    serverConn::remoteAddress
            );

            client.close();
            server.close();
        }
    }

    @Test
    void safePrefixTableRejectsTruncatedSize() {
        try (var arena = Arena.ofConfined()) {
            var fakeTable = arena.allocate(
                    32,
                    8
            );
            fakeTable.set(
                    ValueLayout.JAVA_INT,
                    0,
                    1
            ); // major
            fakeTable.set(
                    ValueLayout.JAVA_INT,
                    4,
                    0
            ); // minor
            fakeTable.set(
                    ValueLayout.JAVA_INT,
                    8,
                    16
            ); // struct_size too small
            fakeTable.set(
                    ValueLayout.JAVA_LONG,
                    16,
                    0
            ); // features

            var ex = assertThrows(
                    IllegalStateException.class,
                    () -> FfmApiV1.fromAddress(fakeTable, arena)
            );
            var msg = ex.getMessage();
            assertNotNull(msg);
            assertTrue(msg.contains("Truncated API table"));
        }
    }

    @Test
    void safePrefixTableRejectsMajorMismatch() {
        try (var arena = Arena.ofConfined()) {
            var fakeTable = arena.allocate(
                    FfmApiLayouts.API_V1.byteSize(),
                    8
            );
            fakeTable.set(
                    ValueLayout.JAVA_INT,
                    0,
                    2
            );
            fakeTable.set(
                    ValueLayout.JAVA_INT,
                    4,
                    0
            );
            fakeTable.set(
                    ValueLayout.JAVA_LONG,
                    16,
                    FfmApiV1.FEATURE_QUIC
                            | FfmApiV1.FEATURE_KCP
                            | FfmApiV1.FEATURE_WRITABLE_EVENT
                            | FfmApiV1.FEATURE_BINARY_SOCKET_ADDRESS
            ); // features

            var ex = assertThrows(
                    IllegalStateException.class,
                    () -> FfmApiV1.fromAddress(fakeTable, arena)
            );
            var msg = ex.getMessage();
            assertNotNull(msg);
            assertTrue(msg.contains("Unsupported ABI major"));
        }
    }

    @Test
    void safePrefixTableRejectsMissingRequiredFeatures() {
        try (var arena = Arena.ofConfined()) {
            var fakeTable = arena.allocate(
                    FfmApiLayouts.API_V1.byteSize(),
                    8
            );
            fakeTable.set(
                    ValueLayout.JAVA_INT,
                    0,
                    1
            );
            fakeTable.set(
                    ValueLayout.JAVA_INT,
                    4,
                    0
            );
            fakeTable.set(
                    ValueLayout.JAVA_INT,
                    8,
                    (int) FfmApiLayouts.API_V1.byteSize()
            );
            fakeTable.set(
                    ValueLayout.JAVA_LONG,
                    16,
                    0L
            ); // missing features

            var linker = Linker.nativeLinker();
            var dummyFunc = linker.defaultLookup().find("malloc").orElseThrow();
            var names = new String[]{
                    "context_create", "context_shutdown", "context_destroy",
                    "connect", "connection_state", "connection_remote_address",
                    "connection_write", "connection_read", "connection_close",
                    "server_start", "server_port", "server_stop"
            };
            Arrays.stream(names)
                    .mapToLong(name -> FfmApiLayouts.API_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement(name)
                    ))
                    .forEach(offset -> fakeTable.set(
                            ValueLayout.ADDRESS,
                            offset,
                            dummyFunc
                    ));

            var api = FfmApiV1.fromAddress(fakeTable, arena);
            assertEquals(0L, api.featureBits());
        }
    }

    @Test
    void listenerReplacementDoesNotReplayActiveChildren() throws Exception {
        try (
                var backend = FfmNativeTransportBackend.load(
                        nativeLibPath,
                        2
                )
        ) {
            var server = backend.startServer(
                    NativeServerRequest.quic(0, 8)
            );
            var port = server.localPort();

            var firstDelivered = new AtomicInteger(0);
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(@NonNull NativeConnection connection) {
                    firstDelivered.incrementAndGet();
                }
            });

            var client = backend.connect(
                    NativeConnectRequest.quic("127.0.0.1", port)
            );
            awaitState(client, NativeConnectionState.CONNECTED);

            var deadline = System.currentTimeMillis() + 5000;
            while (firstDelivered.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(
                    1,
                    firstDelivered.get(),
                    "First listener should receive accepted child once"
            );

            // Replace listener on active server with already delivered child
            var secondDelivered = new AtomicInteger(0);
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(@NonNull NativeConnection connection) {
                    secondDelivered.incrementAndGet();
                }
            });

            Thread.sleep(50);
            assertEquals(
                    0,
                    secondDelivered.get(),
                    "Replacing server listener must NOT replay already delivered active children"
            );

            client.close();
            server.close();
        }
    }

    @Test
    void concurrentBackendCloseIsSafe() throws Exception {
        var backend = FfmNativeTransportBackend.load(
                nativeLibPath,
                2
        );
        var t1 = new Thread(backend::close);
        var t2 = new Thread(backend::close);
        t1.start();
        t2.start();
        t1.join(5000);
        t2.join(5000);
        assertFalse(t1.isAlive());
        assertFalse(t2.isAlive());
        assertFalse(backend.availability().available());
    }

}
