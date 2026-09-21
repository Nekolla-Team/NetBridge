package team.nekolla.netbridge.nativebridge.internal.ffm;

import java.lang.foreign.MemorySegment;
import java.util.function.BooleanSupplier;

/**
 * Java view over one native-owned shared ring region (header plus data area).
 *
 * <p>Cursor math and the wait-state protocol mirror {@code net-bridge-shared-io}; the two
 * languages
 * each implement their own side and cross-check the byte layout. Exactly one producer and one
 * consumer exist per ring, so this type deliberately does not synchronize cursor access itself.
 */
public final class FfmSharedRing {

    private final MemorySegment segment;
    private final long capacity;
    private final long mask;
    private final String name;

    private FfmSharedRing(
            MemorySegment segment,
            long capacity,
            String name
    ) {
        super();
        this.segment = segment;
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.name = name;
    }

    /**
     * Validates the region header against this build and returns a view.
     *
     * <p>The descriptor validation already happened on the native side; this re-checks the fields
     * the Java side actually depends on so a mismatched region can never be used silently.
     */
    public static FfmSharedRing map(
            MemorySegment segment,
            long capacity,
            String name
    ) {
        if (capacity < FfmSharedIoLayouts.MIN_CAPACITY) {
            throw new IllegalStateException(
                    "%s ring capacity below minimum: %d".formatted(name, capacity)
            );
        }
        if (Long.bitCount(capacity) != 1) {
            throw new IllegalStateException(
                    "%s ring capacity is not a power of two: %d".formatted(name, capacity)
            );
        }

        var magic = FfmSharedIoLayouts.load(segment, FfmSharedIoLayouts.OFF_MAGIC);
        if (magic != FfmSharedIoLayouts.MAGIC) {
            throw new IllegalStateException(
                    "%s ring magic mismatch: 0x%x".formatted(name, magic)
            );
        }

        var version = FfmSharedIoLayouts.load(segment, FfmSharedIoLayouts.OFF_LAYOUT_VERSION);
        if (version != FfmSharedIoLayouts.LAYOUT_VERSION) {
            throw new IllegalStateException(
                    "%s ring layout version mismatch: 0x%x".formatted(name, version)
            );
        }

        var headerCapacity = FfmSharedIoLayouts.load(segment, FfmSharedIoLayouts.OFF_CAPACITY);
        if (headerCapacity != capacity) {
            throw new IllegalStateException(
                    "%s ring capacity mismatch: header=%d descriptor=%d".formatted(
                            name,
                            headerCapacity,
                            capacity
                    )
            );
        }

        return new FfmSharedRing(segment, capacity, name);
    }

    public long capacity() {
        return capacity;
    }

    long loadHeadAcquire() {
        return FfmSharedIoLayouts.acquire(segment, FfmSharedIoLayouts.OFF_HEAD);
    }

    long loadTailAcquire() {
        return FfmSharedIoLayouts.acquire(segment, FfmSharedIoLayouts.OFF_TAIL);
    }

    /** Publishes a new tail value; returns whether the consumer must be woken. */
    boolean commitTail(long value) {
        FfmSharedIoLayouts.release(segment, FfmSharedIoLayouts.OFF_TAIL, value);
        return notifyConsumerIfParked();
    }

    boolean notifyConsumerIfParked() {
        FfmSharedIoLayouts.fence();
        return FfmSharedIoLayouts.compareAndSet(
                segment,
                FfmSharedIoLayouts.OFF_CONSUMER_WAIT,
                FfmSharedIoLayouts.WAIT_PARKED,
                FfmSharedIoLayouts.WAIT_NOTIFIED
        );
    }

    /** Publishes a new head value; returns whether the producer must be woken. */
    boolean commitHead(long value) {
        FfmSharedIoLayouts.release(segment, FfmSharedIoLayouts.OFF_HEAD, value);
        return notifyProducerIfParked();
    }

