package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.nativebridge.NativeException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

public final class FfmNativeContext implements AutoCloseable {

    private static final int DRAIN_TIMEOUT_MILLIS = 10_000;

    private final @Nullable FfmNativeLibrary ownerLibrary;
    private final FfmApiV1 api;
    private final MemorySegment contextPtr;
    private final NativeEventDispatcher dispatcher;
    private final AtomicLong activeOps = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private volatile State state = State.OPEN;

    public FfmNativeContext(
            FfmApiV1 api,
            MemorySegment contextPtr,
            NativeEventDispatcher dispatcher
    ) {
        this(
                null,
                api,
                contextPtr,
                dispatcher
        );
    }

    public FfmNativeContext(
            @Nullable FfmNativeLibrary ownerLibrary,
            FfmApiV1 api,
            MemorySegment contextPtr,
            NativeEventDispatcher dispatcher
    ) {
        this.ownerLibrary = ownerLibrary;
        this.api = api;
        this.contextPtr = contextPtr;
        this.dispatcher = dispatcher;
    }

    public NativeEventDispatcher dispatcher() {
        return dispatcher;
    }

    public MemorySegment rawPointer() {
        return contextPtr;
    }

    public long connect(
            int transportKind,
            String host,
            int port,
            int kcpProfile
    ) {
        beginOp();
        try {
            try (var arena = Arena.ofConfined()) {
                var hostBytes = host.getBytes(StandardCharsets.UTF_8);
                var hostSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, hostBytes);

                var opts = arena.allocate(FfmApiLayouts.CONNECT_OPTIONS_V1);
                opts.set(
                        ValueLayout.JAVA_INT,
                        FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("struct_size")
                        ),
                        (int) FfmApiLayouts.CONNECT_OPTIONS_V1.byteSize()
                );
                opts.set(
                        ValueLayout.JAVA_INT,
                        FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("transport_kind")
                        ),
                        transportKind
                );

