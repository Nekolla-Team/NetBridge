package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.nativebridge.NativeIoResult;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;

/**
 * Direct shared-memory data plane for one native connection.
 *
 * <p>Maps the Rust-owned TX/RX rings into a Java {@link Arena}, exposes
 * single-writer/single-reader operations, and owns the {@link SharedIoLeaseGate} that guarantees
 * the Java view is never used after the native memory can be freed. The mapping itself never frees
 * native memory: the cleanup action is intentionally empty because Rust owns the allocation.
 */
final class FfmSharedConnectionIo implements AutoCloseable {

    /** Drain budget used when a connection closes while direct IO is in flight. */
    static final long DEFAULT_CLOSE_DRAIN_MILLIS = 10_000;

    private final FfmSharedIoRegion region;
    private final Arena arena;
    private final FfmSharedRing txRing;
    private final FfmSharedRing rxRing;
    private final FfmSharedRingWriter writer;
    private final FfmSharedRingReader reader;
    private final SharedIoLeaseGate gate = new SharedIoLeaseGate();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantLock readLock = new ReentrantLock();

    private FfmSharedConnectionIo(
            FfmSharedIoRegion region,
            Arena arena,
            FfmSharedRing txRing,
            FfmSharedRing rxRing,
            IntConsumer kick
    ) {
        super();
        this.region = region;
        this.arena = arena;
        this.txRing = txRing;
        this.rxRing = rxRing;
        this.writer = new FfmSharedRingWriter(txRing, kick);
        this.reader = new FfmSharedRingReader(rxRing, kick);
    }

    /**
     * Claims the connection for shared-direct access and maps both rings.
     *
     * <p>Any failure closes the half-built arena so a partially mapped connection cannot leak.
     */
    static FfmSharedConnectionIo map(
            FfmNativeContext context,
            long connectionId
    ) {
        var region = context.connectionIoRegion(connectionId);
        var arena = Arena.ofShared();
        var success = false;
        try {
            var tx = MemorySegment.ofAddress(region.txBase())
                    .reinterpret(region.txTotalBytes(), arena, null);
            var rx = MemorySegment.ofAddress(region.rxBase())
                    .reinterpret(region.rxTotalBytes(), arena, null);
            var txRing = FfmSharedRing.map(tx, region.txCapacity(), "tx");
            var rxRing = FfmSharedRing.map(rx, region.rxCapacity(), "rx");
            IntConsumer kick = flags -> {
                try {
                    context.connectionIoKick(connectionId, flags);
                } catch (RuntimeException _) {
                    // The kick is an edge-triggered optimization; if the context is already closing
                    // the transport task is on its way out, so a missed wakeup is harmless.
                }
            };
            var io = new FfmSharedConnectionIo(region, arena, txRing, rxRing, kick);
            success = true;
            return io;
        } finally {
            if (!success) {
                arena.close();
            }
        }
    }

    FfmSharedIoRegion region() {
        return region;
    }

    NativeIoResult write(ByteBuffer source) {
        if (!gate.begin()) {
            return NativeIoResult.CLOSED;
        }
        writeLock.lock();
        try {
            return writer.write(source);
        } finally {
            writeLock.unlock();
            gate.end();
        }
    }

    NativeIoResult read(ByteBuffer target) {
        if (!gate.begin()) {
            return NativeIoResult.CLOSED;
        }
        readLock.lock();
        try {
            return reader.read(target);
        } finally {
            readLock.unlock();
            gate.end();
        }
    }

    boolean isClosed() {
        return gate.isClosing() && gate.activeCount() == 0;
    }

    @Override
    public void close() {
        close(DEFAULT_CLOSE_DRAIN_MILLIS);
    }

    /**
     * Closes the Java view. Ordering matters: reject new direct IO, wait for in-flight operations,
     * then invalidate the segments. The caller must run this <em>before</em> the native connection
     * close so Rust can never free the region while Java is still copying.
     */
    void close(long drainTimeoutMillis) {
        if (!gate.beginClose()) {
            return;
        }
        gate.awaitDrain(drainTimeoutMillis);
        arena.close();
    }

    @Override
    public String toString() {
        return "FfmSharedConnectionIo[tx=" + txRing + ", rx=" + rxRing + "]";
    }

}
