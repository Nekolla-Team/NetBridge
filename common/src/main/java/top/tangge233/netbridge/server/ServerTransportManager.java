package top.tangge233.netbridge.server;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.ability.NetworksEntry;
import top.tangge233.netbridge.config.server.ServerSettings;
import top.tangge233.netbridge.config.server.ServerSettingsResolver;
import top.tangge233.netbridge.nativebridge.*;
import top.tangge233.netbridge.transport.AcceleratedTransport;
import top.tangge233.netbridge.transport.KcpProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

public final class ServerTransportManager {

    private final NativeTransportBackend backend;
    private final ServerSettings settings;
    private final @Nullable NativeConnectionAdopter adopter;
    private final Executor adoptExecutor;
    private final long sessionGeneration;
    private final @Nullable Integer quicPortOverride;

    private @Nullable NativeServer quic;
    private @Nullable NativeServer kcp;
    private @Nullable NetworksAbility announcement;
    private volatile boolean starting;
    private volatile boolean closed;

    public ServerTransportManager(
            NativeTransportBackend backend,
            ServerSettings settings,
            @Nullable NativeConnectionAdopter adopter,
            Executor adoptExecutor
    ) {
        this(
                backend,
                settings,
                adopter,
                adoptExecutor,
                System.nanoTime(),
                null
        );
    }

    public ServerTransportManager(
            NativeTransportBackend backend,
            ServerSettings settings,
            @Nullable NativeConnectionAdopter adopter,
            Executor adoptExecutor,
            long sessionGeneration,
            @Nullable Integer quicPortOverride
    ) {
        this.backend = backend;
        this.settings = settings;
        this.adopter = adopter;
        this.adoptExecutor = adoptExecutor;
        this.sessionGeneration = sessionGeneration;
        this.quicPortOverride = quicPortOverride;
    }

    public ServerTransportManager(
            NativeTransportBackend backend,
            ServerSettings settings,
            @Nullable NativeConnectionAdopter adopter,
            Executor adoptExecutor,
            long sessionGeneration
    ) {
        this(
                backend,
                settings,
                adopter,
                adoptExecutor,
                sessionGeneration,
                null
        );
    }

    public synchronized boolean start(
            int mcPort,
            @Nullable String mcBindIp
    ) {
        if (closed || quic != null || kcp != null) {
            return quic != null || kcp != null;
        }

        starting = true;
        try {
            var resolved = ServerSettingsResolver.resolve(
                    settings,
                    mcPort,
                    mcBindIp,
                    quicPortOverride
            );

            var entries = new LinkedHashMap<AcceleratedTransport, NetworksEntry>();
            var started = new ArrayList<NativeServer>();
            try {
                var q = startTransport(AcceleratedTransport.QUIC, resolved.quic());
                quic = q;
                if (q != null) {
                    started.add(q);
                }
                collectAnnouncement(entries, AcceleratedTransport.QUIC, resolved.quic(), q);

                var k = startTransport(AcceleratedTransport.KCP, resolved.kcp());
                kcp = k;
                if (k != null) {
                    started.add(k);
                }
                collectAnnouncement(entries, AcceleratedTransport.KCP, resolved.kcp(), k);
            } catch (RuntimeException e) {
                started.forEach(s -> {
                    try {
                        s.close();
                    } catch (RuntimeException ce) {
                        NetBridge.LOGGER.warn(
                                "Error closing transport after failed start: {}",
                                ce.getMessage()
                        );
                    }
                });
                quic = null;
                kcp = null;
                NetBridge.LOGGER.error("Server transport start failed: {}", e.getMessage());
                return false;
            }

            announcement = NetworksAbility.of(entries);
            var any = quic != null || kcp != null;
            if (!any) {
                NetBridge.LOGGER.warn("No accelerated transport started; only TCP will be served");
            }
            return any;
        } finally {
            starting = false;
        }
    }

