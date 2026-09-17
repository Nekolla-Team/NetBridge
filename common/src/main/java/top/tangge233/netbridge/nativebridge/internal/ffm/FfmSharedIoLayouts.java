package top.tangge233.netbridge.nativebridge.internal.ffm;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * Byte layout and atomic access helpers for a native-owned shared ring region.
 *
 * <p>Mirrors {@code net-bridge-shared-io::layout} on the Rust side. Panics are impossible here,
 * but
 * every constant must stay byte-for-byte compatible with the Rust crate; the cross-language layout
 * tests in this package guard the contract.
 */
public final class FfmSharedIoLayouts {

    /** Little-endian ASCII {@code NBRING01} as stored by Rust. */
    public static final long MAGIC = magic();
    public static final long LAYOUT_MAJOR = 1;
    public static final long LAYOUT_MINOR = 0;
    /** Encoded layout version ({@code major << 32 | minor}). */
    public static final long LAYOUT_VERSION = (LAYOUT_MAJOR << 32) | LAYOUT_MINOR;

    public static final long HEADER_BYTES = 192;
    public static final long ALIGNMENT = 128;
    public static final long MIN_CAPACITY = 64L * 1024;
    public static final long MAX_CAPACITY = 1L << 40;

    public static final long OFF_MAGIC = 0;
    public static final long OFF_LAYOUT_VERSION = 8;
    public static final long OFF_CAPACITY = 16;
    public static final long OFF_PRODUCER_WAIT = 24;
    public static final long OFF_CONSUMER_WAIT = 32;
    public static final long OFF_RESERVED0 = 40;
    public static final long OFF_RESERVED1 = 48;
    public static final long OFF_RESERVED2 = 56;
    public static final long OFF_TAIL = 64;
    public static final long OFF_HEAD = 128;
    public static final long OFF_DATA = HEADER_BYTES;

    public static final long WAIT_ACTIVE = 0;
    public static final long WAIT_PARKED = 1;
    public static final long WAIT_NOTIFIED = 2;

    /** Largest single public ring operation, matching the Rust {@code MAX_IO_CHUNK}. */
    public static final int MAX_IO_CHUNK = 64 * 1024;

    private static final VarHandle U64 = ValueLayout.JAVA_LONG.varHandle();

    private FfmSharedIoLayouts() {
        super();
    }

    /** Acquire load of a 64-bit header/ cursor slot. */
    public static long acquire(MemorySegment segment, long offset) {
        return (long) U64.getAcquire(segment, offset);
    }

    /** Release store of a 64-bit header/ cursor slot. */
    public static void release(MemorySegment segment, long offset, long value) {
        U64.setRelease(segment, offset, value);
    }

    /** Plain (opaque) load used for immutable header fields during mapping. */
    public static long load(MemorySegment segment, long offset) {
        return (long) U64.get(segment, offset);
    }

    /** Compare-and-set on a 64-bit slot. Returns whether the swap happened. */
    public static boolean compareAndSet(
            MemorySegment segment,
            long offset,
            long expected,
            long value
    ) {
        return U64.compareAndSet(segment, offset, expected, value);
    }

    /**
     * Writes the immutable header and initial wait states into a freshly allocated local region.
     * Production regions are initialized by Rust; this is used by tests and the primitive
     * benchmarks that exercise the Java ring in isolation.
     */
    public static void initialize(MemorySegment segment, long capacity) {
        U64.setVolatile(segment, OFF_MAGIC, MAGIC);
        U64.setVolatile(segment, OFF_LAYOUT_VERSION, LAYOUT_VERSION);
        U64.setVolatile(segment, OFF_CAPACITY, capacity);
        U64.setVolatile(segment, OFF_PRODUCER_WAIT, WAIT_ACTIVE);
        U64.setVolatile(segment, OFF_CONSUMER_WAIT, WAIT_PARKED);
        U64.setVolatile(segment, OFF_TAIL, 0L);
        U64.setVolatile(segment, OFF_HEAD, 0L);
        fence();
    }

    /**
     * Full fence. Used on both the parking and the notifying side of the wait-state protocol; the
     * store-load ordering it provides is what makes a lost wakeup impossible.
     */
    public static void fence() {
        VarHandle.fullFence();
    }

    private static long magic() {
        var bytes = "NBRING01".getBytes(StandardCharsets.US_ASCII);
        var value = 0L;
        // from_le_bytes: byte 0 is the least significant byte.
        for (var i = bytes.length - 1; i >= 0; i--) {
            value = (value << 8) | (bytes[i] & 0xFFL);
        }
        return value;
    }

}
