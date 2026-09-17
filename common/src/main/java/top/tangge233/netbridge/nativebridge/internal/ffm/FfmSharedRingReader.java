package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.nativebridge.NativeIoResult;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.function.IntConsumer;

/**
 * Java consumer half of a shared ring (transport -&gt; Java).
 *
 * <p>Single-reader: the owning {@link FfmSharedConnectionIo} serializes callers. The cursor and
 * the
 * wait-state protocol mirror the Rust consumer, so empty-ring parking and edge wakeups stay
 * coherent across the language boundary.
 */
public final class FfmSharedRingReader {

    private final FfmSharedRing ring;
    private final IntConsumer kick;
    private long localHead;

    public FfmSharedRingReader(
            FfmSharedRing ring,
            IntConsumer kick
    ) {
        super();
        this.ring = ring;
        this.kick = kick;
    }

    public NativeIoResult read(ByteBuffer target) {
        // Clear a pending NOTIFIED latched by the transport producer.
        ring.resumeConsumer();

        var capacity = target.remaining();
        if (capacity == 0) {
            return NativeIoResult.progressed(0);
        }

        var readable = readableBytes();
        if (readable == 0) {
            var shouldSleep = ring.parkConsumer(() -> readableBytes() == 0);
            if (shouldSleep) {
                // The ring stays parked until the transport producer wakes us.
                return NativeIoResult.WOULD_BLOCK;
            }
            readable = readableBytes();
            if (readable == 0) {
                return NativeIoResult.WOULD_BLOCK;
            }
        }

        var n = (int) Math.min(
                Math.min(capacity, readable),
                FfmSharedIoLayouts.MAX_IO_CHUNK
        );
        // ofBuffer models the buffer from its current position with size == remaining.
        var dst = MemorySegment.ofBuffer(target);
        ring.readInto(localHead, dst, 0, n);
        target.position(target.position() + n);
        localHead += n;

        if (ring.commitHead(localHead)) {
            kick.accept(FfmSharedIoRegion.KICK_RX_SPACE);
        }
        return NativeIoResult.progressed(n);
    }

    private long readableBytes() {
        var tail = ring.loadTailAcquire();
        return tail - localHead;
    }

}
