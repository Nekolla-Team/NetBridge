package team.nekolla.netbridge.nativebridge.fake;

import team.nekolla.netbridge.nativebridge.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class FakeNativeTransportBackend implements NativeTransportBackend {

    private final AtomicLong connIds = new AtomicLong(1);
    private final AtomicLong serverIds = new AtomicLong(1);

    private final Map<Integer, FakeNativeServer> serversByPort = new ConcurrentHashMap<>();
    private final Map<Long, FakeNativeServer> servers = new ConcurrentHashMap<>();
    private final Map<Long, FakeNativeConnection> connections = new ConcurrentHashMap<>();
    private final List<Runnable> closeHooks = new ArrayList<>();

    private volatile boolean closed;

    @Override
    public NativeBackendAvailability availability() {
        if (closed) {
            return new NativeBackendAvailability(NativeBackendState.CLOSED, "closed");
        }
        return new NativeBackendAvailability(NativeBackendState.AVAILABLE, null);
    }

    @Override
    public NativeConnection connect(NativeConnectRequest request) {
        ensureOpen();
        if (request.port() <= 0) {
            throw new NativeException("fake connect requires an explicit port");
        }
        var server = serversByPort.get(request.port());
        if (server == null) {
            throw new NativeException("no fake server listening on port " + request.port());
        }
        var client = FakeNativeConnection.clientSide(
                this,
                connIds.getAndIncrement(),
                request.transport()
        );
        var accepted = server.acceptConnection(
                this,
                connIds.getAndIncrement(),
                client
        );
        client.bindPeer(accepted);
        accepted.bindPeer(client);
        client.assignPeerState(NativeConnectionState.CONNECTED);
        connections.put(client.id(), client);
        return client;
    }

    @Override
    public NativeServer startServer(NativeServerRequest request) {
        ensureOpen();
        var server = new FakeNativeServer(this, serverIds.getAndIncrement(), request);
        var port = request.port() == 0
                ? nextFreePort()
                : request.port();
        server.assignPort(port);
        serversByPort.put(port, server);
        servers.put(server.id(), server);
        return server;
    }

    private int nextFreePort() {
        for (int p = 40000; p < 65535; p++) {
            if (!serversByPort.containsKey(p)) {
                return p;
            }
        }
        throw new NativeException("no free fake port");
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        servers.values().forEach(FakeNativeServer::close);
        servers.clear();
        serversByPort.clear();
        connections.values().forEach(FakeNativeConnection::close);
        connections.clear();
        closeHooks.forEach(Runnable::run);
        closeHooks.clear();
    }

    private void ensureOpen() {
        if (closed) {
            throw new NativeException("fake backend is closed");
        }
    }

    void registerConnection(FakeNativeConnection conn) {
        connections.put(conn.id(), conn);
    }

    void unregisterConnection(long connId) {
        connections.remove(connId);
    }

    void removeServer(long serverId, int port) {
        servers.remove(serverId);
        serversByPort.remove(port);
    }

    public void addCloseHook(Runnable hook) {
        closeHooks.add(hook);
    }

}
