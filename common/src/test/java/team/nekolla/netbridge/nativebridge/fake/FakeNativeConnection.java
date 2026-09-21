package team.nekolla.netbridge.nativebridge.fake;

import team.nekolla.netbridge.nativebridge.*;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

public final class FakeNativeConnection implements NativeConnection {

    private final FakeNativeTransportBackend owner;
    private final long id;
    private final NativeTransportKind transport;
    private final Object monitor = new Object();
    private final ByteArrayOutputStream inbound = new ByteArrayOutputStream();
    private volatile NativeConnectionState state;
    private volatile @Nullable NativeConnectionListener listener;
    private volatile boolean closed;
    private volatile int outboundCapacity = Integer.MAX_VALUE;
    private volatile boolean writerBlocked;

    private volatile @Nullable FakeNativeConnection peer;
    private volatile @Nullable InetSocketAddress remoteAddress;

    private FakeNativeConnection(
            FakeNativeTransportBackend owner,
            long id,
            NativeTransportKind transport,
            NativeConnectionState initialState
    ) {
        this.owner = owner;
        this.id = id;
        this.transport = transport;
        this.state = initialState;
    }

    static FakeNativeConnection clientSide(
            FakeNativeTransportBackend owner,
            long id,
            NativeTransportKind kind
    ) {
        return new FakeNativeConnection(owner, id, kind, NativeConnectionState.CONNECTING);
    }

    static FakeNativeConnection serverSide(
            FakeNativeTransportBackend owner,
            long id,
            NativeTransportKind kind
    ) {
        return new FakeNativeConnection(owner, id, kind, NativeConnectionState.CONNECTED);
    }

    void bindPeer(FakeNativeConnection peer) {
        this.peer = peer;
        this.remoteAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
    }

    void assignPeerState(NativeConnectionState state) {
        this.state = state;
    }

    public void simulateConnected() {
        synchronized (monitor) {
            if (state == NativeConnectionState.CONNECTED) {
                return;
            }
            state = NativeConnectionState.CONNECTED;
        }
        fireStateChanged(NativeConnectionState.CONNECTED);
    }

    void fireStateChanged(NativeConnectionState newState) {
        var l = listener;
        if (l != null) {
            l.onStateChanged(newState);
        }
    }

    public void setOutboundCapacity(int bytes) {
        this.outboundCapacity = bytes;
    }

    public boolean writerBlocked() {
        return writerBlocked;
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
    public NativeConnectionState state() {
        return state;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        var addr = remoteAddress;
        if (addr == null) {
            throw new NativeException("remote address not yet resolved");
        }
        return addr;
    }

    @Override
    public NativeIoResult write(ByteBuffer source) {
        ensureOpen();
        var remaining = source.remaining();
        if (remaining == 0) {
            return NativeIoResult.progressed(0);
        }

        var target = peer;
        if (target == null) {
            throw new NativeException("fake connection has no peer");
        }

        synchronized (target.monitor) {
            if (target.inbound.size() + remaining > outboundCapacity) {
                writerBlocked = true;
                return NativeIoResult.WOULD_BLOCK;
            }

            var bytes = new byte[remaining];
            source.get(bytes);
            target.inbound.write(bytes, 0, remaining);
            writerBlocked = false;
        }

        target.signalDataAvailable();
        return NativeIoResult.progressed(remaining);
    }

    @Override
    public NativeIoResult read(ByteBuffer target) {
        ensureOpen();
        byte[] available;
        synchronized (monitor) {
            var size = inbound.size();
            if (size == 0) {
                return NativeIoResult.WOULD_BLOCK;
            }

            var n = Math.min(size, target.remaining());
            available = Arrays.copyOfRange(inbound.toByteArray(), 0, n);
            var rest = Arrays.copyOfRange(inbound.toByteArray(), n, size);
            inbound.reset();
            inbound.writeBytes(rest);
        }
        target.put(available);
        var writer = peer;
        if (writer != null && writer.writerBlocked) {
            var writerMonitor = writer;
            writerMonitor.writerBlocked = false;
            writer.fireWritable();
        }
        return NativeIoResult.progressed(available.length);
    }

    void fireWritable() {
        var l = listener;
        if (l != null) {
            l.onWritable();
        }
    }

    @Override
    public void setListener(NativeConnectionListener listener) {
        this.listener = listener;
        var current = state();
        if (current != NativeConnectionState.CONNECTING) {
            listener.onStateChanged(current);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        listener = null;
        owner.unregisterConnection(id);
        if (peer != null) {
            peer.closed = true;
            peer.listener = null;
            owner.unregisterConnection(peer.id);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new NativeException("fake connection " + id + " is closed");
        }
    }

    void signalDataAvailable() {
        var l = listener;
        if (l != null) {
            l.onDataAvailable();
        }
    }

}
