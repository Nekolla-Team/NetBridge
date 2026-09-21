package team.nekolla.netbridge.nativebridge.internal.ffm;

import team.nekolla.netbridge.nativebridge.NativeIoResult;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.function.IntConsumer;

/**
 * Java producer half of a shared ring (Java -&gt; transport).
 *
 * <p>Single-writer: the owning {@link FfmSharedConnectionIo} serializes callers. The cursor and
 * the
 * wait-state protocol mirror the Rust producer, so backpressure and edge wakeups stay coherent
 * across the language boundary.
 */
public final class FfmSharedRingWriter {

    private final FfmSharedRing ring;
    private final IntConsumer kick;
    private long localTail;

    public FfmSharedRingWriter(
            FfmSharedRing ring,
            IntConsumer kick
    ) {
        super();
        this.ring = ring;
        this.kick = kick;
    }

    public NativeIoResult write(ByteBuffer source) {
        // Clear a pending NOTIFIED latched by the transport consumer.
        ring.resumeProducer();

        var remaining = Math.min(source.remaining(), FfmSharedIoLayouts.MAX_IO_CHUNK);
        if (remaining == 0) {
            return NativeIoResult.progressed(0);
        }

        var free = freeBytes();
        if (free == 0) {
            var shouldSleep = ring.parkProducer(() -> freeBytes() == 0);
            if (shouldSleep) {
                // The ring stays parked until the transport consumer wakes us.
                return NativeIoResult.WOULD_BLOCK;
            }
            free = freeBytes();
            if (free == 0) {
                return NativeIoResult.WOULD_BLOCK;
            }
        }

        var n = (int) Math.min(remaining, free);
        // ofBuffer models the buffer from its current position with size == remaining.
        var src = MemorySegment.ofBuffer(source);
        ring.writeFrom(localTail, src, 0, n);
        source.position(source.position() + n);
        localTail += n;

        if (ring.commitTail(localTail)) {
            kick.accept(FfmSharedIoRegion.KICK_TX_DATA);
        }
        return NativeIoResult.progressed(n);
    }

    private long freeBytes() {
        var head = ring.loadHeadAcquire();
        var used = localTail - head;
        return ring.capacity() - used;
    }

}
