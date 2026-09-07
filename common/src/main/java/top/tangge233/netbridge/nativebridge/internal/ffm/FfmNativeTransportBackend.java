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
        long connId;
        NativeTransportKind kind;
        switch (request) {
            case NativeConnectRequest.Quic quic -> {
                kind = NativeTransportKind.QUIC;
                connId = context.connect(
                        FfmAbiCodec.transportToAbi(kind),
                        quic.host(),
                        quic.port(),
                        FfmAbiCodec.kcpProfileToAbi(NativeKcpProfile.BALANCED)
                );
            }
            case NativeConnectRequest.Kcp kcp -> {
                kind = NativeTransportKind.KCP;
                connId = context.connect(
                        FfmAbiCodec.transportToAbi(kind),
                        kcp.host(),
                        kcp.port(),
                        FfmAbiCodec.kcpProfileToAbi(kcp.profile())
                );
            }
        }
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
        if (early != null
                && early.state != NativeConnectionState.CONNECTING
        ) {
            conn.handleStateChanged(early.state, early.reason);
        }
        return conn;
    }

    @Override
    public NativeServer startServer(NativeServerRequest request) {
        ensureOpen();
        long serverId;
        NativeTransportKind kind;
        switch (request) {
            case NativeServerRequest.Quic quic -> {
                kind = NativeTransportKind.QUIC;
                serverId = context.serverStart(
                        FfmAbiCodec.transportToAbi(kind),
                        quic.bindHost(),
                        quic.port(),
                        quic.maxConnections(),
                        FfmAbiCodec.kcpProfileToAbi(NativeKcpProfile.BALANCED)
                );
            }
            case NativeServerRequest.Kcp kcp -> {
                kind = NativeTransportKind.KCP;
                serverId = context.serverStart(
                        FfmAbiCodec.transportToAbi(kind),
                        kcp.bindHost(),
                        kcp.port(),
                        kcp.maxConnections(),
                        FfmAbiCodec.kcpProfileToAbi(kcp.profile())
                );
            }
        }
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
                    } catch (RuntimeException _) {
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
            } catch (RuntimeException _) {
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
            switch (event) {
                case NativeEvent.ConnectionStateChanged e -> {
                    var conn = connections.get(e.connectionId());
                    if (conn != null) {
                        conn.handleStateChanged(e.state(), e.reason());
                    } else {
                        var pac = pendingConnections.computeIfAbsent(
                                e.connectionId(),
                                PendingConnRecord::new
                        );
                        pac.state = e.state();
                        pac.reason = e.reason();
                        if (e.state() == NativeConnectionState.CLOSED
                                || e.state() == NativeConnectionState.FAILED
                        ) {
                            pac.terminal = true;
                        }
                    }
                }

                case NativeEvent.DataAvailable e -> {
                    var conn = connections.get(e.connectionId());
                    if (conn != null) {
                        conn.handleDataAvailable();
                    }
                }

                case NativeEvent.Writable e -> {
                    var conn = connections.get(e.connectionId());
                    if (conn != null) {
                        conn.handleWritable();
                    }
                }

                case NativeEvent.Accepted e -> {
                    var server = servers.get(e.serverId());
                    var pac = pendingConnections.remove(e.connectionId());
                    if (pac != null && pac.terminal) {
                        try {
                            context.connectionClose(e.connectionId());
                        } catch (RuntimeException _) {
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
                                e.connectionId(),
                                server,
                                server.transport(),
                                initialState
                        );
                        connections.put(e.connectionId(), accepted);
                        server.handleAccepted(accepted);
                    } else {
                        var early = pac != null
                                ? pac
                                : new PendingConnRecord(e.connectionId());
                        early.serverId = e.serverId();
                        early.accepted = true;
                        pendingConnections.put(e.connectionId(), early);
                    }
                }

                case NativeEvent.ServerStateChanged e -> {
                    var server = servers.get(e.serverId());
                    var st = e.state();
                    if (server != null) {
                        server.handleStateChanged(st);
                    } else {
                        pendingServerStates.put(e.serverId(), st);
                    }
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

        PendingConnRecord(long connId) {
            this.connId = connId;
        }

    }

}
