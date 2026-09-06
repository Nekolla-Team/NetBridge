package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.nativebridge.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FfmNativeTransportBackend
        implements NativeTransportBackend, NativeEventListener {

    private final FfmNativeLibrary library;
    private final FfmNativeContext context;
    private final Map<Long, FfmNativeConnection> connections = new ConcurrentHashMap<>();
    private final Map<Long, FfmNativeServer> servers = new ConcurrentHashMap<>();
    private final Map<Long, List<Long>> pendingAccepted = new ConcurrentHashMap<>();
    private final Map<Long, NativeConnectionState> pendingConnectionStates = new ConcurrentHashMap<>();

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
        var earlyState = pendingConnectionStates.remove(connId);
        var initialState = earlyState != null
                ? earlyState
                : NativeConnectionState.CONNECTING;
        var conn = new FfmNativeConnection(
                this,
                connId,
                null,
                kind,
                initialState
        );
        connections.put(connId, conn);
        // Double check if an event arrived concurrently during instantiation
        var racedState = pendingConnectionStates.remove(connId);
        if (racedState != null) {
            conn.handleStateChanged(racedState);
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
        var buffered = pendingAccepted.remove(serverId);
        if (buffered != null) {
            buffered.forEach(connId -> {
                var earlyState = pendingConnectionStates.remove(connId);
                var initialState = earlyState != null
                        ? earlyState
                        : NativeConnectionState.CONNECTED;
                var acceptedConn = new FfmNativeConnection(
                        this,
                        connId,
                        server,
                        kind,
                        initialState
                );
                connections.put(connId, acceptedConn);
                server.handleAccepted(acceptedConn);
            });
        }
        return server;
    }

    @Override
    public synchronized void close() {
        if (state == NativeBackendState.CLOSED) {
            return;
        }

        state = NativeBackendState.CLOSING;
        context.dispatcher().removeListener(this);

        try {
            servers.values().forEach(server -> {
                try {
                    server.close();
                } catch (RuntimeException e) {
                    NetBridge.LOGGER.warn(
                            "Error closing native server {}: {}",
                            server.id(),
                            e.getMessage()
                    );
                }
            });

            servers.clear();

            connections.values().forEach(conn -> {
                try {
                    conn.close();
                } catch (RuntimeException e) {
                    NetBridge.LOGGER.warn(
                            "Error closing native connection {}: {}",
                            conn.id(),
                            e.getMessage()
                    );
                }
            });

            connections.clear();
            context.close();
            library.close();
            state = NativeBackendState.CLOSED;
        } catch (Throwable t) {
            state = NativeBackendState.AVAILABLE;
            context.dispatcher().addListener(this);
            if (t instanceof RuntimeException re) {
                throw re;
            }

            throw new NativeException("failed to close native transport backend", t);
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
        if (state == NativeBackendState.CLOSED || state == NativeBackendState.CLOSING) {
            return;
        }

        try {
            switch (event.eventKind()) {
                case NativeEvent.KIND_CONNECTION_STATE -> {
                    var conn = connections.get(event.objectId());
                    var mappedState = NativeConnectionState.fromAbi((int) event.arg0());
                    if (conn != null) {
                        conn.handleStateChanged(mappedState);
                    } else {
                        pendingConnectionStates.put(event.objectId(), mappedState);
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
                    if (server != null) {
                        var earlyState = pendingConnectionStates.remove(event.arg0());
                        var initialState = earlyState != null
                                ? earlyState
                                : NativeConnectionState.CONNECTED;
                        var accepted = new FfmNativeConnection(
                                this,
                                event.arg0(),
                                server,
                                server.transport(),
                                initialState
                        );
                        connections.put(event.arg0(), accepted);
                        var racedState = pendingConnectionStates.remove(event.arg0());
                        if (racedState != null) {
                            accepted.handleStateChanged(racedState);
                        }
                        server.handleAccepted(accepted);
                    } else {
                        pendingAccepted
                                .computeIfAbsent(
                                        event.objectId(),
                                        _ -> new CopyOnWriteArrayList<>()
                                )
                                .add(event.arg0());
                    }
                }
                case NativeEvent.KIND_SERVER_STATE -> {
                    var server = servers.get(event.objectId());
                    if (server != null) {
                        var st = switch ((int) event.arg0()) {
                            case 1 -> NativeServerState.RUNNING;
                            case 2 -> NativeServerState.STOPPED;
                            case 3 -> NativeServerState.FAILED;
                            default -> NativeServerState.RUNNING;
                        };
                        server.handleStateChanged(st);
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

}
