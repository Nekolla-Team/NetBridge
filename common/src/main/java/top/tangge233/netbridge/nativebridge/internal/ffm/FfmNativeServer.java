package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.nativebridge.*;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jspecify.annotations.Nullable;

public final class FfmNativeServer implements NativeServer {

    private final FfmNativeTransportBackend owner;
    private final long id;
    private final NativeTransportKind transport;
    private final Set<FfmNativeConnection> activeChildren = ConcurrentHashMap.newKeySet();
    private final Queue<FfmNativeConnection> undeliveredChildren = new ConcurrentLinkedQueue<>();

    private volatile @Nullable NativeServerListener listener;
    private volatile boolean closed;

    FfmNativeServer(
            FfmNativeTransportBackend owner,
            long id,
            NativeTransportKind transport
    ) {
        this.owner = owner;
        this.id = id;
        this.transport = transport;
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
        ensureOpen();
        return owner.context().serverPort(id);
    }

    @Override
    public synchronized void setListener(NativeServerListener listener) {
        this.listener = listener;
        if (listener != null) {
            while (!undeliveredChildren.isEmpty()) {
                var child = undeliveredChildren.poll();
                if (child != null && activeChildren.contains(child)
                        && child.state() != NativeConnectionState.CLOSED
                        && child.state() != NativeConnectionState.FAILED) {
                    listener.onAccepted(child);
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        listener = null;

        try {
            owner.context().serverStop(id);
        } catch (RuntimeException e) {
            throw new NativeException("failed to stop native server " + id, e);
        } finally {
            undeliveredChildren.clear();
            activeChildren.clear();
            owner.unregisterServer(id);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new NativeException("NativeServer " + id + " is closed");
        }
    }

    synchronized void handleAccepted(FfmNativeConnection connection) {
        if (closed) {
            connection.close();
            return;
        }

        activeChildren.add(connection);
        var l = listener;
        if (l != null) {
            l.onAccepted(connection);
        } else {
            undeliveredChildren.add(connection);
        }
    }

    void removeChild(FfmNativeConnection connection) {
        activeChildren.remove(connection);
        undeliveredChildren.remove(connection);
    }

    void handleStateChanged(NativeServerState newState) {
        var l = listener;
        if (l != null) {
            l.onStateChanged(newState);
        }
    }

}
