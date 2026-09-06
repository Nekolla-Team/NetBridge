package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.nativebridge.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

public final class FfmNativeTransportBackend
        implements NativeTransportBackend, NativeEventListener {

    private final FfmNativeLibrary library;
    private final FfmNativeContext context;
    private final Map<Long, FfmNativeConnection> connections = new ConcurrentHashMap<>();
    private final Map<Long, FfmNativeServer> servers = new ConcurrentHashMap<>();
    private final Map<Long, PendingConnRecord> pendingConnections = new ConcurrentHashMap<>();
    private final Map<Long, NativeServerState> pendingServerStates = new ConcurrentHashMap<>();
    private volatile NativeBackendState state = NativeBackendState.NEW;

    private FfmNativeTransportBackend(
            FfmNativeLibrary library,
            FfmNativeContext context
    ) {
        this.library = library;
        this.context = context;
    }

    public static FfmNativeTransportBackend load(
            Path libraryPath,
            int workerThreads
    ) {
        var library = FfmNativeLibrary.load(libraryPath);
        FfmNativeContext context;
        try {
            context = library.createContext(workerThreads);
        } catch (Throwable t) {
            library.close();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Failed to create native context", t);
        }
        var backend = new FfmNativeTransportBackend(library, context);
        backend.state = NativeBackendState.AVAILABLE;
        context.dispatcher().addListener(backend);
        return backend;
    }

    public FfmNativeContext context() {
        return context;
    }

    @Override
    public NativeBackendAvailability availability() {
        return switch (state) {
            case AVAILABLE -> new NativeBackendAvailability(
                    NativeBackendState.AVAILABLE,
                    null
            );
            case CLOSED -> new NativeBackendAvailability(
                    NativeBackendState.CLOSED,
                    "backend closed"
            );
            case CLOSING -> new NativeBackendAvailability(
                    NativeBackendState.CLOSING,
                    "backend closing"
            );
            case CLOSE_FAILED -> new NativeBackendAvailability(
                    NativeBackendState.CLOSE_FAILED,
                    "backend close failed; retry close only"
            );
            default -> new NativeBackendAvailability(
                    NativeBackendState.UNAVAILABLE,
                    "backend not available"
            );
        };
    }

    @Override
    public NativeConnection connect(NativeConnectRequest request) {
        ensureOpen();
        var kind = request.transport();
        var connId = context.connect(
                kind.abiValue(),
                request.host(),
                request.port(),
                request.kcpProfile().abiValue()
        );
        var early = pendingConnections.remove(connId);
        var initialState = early != null
                ? early.state
                : NativeConnectionState.CONNECTING;
        var conn = new FfmNativeConnection(
                this,
                connId,
                null,
                kind,
                initialState
        );
        connections.put(connId, conn);
        if (early != null && early.state != NativeConnectionState.CONNECTING) {
            conn.handleStateChanged(early.state, early.reason);
        }
        return conn;
    }

    @Override
    public NativeServer startServer(NativeServerRequest request) {
        ensureOpen();
        var kind = request.transport();
        var serverId = context.serverStart(
                kind.abiValue(),
                request.bindHost(),
                request.port(),
                request.maxConnections(),
                request.kcpProfile().abiValue()
        );
        var server = new FfmNativeServer(
                this,
                serverId,
                kind
        );
        servers.put(serverId, server);

        var earlyState = pendingServerStates.remove(serverId);
        if (earlyState != null) {
            server.handleStateChanged(earlyState);
        }

        var it = pendingConnections.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var pac = entry.getValue();
            if (pac.accepted
                    && pac.serverId != null
                    && pac.serverId == serverId
            ) {
                it.remove();
                if (pac.terminal) {
                    try {
                        context.connectionClose(pac.connId);
                    } catch (RuntimeException ignored) {
                        // Suppress connection close failure during early terminal reconciliation
                    }
                    continue;
                }
                var acceptedConn = new FfmNativeConnection(
                        this,
                        pac.connId,
                        server,
                        kind,
                        pac.state
                );
                connections.put(pac.connId, acceptedConn);
                server.handleAccepted(acceptedConn);
                if (pac.terminal) {
                    acceptedConn.handleStateChanged(pac.state, pac.reason);
                }
            }
        }
        return server;
    }

    @Override
    public synchronized void close() {
        if (state == NativeBackendState.CLOSED) {
            return;
        }

        state = NativeBackendState.CLOSING;

        for (var pac : pendingConnections.values()) {
            try {
                context.connectionClose(pac.connId);
            } catch (RuntimeException ignored) {
                // Ignore failure while closing unaccepted pending connections
            }
        }
        pendingConnections.clear();
        pendingServerStates.clear();

        List<Throwable> failures = new ArrayList<>();

        for (var server : servers.values()) {
            try {
                server.close();
            } catch (Throwable t) {
                failures.add(t);
                NetBridge.LOGGER.warn(
                        "Error closing native server {}: {}",
                        server.id(),
                        t.getMessage()
                );
            }
        }

        for (var conn : connections.values()) {
            try {
                conn.close();
            } catch (Throwable t) {
                failures.add(t);
                NetBridge.LOGGER.warn(
                        "Error closing native connection {}: {}",
                        conn.id(),
                        t.getMessage()
                );
            }
        }

        if (!servers.isEmpty() || !connections.isEmpty() || !failures.isEmpty()) {
            state = NativeBackendState.CLOSE_FAILED;
            var ex = new NativeException(
                    "failed to close native transport backend (%d servers, %d connections remained)".formatted(
                            servers.size(),
                            connections.size()
                    )
            );
            failures.forEach(ex::addSuppressed);
            throw ex;
        }

        try {
            context.close();
            library.close();
            context.dispatcher().removeListener(this);
            state = NativeBackendState.CLOSED;
        } catch (Throwable t) {
            state = NativeBackendState.CLOSE_FAILED;
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new NativeException("failed to close native context/library", t);
        }
    }

    private void ensureOpen() {
        var st = state;
        if (st != NativeBackendState.AVAILABLE) {
            throw new NativeException("Native backend is " + st);
        }
    }

    void unregisterConnection(long connId) {
        connections.remove(connId);
    }

    void unregisterServer(long serverId) {
        servers.remove(serverId);
    }

    @Override
    public void onEvent(NativeEvent event) {
        if (state == NativeBackendState.CLOSED) {
            return;
        }

        try {
            switch (event.eventKind()) {
                case NativeEvent.KIND_CONNECTION_STATE -> {
                    var conn = connections.get(event.objectId());
                    var mappedState = NativeConnectionState.fromAbi((int) event.arg0());
                    var reason = NativeFailureReason.fromCode(event.arg1());
                    if (conn != null) {
                        conn.handleStateChanged(mappedState, reason);
                    } else {
                        var pac = pendingConnections.computeIfAbsent(
                                event.objectId(),
                                PendingConnRecord::new
                        );
                        pac.state = mappedState;
                        pac.reason = reason;
                        if (mappedState == NativeConnectionState.CLOSED
                                || mappedState == NativeConnectionState.FAILED
                        ) {
                            pac.terminal = true;
                        }
                    }
                }
                case NativeEvent.KIND_DATA_AVAILABLE -> {
                    var conn = connections.get(event.objectId());
                    if (conn != null) {
                        conn.handleDataAvailable();
                    }
                }
                case NativeEvent.KIND_WRITABLE -> {
                    var conn = connections.get(event.objectId());
                    if (conn != null) {
                        conn.handleWritable();
                    }
                }
                case NativeEvent.KIND_ACCEPTED -> {
                    var server = servers.get(event.objectId());
                    var pac = pendingConnections.remove(event.arg0());
                    if (pac != null && pac.terminal) {
                        try {
                            context.connectionClose(event.arg0());
                        } catch (RuntimeException ignored) {
                            // Suppress cleanup failure for early terminal connection
                        }
                        return;
                    }
                    if (server != null) {
                        var initialState = pac != null
                                ? pac.state
                                : NativeConnectionState.CONNECTED;
                        var accepted = new FfmNativeConnection(
                                this,
                                event.arg0(),
                                server,
                                server.transport(),
                                initialState
                        );
                        connections.put(event.arg0(), accepted);
                        server.handleAccepted(accepted);
                    } else {
                        var early = pac != null
                                ? pac
                                : new PendingConnRecord(event.arg0());
                        early.serverId = event.objectId();
                        early.accepted = true;
                        pendingConnections.put(event.arg0(), early);
                    }
                }
                case NativeEvent.KIND_SERVER_STATE -> {
                    var server = servers.get(event.objectId());
                    var st = switch ((int) event.arg0()) {
                        case 1 -> NativeServerState.RUNNING;
                        case 2 -> NativeServerState.STOPPED;
                        case 3 -> NativeServerState.FAILED;
                        default -> NativeServerState.RUNNING;
                    };
                    if (server != null) {
                        server.handleStateChanged(st);
                    } else {
                        pendingServerStates.put(event.objectId(), st);
                    }
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            NetBridge.LOGGER.error(
                    "Error routing native event {}: {}",
                    event,
                    t.getMessage(),
                    t
            );
        }
    }

    private static final class PendingConnRecord {

        final long connId;
        volatile @Nullable Long serverId = null;
        volatile NativeConnectionState state = NativeConnectionState.CONNECTING;
        volatile NativeFailureReason reason = NativeFailureReason.GENERIC;
        volatile boolean terminal = false;
        volatile boolean accepted = false;
        volatile long createdAt = System.currentTimeMillis();

        PendingConnRecord(long connId) {
            this.connId = connId;
        }

    }

}