    boolean notifyProducerIfParked() {
        FfmSharedIoLayouts.fence();
        return FfmSharedIoLayouts.compareAndSet(
                segment,
                FfmSharedIoLayouts.OFF_PRODUCER_WAIT,
                FfmSharedIoLayouts.WAIT_PARKED,
                FfmSharedIoLayouts.WAIT_NOTIFIED
        );
    }

    boolean resumeProducer() {
        return FfmSharedIoLayouts.compareAndSet(
                segment,
                FfmSharedIoLayouts.OFF_PRODUCER_WAIT,
                FfmSharedIoLayouts.WAIT_NOTIFIED,
                FfmSharedIoLayouts.WAIT_ACTIVE
        );
    }

    boolean resumeConsumer() {
        return FfmSharedIoLayouts.compareAndSet(
                segment,
                FfmSharedIoLayouts.OFF_CONSUMER_WAIT,
                FfmSharedIoLayouts.WAIT_NOTIFIED,
                FfmSharedIoLayouts.WAIT_ACTIVE
        );
    }

    /** Arms the producer park and re-checks for space; returns whether the caller must sleep. */
    boolean parkProducer(BooleanSupplier stillWaiting) {
        FfmSharedIoLayouts.release(
                segment,
                FfmSharedIoLayouts.OFF_PRODUCER_WAIT,
                FfmSharedIoLayouts.WAIT_PARKED
        );
        FfmSharedIoLayouts.fence();
        if (stillWaiting.getAsBoolean()) {
            return true;
        }
        FfmSharedIoLayouts.release(
                segment,
                FfmSharedIoLayouts.OFF_PRODUCER_WAIT,
                FfmSharedIoLayouts.WAIT_ACTIVE
        );
        return false;
    }

    /** Arms the consumer park and re-checks for data; returns whether the caller must sleep. */
    boolean parkConsumer(BooleanSupplier stillWaiting) {
        FfmSharedIoLayouts.release(
                segment,
                FfmSharedIoLayouts.OFF_CONSUMER_WAIT,
                FfmSharedIoLayouts.WAIT_PARKED
        );
        FfmSharedIoLayouts.fence();
        if (stillWaiting.getAsBoolean()) {
            return true;
        }
        FfmSharedIoLayouts.release(
                segment,
                FfmSharedIoLayouts.OFF_CONSUMER_WAIT,
                FfmSharedIoLayouts.WAIT_ACTIVE
        );
        return false;
    }

    /** Copies {@code length} bytes from {@code src} into the ring at the absolute {@code cursor}. */
    void writeFrom(
            long cursor,
            MemorySegment src,
            long srcOffset,
            int length
    ) {
        var offset = cursor & mask;
        var first = (int) Math.min(length, capacity - offset);
        MemorySegment.copy(
                src,
                srcOffset,
                segment,
                FfmSharedIoLayouts.OFF_DATA + offset,
                first
        );
        if (first < length) {
            MemorySegment.copy(
                    src,
                    srcOffset + first,
                    segment,
                    FfmSharedIoLayouts.OFF_DATA,
                    length - first
            );
        }
    }

    /** Copies {@code length} bytes out of the ring at the absolute {@code cursor} into {@code dst}. */
    void readInto(
            long cursor,
            MemorySegment dst,
            long dstOffset,
            int length
    ) {
        var offset = cursor & mask;
        var first = (int) Math.min(length, capacity - offset);
        MemorySegment.copy(
                segment,
                FfmSharedIoLayouts.OFF_DATA + offset,
                dst,
                dstOffset,
                first
        );
        if (first < length) {
            MemorySegment.copy(
                    segment,
                    FfmSharedIoLayouts.OFF_DATA,
                    dst,
                    dstOffset + first,
                    length - first
            );
        }
    }

    @Override
    public String toString() {
        return "FfmSharedRing[" + name + ", capacity=" + capacity + "]";
    }

}
