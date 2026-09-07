package top.tangge233.netbridge.nativebridge.internal.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * High-level wrapper around a single native context.
 *
 * <p>Retains the context lifecycle orchestration and the downcall bodies (struct assembly,
 * {@link FfmApiV1} invocation, status handling) while delegating all Java-side call-lifecycle
 * mechanics (open checks, active-operation accounting, drain, close-state synchronization) to an
 * internal {@link FfmCallGate}. Raw ABI-to-semantic translation lives in {@link FfmAbiCodec}.
 */
public final class FfmNativeContext implements AutoCloseable {

    private static final int DRAIN_TIMEOUT_MILLIS = 10_000;

    private final @Nullable FfmNativeLibrary ownerLibrary;
    private final FfmApiV1 api;
    private final MemorySegment contextPtr;
    private final NativeEventDispatcher dispatcher;
    private final FfmCallGate callGate = new FfmCallGate();

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
        return callGate.call(
                "connect",
                () -> {
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
                    }
                }
        );
    }

    public int connectionState(long connectionId) {
        return callGate.call(
                "connection_state",
                () -> {
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
                    }
                }
        );
    }

    public InetSocketAddress connectionRemoteAddress(long connectionId) {
        return callGate.call(
                "connection_remote_address",
                () -> {
                    try (var arena = Arena.ofConfined()) {
                        var outAddr = arena.allocate(FfmApiLayouts.SOCKET_ADDRESS_V1);
                        var status = (int) api.connectionRemoteAddress().invokeExact(
                                contextPtr,
                                connectionId,
                                outAddr
                        );
                        FfmStatus.checkStatus(status, "connection_remote_address");
                        return FfmAbiCodec.decodeSocketAddress(outAddr);
                    }
                }
        );
    }

    public FfmIoResult connectionWrite(
            long connectionId,
            MemorySegment data,
            int length
    ) {
        return callGate.call(
                "connection_write",
                () -> {
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
                    }
                }
        );
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
        return callGate.call(
                "connection_read",
                () -> {
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
                    }
                }
        );
    }

    public void connectionClose(long connectionId) {
        callGate.execute(
                "connection_close",
                () -> {
                    var status = (int) api.connectionClose().invokeExact(
                            contextPtr,
                            connectionId
                    );
                    if (status != FfmStatus.NB_NOT_FOUND) {
                        FfmStatus.checkStatus(status, "connection_close");
                    }
                }
        );
    }

    public long serverStart(
            int transportKind,
            @Nullable String bindHost,
            int port,
            int maxConnections,
            int kcpProfile
    ) {
        return callGate.call(
                "server_start",
                () -> {
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
                    }
                }
        );
    }

    public int serverPort(long serverId) {
        return callGate.call(
                "server_port",
                () -> {
                    try (var arena = Arena.ofConfined()) {
                        var outPort = arena.allocate(ValueLayout.JAVA_SHORT);
                        var status = (int) api.serverPort().invokeExact(
                                contextPtr,
                                serverId,
                                outPort
                        );
                        FfmStatus.checkStatus(status, "server_port");
                        return Short.toUnsignedInt(outPort.get(ValueLayout.JAVA_SHORT, 0));
                    }
                }
        );
    }

    public void serverStop(long serverId) {
        callGate.execute(
                "server_stop",
                () -> {
                    var status = (int) api.serverStop().invokeExact(contextPtr, serverId);
                    if (status != FfmStatus.NB_NOT_FOUND) {
                        FfmStatus.checkStatus(status, "server_stop");
                    }
                }
        );
    }

    public void shutdown(int timeoutMillis) {
        close(timeoutMillis);
    }

    public void close(int timeoutMillis) {
        callGate.close(
                timeoutMillis,
                remainingTimeoutMillis -> {
                    shutdownNative(remainingTimeoutMillis);
                    destroyNative();
                }
        );
        if (ownerLibrary != null) {
            ownerLibrary.unregisterContext(this);
        }
    }

    private void shutdownNative(int timeoutMillis) throws Throwable {
        var status = (int) api.contextShutdown().invokeExact(
                contextPtr,
                timeoutMillis
        );
        FfmStatus.checkStatus(status, "context_shutdown");
    }

    private void destroyNative() throws Throwable {
        var status = (int) api.contextDestroy().invokeExact(contextPtr);
        FfmStatus.checkStatus(status, "context_destroy");
    }

    public void destroy() {
        close();
    }

    @Override
    public void close() {
        close(DRAIN_TIMEOUT_MILLIS);
    }

}
