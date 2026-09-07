package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.nativebridge.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

public final class FfmNativeConnection implements NativeConnection {

    private final FfmNativeTransportBackend owner;
    private final long id;
    private final @Nullable FfmNativeServer ownerServer;
    private final NativeTransportKind transport;

    private volatile NativeConnectionState state;
    private volatile NativeFailureReason failureReason = NativeFailureReason.GENERIC;
    private volatile @Nullable NativeConnectionListener listener;
    private volatile LifecycleState lifecycle = LifecycleState.OPEN;

    FfmNativeConnection(
            FfmNativeTransportBackend owner,
            long id,
            @Nullable FfmNativeServer ownerServer,
            NativeTransportKind transport,
            NativeConnectionState initialState
    ) {
        this.owner = owner;
        this.id = id;
        this.ownerServer = ownerServer;
        this.transport = transport;
        this.state = initialState;
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
        var st = state;
        if (lifecycle == LifecycleState.CLOSED) {
            return NativeConnectionState.CLOSED;
        }

        if (st == NativeConnectionState.CONNECTING) {
            var abi = owner.context().connectionState(id);
            if (abi == -1) {
                markClosedByQuery();
                return NativeConnectionState.CLOSED;
            }

            var mapped = FfmAbiCodec.connectionStateFromAbi(abi);
            state = mapped;
            return mapped;
        }

        return st;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        ensureOpen();
        try {
            return owner.context().connectionRemoteAddress(id);
        } catch (RuntimeException e) {
            throw new NativeException("remote address unavailable for connection " + id, e);
        }
    }

    @Override
    public NativeIoResult write(ByteBuffer source) {
        ensureOpen();
        var remaining = source.remaining();

        if (remaining == 0) {
            return NativeIoResult.progressed(0);
        }
        var toWrite = Math.min(remaining, 65536);

        if (source.isDirect()) {
            var directSlice = source.slice();
            directSlice.limit(toWrite);
            var segment = MemorySegment.ofBuffer(directSlice);
            var result = owner.context().connectionWrite(id, segment, toWrite);

            if (result.closed()) {
                markClosedByQuery();
                return NativeIoResult.CLOSED;
            }

            if (result.wouldBlock()) {
                return NativeIoResult.WOULD_BLOCK;
            }

            var n = result.bytes();
            source.position(source.position() + n);
            return NativeIoResult.progressed(n);
        }

        try (var arena = Arena.ofConfined()) {
            var directSlice = source.slice();
            directSlice.limit(toWrite);
            var segment = arena.allocate(toWrite, 1);
            segment.copyFrom(MemorySegment.ofBuffer(directSlice));
            var result = owner.context().connectionWrite(id, segment, toWrite);

            if (result.closed()) {
                markClosedByQuery();
                return NativeIoResult.CLOSED;
            }

            if (result.wouldBlock()) {
                return NativeIoResult.WOULD_BLOCK;
            }

            var n = result.bytes();
            source.position(source.position() + n);
            return NativeIoResult.progressed(n);
        }
    }

    @Override
    public NativeIoResult read(ByteBuffer target) {
        ensureOpen();
        var capacity = target.remaining();
        if (capacity == 0) {
            return NativeIoResult.progressed(0);
        }
        var toRead = Math.min(capacity, 65536);

        if (target.isDirect()) {
            var directSlice = target.slice();
            directSlice.limit(toRead);
            var segment = MemorySegment.ofBuffer(directSlice);
            var result = owner.context().connectionRead(id, segment, toRead);

            if (result.closed()) {
                markClosedByQuery();
                return NativeIoResult.CLOSED;
            }

            if (result.wouldBlock()) {
                return NativeIoResult.WOULD_BLOCK;
            }

            var n = result.bytes();
            if (n == 0) {
                return NativeIoResult.WOULD_BLOCK;
            }

            target.position(target.position() + n);
            return NativeIoResult.progressed(n);
        }

        try (var arena = Arena.ofConfined()) {
            var segment = arena.allocate(toRead, 1);
            var result = owner.context().connectionRead(id, segment, toRead);

            if (result.closed()) {
                markClosedByQuery();
                return NativeIoResult.CLOSED;
            }

            if (result.wouldBlock()) {
                return NativeIoResult.WOULD_BLOCK;
            }

            var n = result.bytes();
            if (n == 0) {
                return NativeIoResult.WOULD_BLOCK;
            }

            var bytes = new byte[n];
            MemorySegment.copy(
                    segment,
                    0,
                    MemorySegment.ofArray(bytes),
                    0,
                    n
            );
            target.put(bytes, 0, n);
            return NativeIoResult.progressed(n);
        }
    }