                var hostOffset = FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(
                        MemoryLayout.PathElement.groupElement("host_utf8")
                );
                opts.set(
                        ValueLayout.ADDRESS,
                        hostOffset + FfmApiLayouts.BYTES_VIEW_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("data")
                        ),
                        hostSegment
                );
                opts.set(
                        ValueLayout.JAVA_INT,
                        hostOffset + FfmApiLayouts.BYTES_VIEW_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("length")
                        ),
                        hostBytes.length
                );

                opts.set(
                        ValueLayout.JAVA_SHORT,
                        FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("port")
                        ),
                        (short) port
                );
                opts.set(
                        ValueLayout.JAVA_INT,
                        FfmApiLayouts.CONNECT_OPTIONS_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("kcp_profile")
                        ),
                        kcpProfile
                );

                var outConn = arena.allocate(ValueLayout.JAVA_LONG);
                var status = (int) api.connect().invokeExact(
                        contextPtr,
                        opts,
                        outConn
                );
                FfmStatus.checkStatus(status, "connect");
                return outConn.get(
                        ValueLayout.JAVA_LONG,
                        0
                );
            } catch (Throwable t) {
                throw rethrow(t, "connect");
            }
        } finally {
            endOp();
        }
    }

    private void beginOp() {
        if (state != State.OPEN) {
            throw new NativeException("NativeContext is " + state);
        }

        activeOps.incrementAndGet();
        if (state != State.OPEN) {
            activeOps.decrementAndGet();
            throw new NativeException("NativeContext is " + state);
        }
    }

    private static RuntimeException rethrow(Throwable t, String op) {
        return t instanceof RuntimeException re
                ? re
                : new RuntimeException(op + " invocation failed", t);
    }

    private void endOp() {
        activeOps.decrementAndGet();
        synchronized (lifecycleLock) {
            lifecycleLock.notifyAll();
        }
    }

    public int connectionState(long connectionId) {
        beginOp();
        try {
            try (var arena = Arena.ofConfined()) {
                var outState = arena.allocate(ValueLayout.JAVA_INT);
                var status = (int) api.connectionState().invokeExact(
                        contextPtr,
                        connectionId,
                        outState
                );
                if (status == FfmStatus.NB_NOT_FOUND) {
                    return -1;
                }
                FfmStatus.checkStatus(status, "connection_state");
                return outState.get(ValueLayout.JAVA_INT, 0);
            } catch (Throwable t) {
                throw rethrow(t, "connection_state");
            }
        } finally {
            endOp();
        }
    }

    public InetSocketAddress connectionRemoteAddress(long connectionId) {
        beginOp();
        try {
            try (var arena = Arena.ofConfined()) {
                var outAddr = arena.allocate(FfmApiLayouts.SOCKET_ADDRESS_V1);
                var status = (int) api.connectionRemoteAddress().invokeExact(
                        contextPtr,
                        connectionId,
                        outAddr
                );
                FfmStatus.checkStatus(status, "connection_remote_address");

                var family = outAddr.get(
                        ValueLayout.JAVA_INT,
                        FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("family")
                        )
                );
                var port = Short.toUnsignedInt(outAddr.get(
                        ValueLayout.JAVA_SHORT,
                        FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("port")
                        )
                ));
                var scopeId = outAddr.get(
                        ValueLayout.JAVA_INT,
                        FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("scope_id")
                        )
                );
                var addrOffset = FfmApiLayouts.SOCKET_ADDRESS_V1.byteOffset(
                        MemoryLayout.PathElement.groupElement("address")
                );

                InetAddress inet;
                if (family == 4) {
                    var ipBytes = new byte[4];
                    MemorySegment.copy(
                            outAddr,
                            addrOffset,
                            MemorySegment.ofArray(ipBytes),
                            0,
                            4
                    );
                    inet = InetAddress.getByAddress(ipBytes);
                } else if (family == 6) {
                    var ipBytes = new byte[16];
                    MemorySegment.copy(
                            outAddr,
                            addrOffset,
                            MemorySegment.ofArray(ipBytes),
                            0,
                            16
                    );
                    inet = scopeId != 0
                            ? Inet6Address.getByAddress(null, ipBytes, scopeId)
                            : InetAddress.getByAddress(ipBytes);
                } else {
                    throw new NativeException("UNSUPPORTED_SOCKET_FAMILY: family=" + family);
                }
                return new InetSocketAddress(inet, port);
            } catch (Throwable t) {
                throw rethrow(t, "connection_remote_address");
            }
        } finally {
            endOp();
        }
    }

    public FfmIoResult connectionWrite(
            long connectionId,
            MemorySegment data,
            int length
    ) {
        beginOp();
        try {
            try (var arena = Arena.ofConfined()) {
                var outWritten = arena.allocate(ValueLayout.JAVA_INT);
                var status = (int) api.connectionWrite().invokeExact(
                        contextPtr,
                        connectionId,
                        data,
                        length,
                        outWritten
                );
                var written = outWritten.get(ValueLayout.JAVA_INT, 0);
                return ioResult(status, written, "connection_write");
            } catch (Throwable t) {
                throw rethrow(t, "connection_write");
            }
        } finally {
            endOp();
        }
    }

    private static FfmIoResult ioResult(
            int status,
            int bytes,
            String op
    ) {
        return switch (status) {
            case FfmStatus.NB_OK, FfmStatus.NB_WOULD_BLOCK -> new FfmIoResult(status, bytes);
            case FfmStatus.NB_CLOSED, FfmStatus.NB_NOT_FOUND -> new FfmIoResult(status, 0);
            default -> throw new IllegalStateException(
                    op + " failed: " + FfmStatus.describe(status)
            );
        };
    }

    public FfmIoResult connectionRead(
            long connectionId,
            MemorySegment dst,
            int capacity
    ) {
        beginOp();
        try {
            try (var arena = Arena.ofConfined()) {
                var outRead = arena.allocate(ValueLayout.JAVA_INT);
                var status = (int) api.connectionRead().invokeExact(
                        contextPtr,
                        connectionId,
                        dst,
                        capacity,
                        outRead
                );
                var read = outRead.get(
                        ValueLayout.JAVA_INT,
                        0
                );
                return ioResult(status, read, "connection_read");
            } catch (Throwable t) {
                throw rethrow(t, "connection_read");
            }
        } finally {
            endOp();
        }
    }

    public void connectionClose(long connectionId) {
        beginOp();
        try {
            var status = (int) api.connectionClose().invokeExact(
                    contextPtr,
                    connectionId
            );
            if (status != FfmStatus.NB_NOT_FOUND) {
                FfmStatus.checkStatus(status, "connection_close");
            }
        } catch (Throwable t) {
            throw rethrow(t, "connection_close");
        } finally {
            endOp();
        }
    }

    public long serverStart(
            int transportKind,
            @Nullable String bindHost,
            int port,
            int maxConnections,
            int kcpProfile
    ) {
        beginOp();
        try {
            try (var arena = Arena.ofConfined()) {
                var opts = arena.allocate(FfmApiLayouts.SERVER_OPTIONS_V1);
                opts.set(
                        ValueLayout.JAVA_INT,
                        FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("struct_size")
                        ),
                        (int) FfmApiLayouts.SERVER_OPTIONS_V1.byteSize()
                );
                opts.set(
                        ValueLayout.JAVA_INT,
                        FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("transport_kind")
                        ),
                        transportKind
                );

                var bindOffset = FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                        MemoryLayout.PathElement.groupElement("bind_host_utf8")
                );
                if (bindHost != null && !bindHost.isEmpty()) {
                    var bytes = bindHost.getBytes(StandardCharsets.UTF_8);
                    var seg = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
                    opts.set(
                            ValueLayout.ADDRESS,
                            bindOffset + FfmApiLayouts.BYTES_VIEW_V1.byteOffset(
                                    MemoryLayout.PathElement.groupElement("data")
                            ),
                            seg
                    );
                    opts.set(
                            ValueLayout.JAVA_INT,
                            bindOffset + FfmApiLayouts.BYTES_VIEW_V1.byteOffset(
                                    MemoryLayout.PathElement.groupElement("length")
                            ),
                            bytes.length
                    );
                }

                opts.set(
                        ValueLayout.JAVA_SHORT,
                        FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("port")),
                        (short) port
                );
                opts.set(
                        ValueLayout.JAVA_INT,
                        FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("max_connections")
                        ),
                        maxConnections
                );
                opts.set(
                        ValueLayout.JAVA_INT,
                        FfmApiLayouts.SERVER_OPTIONS_V1.byteOffset(
                                MemoryLayout.PathElement.groupElement("kcp_profile")
                        ),
                        kcpProfile
                );

                var outServer = arena.allocate(ValueLayout.JAVA_LONG);
                var status = (int) api.serverStart().invokeExact(
                        contextPtr,
                        opts,
                        outServer
                );
                FfmStatus.checkStatus(status, "server_start");
                return outServer.get(ValueLayout.JAVA_LONG, 0);
            } catch (Throwable t) {
                throw rethrow(t, "server_start");
            }
        } finally {
            endOp();
        }
    }

    public int serverPort(long serverId) {
        beginOp();
        try {
            try (var arena = Arena.ofConfined()) {
                var outPort = arena.allocate(ValueLayout.JAVA_SHORT);
                var status = (int) api.serverPort().invokeExact(
                        contextPtr,
                        serverId,
                        outPort
                );
                FfmStatus.checkStatus(status, "server_port");
                return Short.toUnsignedInt(outPort.get(ValueLayout.JAVA_SHORT, 0));
            } catch (Throwable t) {
                throw rethrow(t, "server_port");
            }
        } finally {
            endOp();
        }
    }

    public void serverStop(long serverId) {
        beginOp();
        try {
            var status = (int) api.serverStop().invokeExact(contextPtr, serverId);
            if (status != FfmStatus.NB_NOT_FOUND) {
                FfmStatus.checkStatus(status, "server_stop");
            }
        } catch (Throwable t) {
            throw rethrow(t, "server_stop");
        } finally {
            endOp();
        }
    }

    public void shutdown(int timeoutMillis) {
        close(timeoutMillis);
    }

    public synchronized void close(int timeoutMillis) {
        if (state == State.CLOSED) {
            return;
        }

        state = State.CLOSING;
        var deadline = System.currentTimeMillis() + timeoutMillis;

        try {
            awaitDrain(deadline);
            var remaining = Math.max(
                    10,
                    (int) (deadline - System.currentTimeMillis())
            );
            shutdownLocked(remaining);
            destroyLocked();
            state = State.CLOSED;
            if (ownerLibrary != null) {
                ownerLibrary.unregisterContext(this);
            }
        } catch (Throwable t) {
            state = State.CLOSE_FAILED;
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new NativeException("failed to close native context", t);
        }
    }

    private void awaitDrain(long deadline) {
        while (activeOps.get() > 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new NativeException(
                        "TIMEOUT_DRAINING_ACTIVE_OPERATIONS: cannot destroy context with %d active operations".formatted(
                                activeOps.get()
                        )
                );
            }
            synchronized (lifecycleLock) {
                try {
                    lifecycleLock.wait(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new NativeException("INTERRUPTED_DRAINING_ACTIVE_OPERATIONS", e);
                }
            }
        }
    }

    private void shutdownLocked(int timeoutMillis) {
        try {
            var status = (int) api.contextShutdown().invokeExact(
                    contextPtr,
                    timeoutMillis
            );
            FfmStatus.checkStatus(status, "context_shutdown");
        } catch (Throwable t) {
            throw rethrow(t, "context_shutdown");
        }
    }

    private void destroyLocked() {
        try {
            var status = (int) api.contextDestroy().invokeExact(contextPtr);
            FfmStatus.checkStatus(status, "context_destroy");
        } catch (Throwable t) {
            throw rethrow(t, "context_destroy");
        }
    }

    public synchronized void destroy() {
        close();
    }

    @Override
    public synchronized void close() {
        close(DRAIN_TIMEOUT_MILLIS);
    }

    public State state() {
        return state;
    }

    public enum State {

        OPEN,
        CLOSING,
        CLOSED,
        CLOSE_FAILED

    }

}
