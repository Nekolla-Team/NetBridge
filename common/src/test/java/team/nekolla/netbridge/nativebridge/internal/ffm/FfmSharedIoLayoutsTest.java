package team.nekolla.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java unit tests for the shared-ring layout and the single-writer/single-reader copies.
 *
 * <p>These run against a locally allocated region initialized by {@link FfmSharedIoLayouts}, so
 * they exercise the same byte contract as the Rust crate without needing the native library. The
 * cross-language contract itself is covered by the native integration tests.
 */
class FfmSharedIoLayoutsTest {

    private static final long CAPACITY = 64 * 1024;

    @Test
    void magicMatchesRustLittleEndianEncoding() {
        assertEquals(0x3130_474E_4952_424EL, FfmSharedIoLayouts.MAGIC);
    }

    @Test
    void waitsAndOffsetsMatchTheRustLayout() {
        assertEquals(192, FfmSharedIoLayouts.HEADER_BYTES);
        assertEquals(128, FfmSharedIoLayouts.ALIGNMENT);
        assertEquals(1L << 32, FfmSharedIoLayouts.LAYOUT_VERSION);
        assertEquals(0, FfmSharedIoLayouts.OFF_MAGIC);
        assertEquals(8, FfmSharedIoLayouts.OFF_LAYOUT_VERSION);
        assertEquals(16, FfmSharedIoLayouts.OFF_CAPACITY);
        assertEquals(24, FfmSharedIoLayouts.OFF_PRODUCER_WAIT);
        assertEquals(32, FfmSharedIoLayouts.OFF_CONSUMER_WAIT);
        assertEquals(64, FfmSharedIoLayouts.OFF_TAIL);
        assertEquals(128, FfmSharedIoLayouts.OFF_HEAD);
        assertEquals(192, FfmSharedIoLayouts.OFF_DATA);
        assertEquals(0, FfmSharedIoLayouts.WAIT_ACTIVE);
        assertEquals(1, FfmSharedIoLayouts.WAIT_PARKED);
        assertEquals(2, FfmSharedIoLayouts.WAIT_NOTIFIED);
    }

    @Test
    void mapAcceptsInitializedRegion() {
        try (var arena = Arena.ofShared()) {
            var segment = allocateRing(arena, CAPACITY);
            var ring = FfmSharedRing.map(
                    segment,
                    CAPACITY,
                    "test"
            );
            assertEquals(CAPACITY, ring.capacity());
        }
    }

    private static MemorySegment allocateRing(Arena arena, long capacity) {
        var segment = arena.allocate(
                FfmSharedIoLayouts.HEADER_BYTES + capacity,
                FfmSharedIoLayouts.ALIGNMENT
        );
        FfmSharedIoLayouts.initialize(segment, capacity);
        return segment;
    }

    @Test
    void mapRejectsMagicAndCapacityMismatch() {
        try (var arena = Arena.ofShared()) {
            var segment = allocateRing(arena, CAPACITY);
            FfmSharedIoLayouts.release(
                    segment,
                    FfmSharedIoLayouts.OFF_MAGIC,
                    0L
            );
            assertThrows(
                    IllegalStateException.class,
                    () -> FfmSharedRing.map(
                            segment,
                            CAPACITY,
                            "test"
                    )
            );
        }

        try (var arena = Arena.ofShared()) {
            var segment = allocateRing(arena, CAPACITY);
            assertThrows(
                    IllegalStateException.class,
                    () -> FfmSharedRing.map(
                            segment,
                            CAPACITY * 2,
                            "test"
                    )
            );
        }
    }

    @Test
    void writerAndReaderRoundTripAcrossManyWraps() {
        try (var arena = Arena.ofShared()) {
            var ring = FfmSharedRing.map(
                    allocateRing(arena, CAPACITY),
                    CAPACITY,
                    "test"
            );
            var txKicks = new AtomicInteger();
            var rxKicks = new AtomicInteger();
            var writer = new FfmSharedRingWriter(ring, _ -> txKicks.incrementAndGet());
            var reader = new FfmSharedRingReader(ring, _ -> rxKicks.incrementAndGet());

            var payload = pattern(200_000);
            var source = ByteBuffer.wrap(payload);
            var out = ByteBuffer.allocate(payload.length);

            var stallGuard = 0;
            while (out.position() < payload.length) {
                var wrote = writer.write(source);
                var read = reader.read(out);
                if (wrote.wouldBlock() && read.wouldBlock()) {
                    throw new IllegalStateException("both ends blocked; ring cannot make progress");
                }
                if (++stallGuard > 1_000_000) {
                    throw new IllegalStateException("roundtrip failed to converge");
                }
            }

            assertArrayEquals(payload, out.array());
            assertTrue(txKicks.get() > 0);
        }
    }