    private @Nullable NativeServer startTransport(
            AcceleratedTransport transport,
            ServerSettingsResolver.ResolvedTransport resolved
    ) {
        if (!resolved.enabled() || !backend.availability().available()) {
            return null;
        }

        var request = transport == AcceleratedTransport.KCP
                ?
                NativeServerRequest.kcp(
                        resolved.bindHost(),
                        resolved.listenPort(),
                        resolved.maxConnections(),
                        toNativeKcpProfile(resolved.kcpProfile())
                )
                : NativeServerRequest.quic(
                        resolved.bindHost(),
                        resolved.listenPort(),
                        resolved.maxConnections()
                );
        final NativeServer server;
        try {
            server = backend.startServer(request);
        } catch (RuntimeException e) {
            NetBridge.LOGGER.error(
                    "{} transport failed to bind udp/{}: transport disabled",
                    transport.key(),
                    resolved.listenPort()
            );
            return null;
        }
        server.setListener(new NativeServerListener() {
            @Override
            public void onAccepted(NativeConnection connection) {
                dispatch(connection);
            }
        });
        NetBridge.LOGGER.info(
                "{} acceptor listening on udp/{}",
                transport.key(),
                server.localPort()
        );
        return server;
    }

    private static void collectAnnouncement(
            Map<AcceleratedTransport, NetworksEntry> entries,
            AcceleratedTransport transport,
            ServerSettingsResolver.ResolvedTransport resolved,
            @Nullable NativeServer server
    ) {
        if (server == null) {
            return;
        }

        var actual = server.localPort();
        if (actual <= 0) {
            return;
        }

        entries.put(
                transport,
                new NetworksEntry(
                        true,
                        resolved.advertisedHost(),
                        actual
                )
        );
    }

    private static NativeKcpProfile toNativeKcpProfile(
            @Nullable KcpProfile profile
    ) {
        return profile == KcpProfile.AGGRESSIVE
                ? NativeKcpProfile.AGGRESSIVE
                : NativeKcpProfile.BALANCED;
    }

    private void dispatch(NativeConnection connection) {
        adoptExecutor.execute(() -> {
            if (closed) {
                try {
                    connection.close();
                } catch (RuntimeException e) {
                    NetBridge.LOGGER.warn(
                            "Failed to close connection after manager close: {}",
                            connection.id()
                    );
                }
                return;
            }

            var handler = adopter;
            if (handler == null) {
                NetBridge.LOGGER.warn(
                        "Connection {} rejected: no connection handler registered",
                        connection.id()
                );
                try {
                    connection.close();
                } catch (RuntimeException e) {
                    NetBridge.LOGGER.warn(
                            "Failed to close rejected connection {}",
                            connection.id(),
                            e
                    );
                }
                return;
            }

            try {
                handler.adopt(connection, sessionGeneration);
            } catch (Throwable t) {
                NetBridge.LOGGER.warn(
                        "Connection handler failed for conn {}",
                        connection.id(),
                        t
                );
                try {
                    connection.close();
                } catch (RuntimeException e) {
                    NetBridge.LOGGER.warn(
                            "Failed to close failed connection {}",
                            connection.id(),
                            e
                    );
                }
            }
        });
    }

    public synchronized void close() {
        if (closed) {
            return;
        }

        List<Throwable> errors = new ArrayList<>();
        announcement = null;

        if (quic != null) {
            try {
                quic.close();
                quic = null;
            } catch (Throwable e) {
                NetBridge.LOGGER.warn("Error stopping quic acceptor", e);
                errors.add(e);
            }
        }

        if (kcp != null) {
            try {
                kcp.close();
                kcp = null;
            } catch (Throwable e) {
                NetBridge.LOGGER.warn("Error stopping kcp acceptor", e);
                errors.add(e);
            }
        }

        if (!errors.isEmpty()) {
            var primary = new RuntimeException("Failed to close all server transports cleanly");
            errors.forEach(primary::addSuppressed);
            throw primary;
        }

        closed = true;
        NetBridge.LOGGER.info("Server transport manager stopped");
    }

    public synchronized NetworksAbility announcement() {
        var a = announcement;
        return a != null
                ? a
                : NetworksAbility.empty();
    }

    public long sessionGeneration() {
        return sessionGeneration;
    }

    public synchronized boolean isSessionValid(long generation) {
        return !closed
                && (starting || isRunning())
                && this.sessionGeneration == generation;
    }

    public synchronized boolean isRunning() {
        return !closed
                && (quic != null || kcp != null);
    }

}
