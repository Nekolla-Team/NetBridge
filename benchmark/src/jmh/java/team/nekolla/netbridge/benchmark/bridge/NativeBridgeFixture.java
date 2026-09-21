package team.nekolla.netbridge.benchmark.bridge;

import team.nekolla.netbridge.config.SharedIoMode;
import team.nekolla.netbridge.nativebridge.*;
import team.nekolla.netbridge.nativebridge.internal.ffm.FfmNativeContext;
import team.nekolla.netbridge.nativebridge.internal.ffm.FfmNativeTransportBackend;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.Nullable;

/**
 * QUIC loopback fixture for L1 benchmarks: one production bridge per side.
 *
 * <p>Loaded once per JMH trial in {@code @Setup(Level.Trial)} — never per
 * invocation. {@code drainServer} keeps the client→server path empty so write benchmarks measure
 * success progress; {@code feedClient} keeps the server→ client path full so read benchmarks
 * measure actual data reads.
 */
@SuppressWarnings("NullAway")
public final class NativeBridgeFixture implements AutoCloseable {

    private final FfmNativeTransportBackend clientBackend;
    private final FfmNativeTransportBackend serverBackend;
    private final NativeServer server;
    private final NativeConnection clientConnection;
    private final NativeConnection serverConnection;

    private final AtomicReference<@Nullable Thread> drainThread = new AtomicReference<>();
    private final AtomicReference<@Nullable Thread> feedThread = new AtomicReference<>();
    private volatile boolean running = true;

    private NativeBridgeFixture(
            FfmNativeTransportBackend clientBackend,
            FfmNativeTransportBackend serverBackend,
            NativeServer server,
            NativeConnection clientConnection,
            NativeConnection serverConnection
    ) {
        super();
        this.clientBackend = clientBackend;
        this.serverBackend = serverBackend;
        this.server = server;
        this.clientConnection = clientConnection;
        this.serverConnection = serverConnection;
    }

    public static NativeBridgeFixture open(
            Path nativeLibrary,
            int workers,
            boolean drainServer,
            boolean feedClient
    ) {
        return open(
                nativeLibrary,
                workers,
                drainServer,
                feedClient,
                4096
        );
    }

    /**
     * Opens a fixture whose server-side feeder writes {@code feedChunkBytes}-sized chunks. Passing
     * the benchmark {@code size} keeps read benchmarks from being starved by an artificially small
     * feed chunk, so a "success" read reflects the negotiated size rather than the feeder's cap.
     */
    public static NativeBridgeFixture open(
            Path nativeLibrary,
            int workers,
            boolean drainServer,
            boolean feedClient,
            int feedChunkBytes
    ) {
        return open(
                nativeLibrary,
                workers,
                drainServer,
                feedClient,
                feedChunkBytes,
                SharedIoMode.DEFAULT,
                0,
                0
        );
    }

    /**
     * Full form used by the shared-ring A/B and capacity benchmarks: selects the Java data plane
     * ({@code auto}/{@code on}/{@code off}) and, when direct, the per-connection ring capacities.
     */
    public static NativeBridgeFixture open(
            Path nativeLibrary,
            int workers,
            boolean drainServer,
            boolean feedClient,
            int feedChunkBytes,
            SharedIoMode sharedIoMode,
            int sharedIoTxCapacity,
            int sharedIoRxCapacity
    ) {
        try {
            var clientBackend = FfmNativeTransportBackend.load(
                    nativeLibrary,
                    workers,
                    sharedIoMode,
                    sharedIoTxCapacity,
                    sharedIoRxCapacity
            );
            var serverBackend = FfmNativeTransportBackend.load(
                    nativeLibrary,
                    workers,
                    sharedIoMode,
                    sharedIoTxCapacity,
                    sharedIoRxCapacity
            );
            try {
                var server = serverBackend.startServer(
                        NativeServerRequest.quic(0, 16)
                );
                var accepted = new CountDownLatch(1);
                var acceptedRef = new AtomicReference<@Nullable NativeConnection>();
                server.setListener(new NativeServerListener() {
                    @Override
                    public void onAccepted(NativeConnection connection) {
                        acceptedRef.set(connection);
                        accepted.countDown();
                    }
                });

                var client = clientBackend.connect(NativeConnectRequest.quic(
                        "127.0.0.1",
                        server.localPort()
                ));
                awaitConnected(client, "client");
                if (!accepted.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("accepted connection never arrived");
                }
                var serverConn = acceptedRef.get();
                awaitConnected(serverConn, "server");

                var fixture = new NativeBridgeFixture(
                        clientBackend,
                        serverBackend,
                        server,
                        client,
                        serverConn
                );
                if (drainServer) {
                    fixture.startDrainer();
                }
                if (feedClient) {
                    fixture.startFeeder(Math.max(1, feedChunkBytes));
                }
                return fixture;
            } catch (Throwable t) {
                serverBackend.close();
                clientBackend.close();
                throw t;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("fixture setup failed", t);
        }
    }

    private static void awaitConnected(NativeConnection connection, String label) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (connection.state() == NativeConnectionState.CONNECTED) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting " + label);
            }
        }
        throw new IllegalStateException(label + " connection never reached CONNECTED");
    }

    private void startDrainer() {
        var thread = new Thread(
                () -> {
                    var scratch = ByteBuffer.allocateDirect(65536);
                    while (running) {
                        try {
                            var result = serverConnection.read(scratch);
                            if (result.wouldBlock()) {
                                LockSupport.parkNanos(2_000);
                            }
                        } catch (Exception _) {
                            // closed
                        }
                        scratch.clear();
                    }
                },
                "nb-bench-drain"
        );
        thread.setDaemon(true);
        drainThread.set(thread);
        thread.start();
    }

    private void startFeeder(int chunkBytes) {
        var thread = new Thread(
                () -> {
                    var chunk = ByteBuffer.allocateDirect(chunkBytes);
                    while (running) {
                        chunk.clear();
                        var result = serverConnection.write(chunk);
                        if (result.wouldBlock()) {
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                },
                "nb-bench-feed"
        );
        thread.setDaemon(true);
        feedThread.set(thread);
        thread.start();
    }

    /** Real downcall target for control benchmarks (never the Java cached state). */
    public FfmNativeContext context() {
        return clientBackend.context();
    }

    public NativeConnection clientConnection() {
        return clientConnection;
    }

    public NativeConnection serverConnection() {
        return serverConnection;
    }

    @Override
    public void close() {
        running = false;
        var drain = drainThread.get();
        if (drain != null) {
            drain.interrupt();
        }
        var feed = feedThread.get();
        if (feed != null) {
            feed.interrupt();
        }
        try {
            clientConnection.close();
        } catch (Throwable _) {
            // best-effort
        }
        try {
            serverConnection.close();
        } catch (Throwable _) {
            // best-effort
        }
        try {
            server.close();
        } catch (Throwable _) {
            // best-effort
        }
        serverBackend.close();
        clientBackend.close();
    }

}
