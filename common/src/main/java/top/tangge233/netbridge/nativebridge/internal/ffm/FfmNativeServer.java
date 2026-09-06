package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.nativebridge.*;

import java.util.ArrayList;
import java.util.List;
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
    private volatile LifecycleState lifecycle = LifecycleState.OPEN;

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
    public void setListener(NativeServerListener listener) {
        List<FfmNativeConnection> toDeliver = new ArrayList<>();
        synchronized (this) {
            this.listener = listener;
            if (listener != null) {
                while (!undeliveredChildren.isEmpty()) {
                    var child = undeliveredChildren.poll();
                    if (child != null) {
                        toDeliver.add(child);
                    }
                }
            }
        }

        if (listener != null) {
            for (var child : toDeliver) {
                if (activeChildren.contains(child)
                        && child.state() != NativeConnectionState.CLOSED
                        && child.state() != NativeConnectionState.FAILED) {
                    listener.onAccepted(child);
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (lifecycle == LifecycleState.CLOSED) {
                return;
            }
            lifecycle = LifecycleState.CLOSING;
        }

        try {
            owner.context().serverStop(id);
            synchronized (this) {
                lifecycle = LifecycleState.CLOSED;
                listener = null;
            }
            owner.unregisterServer(id);
        } catch (Throwable t) {
            if (t instanceof NativeException ne
                    && ne.statusCode() == FfmStatus.NB_NOT_FOUND
            ) {
                synchronized (this) {
                    lifecycle = LifecycleState.CLOSED;
                    listener = null;
                }
                owner.unregisterServer(id);
                return;
            }

            synchronized (this) {
                lifecycle = LifecycleState.CLOSE_FAILED;
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new NativeException("failed to stop native server " + id, t);
        }
    }

    private void ensureOpen() {
        if (lifecycle == LifecycleState.CLOSED) {
            throw new NativeException("NativeServer " + id + " is closed");
        }
    }

    public LifecycleState lifecycle() {
        return lifecycle;
    }

    void handleAccepted(FfmNativeConnection connection) {
        boolean shouldClose;
        NativeServerListener l;
        synchronized (this) {
            if (lifecycle == LifecycleState.CLOSED) {
                shouldClose = true;
                l = null;
            } else {
                shouldClose = false;
                activeChildren.add(connection);
                l = listener;
                if (l == null) {
                    undeliveredChildren.add(connection);
                }
            }
        }

        if (shouldClose) {
            connection.close();
            return;
        }

        if (l != null) {
            l.onAccepted(connection);
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

    public enum LifecycleState {

        OPEN,
        CLOSING,
        CLOSED,
        CLOSE_FAILED

    }

}
