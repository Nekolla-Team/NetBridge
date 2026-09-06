package top.tangge233.netbridge.server;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.ability.NetworksEntry;
import top.tangge233.netbridge.ability.TransportProtocol;
import top.tangge233.netbridge.config.server.ServerSettings;
import top.tangge233.netbridge.config.server.ServerSettingsResolver;
import top.tangge233.netbridge.nativebridge.*;
import top.tangge233.netbridge.transport.KcpProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

public final class ServerTransportManager {

    private final NativeTransportBackend backend;
    private final ServerSettings settings;
    private final @Nullable NativeConnectionAdopter adopter;
    private final Executor adoptExecutor;
    private final long sessionGeneration;

    private @Nullable NativeServer quic;
    private @Nullable NativeServer kcp;
    private @Nullable NetworksAbility announcement;
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
                System.nanoTime()
        );
    }

    public ServerTransportManager(
            NativeTransportBackend backend,
            ServerSettings settings,
            @Nullable NativeConnectionAdopter adopter,
            Executor adoptExecutor,
            long sessionGeneration
    ) {
        this.backend = backend;
        this.settings = settings;
        this.adopter = adopter;
        this.adoptExecutor = adoptExecutor;
        this.sessionGeneration = sessionGeneration;
    }

    public synchronized boolean start(
            int mcPort,
            @Nullable String mcBindIp
    ) {
        if (closed || quic != null || kcp != null) {
            return quic != null || kcp != null;
        }

        var resolved = ServerSettingsResolver.resolve(settings, mcPort, mcBindIp);

        var entries = new LinkedHashMap<String, NetworksEntry>();
        var started = new ArrayList<NativeServer>();
        try {
            var q = startTransport("quic", resolved.quic());
            quic = q;
            if (q != null) {
                started.add(q);
            }
            collectAnnouncement(entries, "quic", resolved.quic(), q);

            var k = startTransport("kcp", resolved.kcp());
            kcp = k;
            if (k != null) {
                started.add(k);
            }
            collectAnnouncement(entries, "kcp", resolved.kcp(), k);
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

        announcement = NetworksAbility.of(entries.values().toArray(new NetworksEntry[0]));
        var any = quic != null || kcp != null;
        if (!any) {
            NetBridge.LOGGER.warn("No accelerated transport started; only TCP will be served");
        }
        return any;
    }

    private @Nullable NativeServer startTransport(
            String name,
            ServerSettingsResolver.ResolvedTransport transport
    ) {
        if (!transport.enabled() || !backend.availability().available()) {
            return null;
        }

        var request = name.equals("kcp")
                ?
                new NativeServerRequest(
                        NativeTransportKind.KCP,
                        transport.bindHost(),
                        transport.listenPort(),
                        transport.maxConnections(),
                        toKcpProfile(transport.kcpProfile())
                )
                : new NativeServerRequest(
                        NativeTransportKind.QUIC,
                        transport.bindHost(),
                        transport.listenPort(),
                        transport.maxConnections(),
                        NativeConnectRequest.KcpProfileValue.BALANCED
                );
        final NativeServer server;
        try {
            server = backend.startServer(request);
        } catch (RuntimeException e) {
            NetBridge.LOGGER.error(
                    "{} transport failed to bind udp/{}: transport disabled",
                    name,
                    transport.listenPort()
            );
            return null;
        }
        server.setListener(new NativeServerListener() {
            @Override
            public void onAccepted(NativeConnection connection) {
                dispatch(connection);
            }
        });
        NetBridge.LOGGER.info("{} acceptor listening on udp/{}", name, server.localPort());
        return server;
    }

    private static void collectAnnouncement(
            Map<String, NetworksEntry> entries,
            String name,
            ServerSettingsResolver.ResolvedTransport transport,
            @Nullable NativeServer server
    ) {
        if (server == null) {
            return;
        }

        var actual = server.localPort();
        if (actual <= 0) {
            return;
        }

        var protocol = name.equals("kcp")
                ? TransportProtocol.KCP_V1
                : TransportProtocol.QUIC_V1;
        entries.put(
                name,
                new NetworksEntry(
                        true,
                        transport.advertisedHost(),
                        actual,
                        protocol
                )
        );
    }

    private static NativeConnectRequest.KcpProfileValue toKcpProfile(
            @Nullable KcpProfile profile
    ) {
        return profile == KcpProfile.AGGRESSIVE
                ? NativeConnectRequest.KcpProfileValue.AGGRESSIVE
                : NativeConnectRequest.KcpProfileValue.BALANCED;
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

        closed = true;
        announcement = null;

        if (quic != null) {
            try {
                quic.close();
            } catch (RuntimeException e) {
                NetBridge.LOGGER.warn("Error stopping quic acceptor: {}", e.getMessage());
            }
            quic = null;
        }

        if (kcp != null) {
            try {
                kcp.close();
            } catch (RuntimeException e) {
                NetBridge.LOGGER.warn("Error stopping kcp acceptor: {}", e.getMessage());
            }
            kcp = null;
        }

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
                && isRunning()
                && this.sessionGeneration == generation;
    }

    public synchronized boolean isRunning() {
        return !closed
                && (quic != null || kcp != null);
    }

}
