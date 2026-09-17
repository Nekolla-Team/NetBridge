package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.config.SharedIoMode;
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
    private final SharedIoMode sharedIoMode;

    private final Object dataPlaneLock = new Object();
    private volatile DataPlane dataPlaneState = DataPlane.UNINITIALIZED;
    private volatile @Nullable FfmSharedConnectionIo directPlane;

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
        super();
        this.owner = owner;
        this.id = id;
        this.ownerServer = ownerServer;
        this.transport = transport;
        this.sharedIoMode = owner.sharedIoMode();
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
        var plane = ensureDataPlane();
        if (plane != null) {
            var result = plane.write(source);
            if (result.closed()) {
                markClosedByQuery();
            }
            return result;
        }
        if (dataPlaneState != DataPlane.LEGACY) {
            return NativeIoResult.CLOSED;
        }
        return writeLegacy(source);
    }

    @Override
    public NativeIoResult read(ByteBuffer target) {
        ensureOpen();
        var plane = ensureDataPlane();
        if (plane != null) {
            var result = plane.read(target);
            if (result.closed()) {
                markClosedByQuery();
            }
            return result;
        }
        if (dataPlaneState != DataPlane.LEGACY) {
            return NativeIoResult.CLOSED;
        }
        return readLegacy(target);
    }

    private NativeIoResult readLegacy(ByteBuffer target) {
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

        // Invalidate the Java-side mapping before the native close can drop the Rust-owned region.
        try {
            releaseDataPlane(true);
        } catch (RuntimeException e) {
            synchronized (this) {
                lifecycle = LifecycleState.CLOSE_FAILED;
            }
            throw e;
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

    /**
     * Selects and lazily maps the data plane on the first data operation.
     *
     * <p>The mapping deliberately does not happen in the constructor: an ACCEPTED event can be
     * delivered on a Rust upcall thread, and re-entering native code there would be an avoidable
     * FFI reentrancy hazard.
     */
    private @Nullable FfmSharedConnectionIo ensureDataPlane() {
        var plane = directPlane;
        if (plane != null) {
            return plane;
        }

        synchronized (dataPlaneLock) {
            if (directPlane != null) {
                return directPlane;
            }

            if (dataPlaneState == DataPlane.DIRECT
                    || dataPlaneState == DataPlane.CLOSED
                    || dataPlaneState == DataPlane.LEGACY
            ) {
                return null;
            }

            if (sharedIoMode == SharedIoMode.OFF) {
                dataPlaneState = DataPlane.LEGACY;
                return null;
            }

            if (lifecycle != LifecycleState.OPEN) {
                dataPlaneState = DataPlane.CLOSED;
                return null;
            }

            if (!owner.context().supportsSharedRingIo()) {
                if (sharedIoMode == SharedIoMode.ON) {
                    throw new NativeException(
                            "Shared-ring IO was requested (netbridge.native.sharedIo=on) but the native backend does not advertise support"
                    );
                }
                dataPlaneState = DataPlane.LEGACY;
                return null;
            }

            var mapped = FfmSharedConnectionIo.map(owner.context(), id);
            directPlane = mapped;
            dataPlaneState = DataPlane.DIRECT;
            return mapped;
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
        releaseDataPlane(false);
        if (l != null
                && prev != NativeConnectionState.CLOSED
        ) {
            l.onStateChanged(NativeConnectionState.CLOSED, reason);
        }
    }

    private NativeIoResult writeLegacy(ByteBuffer source) {
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

    private void releaseServerOwnership() {
        if (ownerServer != null) {
            ownerServer.removeChild(this);
        }
    }

    /**
     * Invalidates the Java-side mapping exactly once. Runs before {@code connectionClose} (or on a
     * terminal event) so the Rust-owned region can never be freed while Java may still copy.
     */
    private void releaseDataPlane(boolean propagateFailure) {
        FfmSharedConnectionIo plane;
        synchronized (dataPlaneLock) {
            plane = directPlane;
            directPlane = null;
            dataPlaneState = DataPlane.CLOSED;
        }
        if (plane == null) {
            return;
        }
        try {
            plane.close(FfmSharedConnectionIo.DEFAULT_CLOSE_DRAIN_MILLIS);
        } catch (RuntimeException e) {
            if (propagateFailure) {
                throw e;
            }
        }
    }

    private void ensureOpen() {
        if (lifecycle == LifecycleState.CLOSED) {
            throw new NativeException("NativeConnection " + id + " is closed");
        }
    }

    /** True when the direct shared-ring data plane was selected for this connection. */
    boolean directDataPlaneActive() {
        return dataPlaneState == DataPlane.DIRECT;
    }

    /** The mapped region of the direct data plane, or {@code null} when it is not active. */
    @Nullable FfmSharedIoRegion directRegion() {
        var plane = directPlane;
        return plane == null
                ? null
                : plane.region();
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
            releaseDataPlane(false);
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

    /**
     * Lazily resolved data-plane selection for this connection.
     *
     * <p>{@code UNINITIALIZED} is resolved on the first data operation; {@code CLOSED} permanently
     * rejects further data operations.
     */
    private enum DataPlane {

        UNINITIALIZED,
        DIRECT,
        LEGACY,
        CLOSED

    }

}
