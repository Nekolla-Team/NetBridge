package top.tangge233.netbridge.benchmark;

import top.tangge233.netbridge.nativebridge.*;
import top.tangge233.netbridge.nativebridge.internal.ffm.FfmNativeTransportBackend;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("NullAway")
public final class FfmBenchmark {

    private static final int WARMUP_OPS = 2_000;
    private static final int MEASURED_OPS = 20_000;
    private static final AtomicLong BENCH_BYTES = new AtomicLong();
    private static final AtomicReference<long[]> BENCH_LATENCIES = new AtomicReference<>(new long[0]);

    private FfmBenchmark() {
    }

    static void main(String[] args) throws Exception {
        var libPath = Path.of(args.length > 0
                ? args[0]
                : System.getProperty("netbridge.native.path"));
        var seconds = args.length > 1
                ? Long.parseLong(args[1])
                : 5;

        try (
                var backend = FfmNativeTransportBackend.load(
                        libPath,
                        2
                )
        ) {
            var server = backend.startServer(NativeServerRequest.quic(
                    0,
                    8
            ));
            var accepted = new CountDownLatch(1);
            var serverConnRef = new AtomicReference<@Nullable NativeConnection>();
            server.setListener(new NativeServerListener() {
                @Override
                public void onAccepted(NativeConnection connection) {
                    serverConnRef.set(connection);
                    accepted.countDown();
                }
            });
            var client = backend.connect(NativeConnectRequest.quic(
                    "127.0.0.1",
                    server.localPort()
            ));
            awaitState(client, NativeConnectionState.CONNECTED);
            if (!accepted.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("accept timeout");
            }

            var serverConn = serverConnRef.get();
            awaitState(
                    serverConn,
                    NativeConnectionState.CONNECTED
            );
            serverConn.setListener(new NativeConnectionListener() {
            });

            benchEmptyDowncall(serverConn);
            benchChunkRoundtrip(
                    "write+read 1KiB",
                    client,
                    serverConn,
                    1024,
                    MEASURED_OPS
            );
            benchChunkRoundtrip(
                    "write+read 64KiB",
                    client,
                    serverConn,
                    64 * 1024,
                    MEASURED_OPS / 8
            );
            benchThroughput(
                    "QUIC loopback throughput",
                    client,
                    serverConn,
                    seconds
            );

            server.close();
            client.close();
            serverConn.close();
        }
    }

    private static void benchEmptyDowncall(NativeConnection probe) {
        IntStream.range(0, WARMUP_OPS)
                .forEach(_ -> probe.state());
        var start = System.nanoTime();
        IntStream.range(0, MEASURED_OPS)
                .forEach(_ -> probe.state());
        var elapsed = System.nanoTime() - start;
        report(
                "empty downcall (connection_state)",
                MEASURED_OPS,
                elapsed,
                new long[0]
        );
    }

    private static void benchChunkRoundtrip(
            String name,
            NativeConnection client,
            NativeConnection serverConn,
            int bytes,
            int rounds
    ) {
        var payload = new byte[bytes];
        IntStream.range(0, bytes)
                .forEach(i ->
                        payload[i] = (byte) (i & 0xFF)
                );
        var latencies = new long[rounds];
        var buf = ByteBuffer.allocateDirect(bytes);

        for (var round = -500; round < rounds; round++) {
            var start = System.nanoTime();
            var write = client.write(ByteBuffer.wrap(payload));
            if (!write.progressed()) {
                throw new IllegalStateException(name + ": write would block at round " + round);
            }

            var got = 0;
            var readDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (got < bytes) {
                var read = serverConn.read(buf);
                if (read.progressed()) {
                    got += read.bytes();
                    buf.clear();
                } else if (read.closed()) {
                    throw new IllegalStateException(name + ": connection closed");
                } else if (System.nanoTime() > readDeadline) {
                    throw new IllegalStateException(name + ": read timeout at round " + round);
                }
            }

            var elapsed = System.nanoTime() - start;
            if (round >= 0) {
                latencies[round] = elapsed;
            }
        }

        var totalNanos = Arrays.stream(latencies).sum();
        report(
                name,
                rounds,
                totalNanos,
                latencies
        );
    }

    private static void benchThroughput(
            String name,
            NativeConnection client,
            NativeConnection serverConn,
            long seconds
    ) throws InterruptedException {
        var chunk = new byte[64 * 1024];
        var reader = Thread.ofPlatform()
                .daemon()
                .name("bench-reader")
                .start(() -> {
                    var buf = ByteBuffer.allocateDirect(64 * 1024);
                    var got = 0L;
                    var last = 0L;
                    var lat = new ArrayList<Long>();
                    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds + 2);
                    var runUntil = System.currentTimeMillis() + seconds * 1000;

                    while (System.currentTimeMillis() < runUntil && System.nanoTime() < deadline) {
                        var read = serverConn.read(buf);
                        if (read.progressed()) {
                            var now = System.nanoTime();
                            for (var i = 0; i < read.bytes(); i += 64 * 1024) {
                                if (last != 0) {
                                    lat.add(now - last);
                                }
                                last = now;
                            }
                            got += read.bytes();
                            buf.clear();
                        } else {
                            LockSupport.parkNanos(1_000);
                        }
                        BENCH_BYTES.set(got);
                    }

                    BENCH_LATENCIES.set(
                            lat.stream()
                                    .mapToLong(Long::longValue)
                                    .sorted()
                                    .toArray()
                    );
                });

        var start = System.currentTimeMillis();
        var sent = 0L;
        while (System.currentTimeMillis() - start < seconds * 1000) {
            var write = client.write(ByteBuffer.wrap(chunk));
            if (write.progressed()) {
                sent += write.bytes();
            } else {
                LockSupport.parkNanos(1_000);
            }
        }

        var elapsedMs = System.currentTimeMillis() - start;
        Thread.sleep(300);
        BENCH_BYTES.set(Math.min(BENCH_BYTES.get(), sent));

        var mib = BENCH_BYTES.get() / (1024.0 * 1024.0);
        var secs = elapsedMs / 1000.0;
        System.out.printf(
                Locale.ROOT,
                "%-32s  throughput %10.1f MiB/s  (%d MiB sent in %.2fs)%n",
                name,
                mib / secs,
                (long) mib,
                secs
        );
        printPercentiles(name, BENCH_LATENCIES.get());
        reader.join(5_000);
    }

    private static void report(
            String name,
            int ops,
            long totalNanos,
            long[] latencies
    ) {
        System.out.printf(
                Locale.ROOT,
                "%-32s  ops=%d  avg=%8.2f us/op%s%n",
                name,
                ops,
                totalNanos / (double) ops / 1000.0,
                ""
        );
        printPercentiles(name, latencies);
    }

    private static void printPercentiles(
            String name,
            long[] sorted
    ) {
        if (sorted.length == 0) {
            return;
        }

        var p50 = sorted[(int) (sorted.length * 0.50)];
        var p95 = sorted[(int) Math.min(sorted.length - 1, sorted.length * 0.95)];
        var p99 = sorted[(int) Math.min(sorted.length - 1, sorted.length * 0.99)];

        System.out.printf(
                Locale.ROOT,
                "%-32s  p50=%8.2f us  p95=%8.2f us  p99=%8.2f us  (n=%d)%n",
                name,
                p50 / 1000.0,
                p95 / 1000.0,
                p99 / 1000.0,
                sorted.length
        );
    }

    private static void awaitState(
            NativeConnection conn,
            NativeConnectionState want
    ) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000;
        while (conn.state() != want && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        if (conn.state() != want) {
            throw new IllegalStateException("connection did not reach " + want);
        }
    }

}
