package team.nekolla.netbridge.benchmark.bridge;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import team.nekolla.netbridge.nativebridge.internal.ffm.FfmSharedIoLayouts;
import team.nekolla.netbridge.nativebridge.internal.ffm.FfmSharedRing;
import team.nekolla.netbridge.nativebridge.internal.ffm.FfmSharedRingReader;
import team.nekolla.netbridge.nativebridge.internal.ffm.FfmSharedRingWriter;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.Nullable;

/**
 * B0: raw Java shared-ring primitive cost, without any native transport.
 *
 * <p>The ring is a locally allocated region initialized by {@link FfmSharedIoLayouts}; a daemon
 * peer
 * thread drains or feeds it so the measured side stays on the progress path. The full/empty
 * variants deliberately measure the fast {@code WOULD_BLOCK} path instead.
 */
@SuppressWarnings("NullAway")
public class SharedIoPrimitiveBenchmark {

    private static final long CAPACITY = 256 * 1024;

    @Benchmark
    public void writeDrained(
            WriteState s,
            IoOutcome counters,
            Blackhole bh
    ) {
        s.source.clear();
        var result = s.writer.write(s.source);
        bh.consume(result);
        if (result.progressed()) {
            counters.successOps++;
        } else {
            counters.wouldBlockOps++;
        }
    }

    @Benchmark
    public void readFed(
            ReadState s,
            IoOutcome counters,
            Blackhole bh
    ) {
        s.sink.clear();
        var result = s.reader.read(s.sink);
        bh.consume(result);
        if (result.progressed()) {
            counters.successOps++;
        } else {
            counters.wouldBlockOps++;
        }
    }

    @Benchmark
    public void writeFullWouldBlock(
            FullState s,
            IoOutcome counters,
            Blackhole bh
    ) {
        s.source.clear();
        var result = s.writer.write(s.source);
        if (!result.wouldBlock()) {
            throw new IllegalStateException("full ring must report WOULD_BLOCK: " + result);
        }
        bh.consume(result);
        counters.wouldBlockOps++;
    }

    @Benchmark
    public void readEmptyWouldBlock(
            EmptyState s,
            IoOutcome counters,
            Blackhole bh
    ) {
        s.sink.clear();
        var result = s.reader.read(s.sink);
        if (!result.wouldBlock()) {
            throw new IllegalStateException("empty ring must report WOULD_BLOCK: " + result);
        }
        bh.consume(result);
        counters.wouldBlockOps++;
    }

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class IoOutcome {

        public long successOps;
        public long wouldBlockOps;

    }

    @State(Scope.Benchmark)
    @SuppressWarnings("NotNullFieldNotInitialized")
    public abstract static class BaseState {

        @Param({"64", "1024", "65536"}) int size;
        Arena arena;
        FfmSharedRing ring;
        FfmSharedRingWriter writer;
        FfmSharedRingReader reader;
        volatile boolean running;
        @Nullable Thread peer;

        @Setup(Level.Trial)
        public void setUp() {
            arena = Arena.ofShared();
            var segment = arena.allocate(
                    FfmSharedIoLayouts.HEADER_BYTES + CAPACITY,
                    FfmSharedIoLayouts.ALIGNMENT
            );
            FfmSharedIoLayouts.initialize(segment, CAPACITY);
            ring = FfmSharedRing.map(segment, CAPACITY, "primitive");
            writer = new FfmSharedRingWriter(
                    ring, flags -> {
            }
            );
            reader = new FfmSharedRingReader(
                    ring, flags -> {
            }
            );
            prepare();
            running = true;
            peer = startPeer();
        }

        /** Subclass hook for buffers that must exist before the peer thread starts. */
        void prepare() {
        }

        abstract @Nullable Thread startPeer();

        @TearDown(Level.Trial)
        public void tearDown() {
            running = false;
            var thread = peer;
            if (thread != null) {
                thread.interrupt();
            }
            arena.close();
        }

    }

    public static class WriteState extends BaseState {

        ByteBuffer source;

        @Override
        void prepare() {
            source = ByteBuffer.allocateDirect(size);
        }

        @Override
        Thread startPeer() {
            var thread = new Thread(
                    () -> {
                        var sink = ByteBuffer.allocateDirect((int) CAPACITY);
                        while (running) {
                            sink.clear();
                            if (reader.read(sink).wouldBlock()) {
                                LockSupport.parkNanos(500);
                            }
                        }
                    },
                    "nb-primitive-drain"
            );
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

    }

    public static class ReadState extends BaseState {

        ByteBuffer sink;

        @Override
        void prepare() {
            sink = ByteBuffer.allocateDirect(size);
        }

        @Override
        Thread startPeer() {
            var chunk = ByteBuffer.allocateDirect(4096);
            var thread = new Thread(
                    () -> {
                        while (running) {
                            chunk.clear();
                            if (writer.write(chunk).wouldBlock()) {
                                LockSupport.parkNanos(500);
                            }
                        }
                    },
                    "nb-primitive-feed"
            );
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

    }

    public static class FullState extends BaseState {

        ByteBuffer source;

        @Override
        void prepare() {
            source = ByteBuffer.allocateDirect(size);
            var filler = ByteBuffer.allocateDirect((int) CAPACITY);
            while (filler.hasRemaining()) {
                writer.write(filler);
            }
        }

        @Override
        @Nullable Thread startPeer() {
            return null;
        }

    }

    public static class EmptyState extends BaseState {

        ByteBuffer sink;

        @Override
        void prepare() {
            sink = ByteBuffer.allocateDirect(size);
        }

        @Override
        @Nullable Thread startPeer() {
            return null;
        }

    }

}
