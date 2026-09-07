package top.tangge233.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.nativebridge.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.jspecify.annotations.NonNull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmBackendStressTest {

    private static final int BURST_CHUNKS = 256;
    private static final int CHUNK_BYTES = 8 * 1024;

    private static Path nativeLibPath;

    @BeforeAll
    static void setUp() {
        nativeLibPath = FfmTestSupport.requireNativeLibraryOrSkip();
    }

    @Test
    void callbackBurstDeliversAllBytesWithCoalescedEvents() throws Exception {
        try (var backend = FfmNativeTransportBackend.load(nativeLibPath, 2)) {
            var server = backend.startServer(NativeServerRequest.quic(0, 32));
            var accepted = new CountDownLatch(1);
            var serverConnRef = new AtomicReference<NativeConnection>();
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(@NonNull NativeConnection connection) {
                    serverConnRef.set(connection);
                    accepted.countDown();
                }
            });

            var client = backend.connect(
                    NativeConnectRequest.quic("127.0.0.1", server.localPort())
            );
            awaitState(client, NativeConnectionState.CONNECTED);
            assertTrue(accepted.await(10, TimeUnit.SECONDS));
            var serverConn = serverConnRef.get();
            awaitState(serverConn, NativeConnectionState.CONNECTED);

            var dataEvents = new AtomicInteger();
            var drainRequested = new CountDownLatch(1);
            serverConn.setListener(new NativeConnectionListener() {
                @Override
                public void onDataAvailable() {
                    dataEvents.incrementAndGet();
                    drainRequested.countDown();
                }
            });

            var chunk = new byte[CHUNK_BYTES];
            IntStream.range(0, chunk.length)
                    .forEach(i ->
                            chunk[i] = (byte) (i & 0xFF)
                    );

            var total = (long) BURST_CHUNKS * CHUNK_BYTES;
            var written = new AtomicLong();
            IntStream.range(0, BURST_CHUNKS)
                    .forEach(i -> {
                        var res = client.write(ByteBuffer.wrap(chunk));
                        assertEquals(
                                NativeIoResult.progressed(CHUNK_BYTES),
                                res,
                                "burst write " + i
                        );
                        written.addAndGet(CHUNK_BYTES);
                    });
            assertEquals(total, written.get());

            assertTrue(
                    drainRequested.await(10, TimeUnit.SECONDS),
                    "至少应收到一次 DATA_AVAILABLE 事件（事件允许合并）"
            );

            var received = ByteBuffer.allocate(CHUNK_BYTES);
            var got = 0L;
            var readDeadline = System.currentTimeMillis() + 30_000;
            while (got < total && System.currentTimeMillis() < readDeadline) {
                var res = serverConn.read(received);
                if (res.progressed()) {
                    for (var i = 0; i < res.bytes(); i++) {
                        assertEquals(
                                (byte) ((got + i) % CHUNK_BYTES & 0xFF), received.get(i),
                                "字节流错位 at " + (got + i)
                        );
                    }
                    got += res.bytes();
                    received.clear();
                } else {
                    Thread.sleep(5);
                }
            }
            assertEquals(total, got, "回调风暴下必须无丢字节数");

            server.close();
            client.close();
            serverConn.close();
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
    void wouldBlockIsResolvedByRustWritableEvent() throws Exception {
        try (var backend = FfmNativeTransportBackend.load(nativeLibPath, 2)) {
            var server = backend.startServer(NativeServerRequest.quic(0, 8));
            var accepted = new CountDownLatch(1);
            var serverConnRef = new AtomicReference<NativeConnection>();

            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(@NonNull NativeConnection connection) {
                    serverConnRef.set(connection);
                    accepted.countDown();
                }
            });
            var client = backend.connect(
                    NativeConnectRequest.quic("127.0.0.1", server.localPort())
            );

            awaitState(client, NativeConnectionState.CONNECTED);
            assertTrue(accepted.await(10, TimeUnit.SECONDS));

            var serverConn = serverConnRef.get();

            awaitState(serverConn, NativeConnectionState.CONNECTED);
            serverConn.setListener(new NativeConnectionListener() {
            });

            var writable = new CountDownLatch(1);
            client.setListener(new NativeConnectionListener() {
                @Override
                public void onWritable() {
                    writable.countDown();
                }
            });

            var chunk = ByteBuffer.wrap(new byte[64 * 1024]);
            var blocked = false;
            var sent = 0;
            for (var i = 0; i < 8192 && !blocked; i++) {
                var snapshot = chunk.duplicate();
                var res = client.write(snapshot);
                if (res.wouldBlock()) {
                    blocked = true;
                } else {
                    sent += res.bytes();
                }
            }
            assertTrue(blocked, "填满写队列后必须出现 WOULD_BLOCK");

            var readBuf = ByteBuffer.allocate(64 * 1024);
            var drained = 0L;
            var readDeadline = System.currentTimeMillis() + 30_000;
            while (drained < sent && System.currentTimeMillis() < readDeadline) {
                var res = serverConn.read(readBuf);
                if (res.progressed()) {
                    drained += res.bytes();
                    readBuf.clear();
                } else {
                    Thread.sleep(5);
                }
            }
            assertEquals(
                    sent,
                    drained,
                    "回压期间不得丢字节"
            );

            assertTrue(
                    writable.await(30, TimeUnit.SECONDS),
                    "队列恢复空间后必须收到 WRITABLE 事件"
            );

            var after = client.write(ByteBuffer.wrap(new byte[1024]));
            assertTrue(
                    after.progressed(),
                    "WRITABLE 后写应恢复"
            );

            server.close();
            client.close();
            serverConn.close();
        }
    }

    @Test
    void repeatedBackendCyclesStayClean() throws Exception {
        for (var cycle = 0; cycle < 4; cycle++) {
            try (
                    var backend = FfmNativeTransportBackend.load(
                            nativeLibPath,
                            2
                    )
            ) {
                var server = backend.startServer(
                        NativeServerRequest.quic(0, 16)
                );
                var accepted = new CountDownLatch(1);
                var serverConnRef = new AtomicReference<NativeConnection>();
                server.setListener(new NativeServerListener() {
                    @Override
                    public void onAccepted(@NonNull NativeConnection connection) {
                        serverConnRef.set(connection);
                        accepted.countDown();
                    }
                });
                var client = backend.connect(
                        NativeConnectRequest.quic("127.0.0.1", server.localPort())
                );
                awaitState(client, NativeConnectionState.CONNECTED);
                assertTrue(accepted.await(10, TimeUnit.SECONDS));
                var serverConn = serverConnRef.get();
                awaitState(serverConn, NativeConnectionState.CONNECTED);

                var payload = ("cycle-" + cycle).getBytes(StandardCharsets.UTF_8);
                assertEquals(
                        NativeIoResult.progressed(payload.length),
                        client.write(ByteBuffer.wrap(payload))
                );
                var readBuf = ByteBuffer.allocate(1024);
                var readDeadline = System.currentTimeMillis() + 10_000;
                NativeIoResult res;
                do {
                    res = serverConn.read(readBuf);
                    if (!res.progressed()) {
                        Thread.sleep(5);
                    }
                }
                while (!res.progressed() && System.currentTimeMillis() < readDeadline);
                assertTrue(
                        res.progressed(),
                        "cycle " + cycle + ": roundtrip read timeout"
                );
                assertEquals(payload.length, res.bytes());

                server.close();
                client.close();
                serverConn.close();
            }
        }
    }

}