    private static byte[] pattern(int length) {
        var bytes = new byte[length];
        IntStream.range(0, length)
                .forEach(i ->
                        bytes[i] = (byte) ((i * 31 + 7) & 0xFF)
                );
        return bytes;
    }

    @Test
    void fullAndEmptyReportWouldBlockAndEdgeWakeupsFire() {
        try (var arena = Arena.ofShared()) {
            var ring = FfmSharedRing.map(
                    allocateRing(arena, CAPACITY),
                    CAPACITY,
                    "test"
            );
            var txKicks = new AtomicInteger();
            var rxKicks = new AtomicInteger();
            var writer = new FfmSharedRingWriter(ring, _ -> txKicks.incrementAndGet());
            var reader = new FfmSharedRingReader(ring, _ -> rxKicks.incrementAndGet());

            var filler = ByteBuffer.wrap(pattern((int) CAPACITY));
            assertEquals(
                    (int) CAPACITY,
                    writer.write(filler).bytes(),
                    "a single MAX_IO_CHUNK write must exactly fill the minimum ring"
            );

            // The transport consumer parked at initialization, so the first publish claims a wakeup.
            assertEquals(1, txKicks.get());

            var probe = ByteBuffer.wrap(new byte[1]);
            assertTrue(
                    writer.write(probe).wouldBlock(),
                    "full ring must report WOULD_BLOCK"
            );

            // Draining one byte frees space and claims the producer wakeup exactly once.
            var one = ByteBuffer.allocate(1);
            assertEquals(1, reader.read(one).bytes());
            assertEquals(1, rxKicks.get());

            assertTrue(
                    writer.write(probe).progressed(),
                    "space was freed, write must progress"
            );

            // Drain everything; the reader arms PARKED and reports WOULD_BLOCK.
            var sink = ByteBuffer.allocate((int) CAPACITY);
            while (reader.read(sink).progressed()) {
                sink.clear();
            }
            assertTrue(
                    reader.read(ByteBuffer.allocate(1)).wouldBlock(),
                    "empty ring must report WOULD_BLOCK"
            );

            var beforeTx = txKicks.get();
            assertTrue(writer.write(ByteBuffer.wrap(new byte[1])).progressed());
            assertEquals(
                    beforeTx + 1,
                    txKicks.get(),
                    "a parked consumer must be woken once"
            );
        }
    }

    @Test
    void partialReadConsumesExactlyReadableBytes() {
        try (var arena = Arena.ofShared()) {
            var ring = FfmSharedRing.map(
                    allocateRing(arena, CAPACITY),
                    CAPACITY,
                    "test"
            );
            var writer = new FfmSharedRingWriter(
                    ring,
                    _ -> {
                    }
            );
            var reader = new FfmSharedRingReader(
                    ring,
                    _ -> {
                    }
            );

            var payload = pattern(100);
            assertEquals(100, writer.write(ByteBuffer.wrap(payload)).bytes());

            var tiny = ByteBuffer.allocate(10);
            assertEquals(10, reader.read(tiny).bytes());
            assertArrayEquals(Arrays.copyOf(payload, 10), tiny.array());

            var rest = ByteBuffer.allocate(1000);
            assertEquals(90, reader.read(rest).bytes());
            assertTrue(reader.read(ByteBuffer.allocate(1)).wouldBlock());
        }
    }

    @Test
    void heapAndDirectSourcesProduceIdenticalBytes() {
        try (var arena = Arena.ofShared()) {
            var ring = FfmSharedRing.map(
                    allocateRing(arena, CAPACITY),
                    CAPACITY,
                    "test"
            );
            var writer = new FfmSharedRingWriter(
                    ring,
                    _ -> {
                    }
            );
            var reader = new FfmSharedRingReader(
                    ring,
                    _ -> {
                    }
            );

            var payload = pattern(1024);
            var direct = ByteBuffer.allocateDirect(payload.length);
            direct.put(payload).flip();

            assertEquals(payload.length, writer.write(direct).bytes());
            var out = ByteBuffer.allocate(payload.length);
            assertEquals(payload.length, reader.read(out).bytes());
            assertArrayEquals(payload, out.array());
        }
    }

    @Test
    void parkCancelsWhenConditionAlreadyCleared() {
        try (var arena = Arena.ofShared()) {
            var ring = FfmSharedRing.map(
                    allocateRing(arena, CAPACITY),
                    CAPACITY,
                    "test"
            );
            // Simulate a producer that arms the park after space already appeared.
            var slept = ring.parkProducer(() -> false);
            assertFalse(slept);
            assertFalse(
                    ring.notifyProducerIfParked(),
                    "no parked waiter should be claimable"
            );
        }
    }

}