    @Override
    public void setListener(NativeConnectionListener listener) {
        NativeConnectionState st;
        NativeFailureReason reason;
        synchronized (this) {
            this.listener = listener;
            st = state();
            reason = failureReason;
        }

        if (st != NativeConnectionState.CONNECTING) {
            listener.onStateChanged(st, reason);
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
            owner.context().connectionClose(id);
            synchronized (this) {
                lifecycle = LifecycleState.CLOSED;
                state = NativeConnectionState.CLOSED;
                listener = null;
            }
            owner.unregisterConnection(id);
            releaseServerOwnership();
        } catch (Throwable t) {
            if (t instanceof NativeException ne
                    && ne.statusCode() == FfmStatus.NB_NOT_FOUND
            ) {
                synchronized (this) {
                    lifecycle = LifecycleState.CLOSED;
                    state = NativeConnectionState.CLOSED;
                    listener = null;
                }
                owner.unregisterConnection(id);
                releaseServerOwnership();
                return;
            }

            synchronized (this) {
                lifecycle = LifecycleState.CLOSE_FAILED;
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new NativeException("failed to close native connection " + id, t);
        }
    }

    private void markClosedByQuery() {
        NativeConnectionListener l;
        NativeConnectionState prev;
        NativeFailureReason reason;
        synchronized (this) {
            prev = state;
            state = NativeConnectionState.CLOSED;
            lifecycle = LifecycleState.CLOSED;
            l = listener;
            reason = failureReason;
        }
        owner.unregisterConnection(id);
        releaseServerOwnership();
        if (l != null
                && prev != NativeConnectionState.CLOSED
        ) {
            l.onStateChanged(NativeConnectionState.CLOSED, reason);
        }
    }

    private void releaseServerOwnership() {
        if (ownerServer != null) {
            ownerServer.removeChild(this);
        }
    }

    private void ensureOpen() {
        if (lifecycle == LifecycleState.CLOSED) {
            throw new NativeException("NativeConnection " + id + " is closed");
        }
    }

    public NativeFailureReason failureReason() {
        return failureReason;
    }

    public LifecycleState lifecycle() {
        return lifecycle;
    }

    void handleStateChanged(NativeConnectionState newState) {
        handleStateChanged(newState, NativeFailureReason.GENERIC);
    }

    void handleStateChanged(
            NativeConnectionState newState,
            NativeFailureReason reason
    ) {
        NativeConnectionListener l;
        synchronized (this) {
            var prev = state;
            if (prev == newState) {
                return;
            }

            this.failureReason = reason;
            this.state = newState;
            l = listener;
        }

        if (l != null) {
            l.onStateChanged(newState, reason);
        }

        if (newState == NativeConnectionState.CLOSED
                || newState == NativeConnectionState.FAILED
        ) {
            synchronized (this) {
                lifecycle = LifecycleState.CLOSED;
            }
            owner.unregisterConnection(id);
            releaseServerOwnership();
        }
    }

    void handleDataAvailable() {
        var l = listener;
        if (l != null && state != NativeConnectionState.CLOSED
                && state != NativeConnectionState.FAILED
        ) {
            l.onDataAvailable();
        }
    }

    void handleWritable() {
        var l = listener;
        if (l != null && state != NativeConnectionState.CLOSED
                && state != NativeConnectionState.FAILED
        ) {
            l.onWritable();
        }
    }

    public enum LifecycleState {

        OPEN,
        CLOSING,
        CLOSED,
        CLOSE_FAILED

    }

}
