package top.tangge233.netbridge.nativebridge.fake;

import top.tangge233.netbridge.nativebridge.*;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class FakeNativeServer implements NativeServer {

    private final FakeNativeTransportBackend owner;
    private final long id;
    private final NativeTransportKind transport;
    private final List<NativeConnection> accepted = new ArrayList<>();

    private volatile int port;
    private volatile @Nullable NativeServerListener listener;
    private volatile boolean closed;

    FakeNativeServer(
            FakeNativeTransportBackend owner,
            long id,
            NativeServerRequest request
    ) {
        this.owner = owner;
        this.id = id;
        this.transport = request.transport();
    }

    void assignPort(int port) {
        this.port = port;
    }

    FakeNativeConnection acceptConnection(
            FakeNativeTransportBackend backend,
            long connId,
            FakeNativeConnection client
    ) {
        if (closed) {
            throw new NativeException("fake server is closed");
        }
        var accepted = FakeNativeConnection.serverSide(backend, connId, transport);
        accepted.assignPeerState(NativeConnectionState.CONNECTED);
        backend.registerConnection(accepted);
        this.accepted.add(accepted);
        var l = listener;
        if (l != null) {
            l.onAccepted(accepted);
        }
        return accepted;
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public NativeTransportKind transport() {
        return transport;
    }

    @Override
    public int localPort() {
        return port;
    }

    @Override
    public void setListener(NativeServerListener listener) {
        this.listener = listener;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        listener = null;
        owner.removeServer(id, port);
        accepted.clear();
    }

}
