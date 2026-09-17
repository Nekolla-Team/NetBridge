package top.tangge233.netbridge.benchmark.bridge;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;

/**
 * L1 data-plane matrix over the production bridge: direct/heap buffers against QUIC loopback,
 * measuring real progress and WOULD_BLOCK behaviour.
 *
 * <p>Every benchmark asserts its expected outcome, so a run that would silently measure the
 * wrong event (a would-block reported as a success, or a zero-byte success) fails loudly instead of
 * producing a misleading number.
 */
@SuppressWarnings("NullAway")
public class NativeIoBenchmark {

    @Benchmark
    public void writeDirectSuccess(
            WriteState s,
            IoCounters counters,
            Blackhole bh
    ) {
        bh.consume(writeSuccess(s.direct, s.fixture, "writeDirectSuccess"));
        counters.successOps++;
    }

    private static int writeSuccess(
            ByteBuffer buffer,
            NativeBridgeFixture fixture,
            String label
    ) {
        buffer.clear();
        var result = fixture.clientConnection().write(buffer);
        if (!result.progressed() || result.bytes() <= 0) {
            throw new IllegalStateException(label + " expected a write progress, got " + result);
        }
        return result.bytes();
    }

    @Benchmark
    public void writeHeapSuccess(
            WriteState s,
            IoCounters counters,
            Blackhole bh
    ) {
        bh.consume(writeSuccess(s.heap, s.fixture, "writeHeapSuccess"));
        counters.successOps++;
    }

    @Benchmark
    public void writeDirectWouldBlock(
            BlockWriteState s,
            IoCounters counters,
            Blackhole bh
    ) {
        bh.consume(writeWouldBlock(s.direct, s.fixture, "writeDirectWouldBlock"));
        counters.wouldBlockOps++;
    }

    private static int writeWouldBlock(
            ByteBuffer buffer,
            NativeBridgeFixture fixture,
            String label
    ) {
        buffer.clear();
        var result = fixture.clientConnection().write(buffer);
        if (result.progressed()) {
            throw new IllegalStateException(label + " expected WOULD_BLOCK, got " + result);
        }
        return 0;
    }

    @Benchmark
    public void writeHeapWouldBlock(
            BlockWriteState s,
            IoCounters counters,
            Blackhole bh
    ) {
        bh.consume(writeWouldBlock(s.heap, s.fixture, "writeHeapWouldBlock"));
        counters.wouldBlockOps++;
    }

    @Benchmark
    public void readDirectSuccess(
            ReadState s,
            IoCounters counters,
            Blackhole bh
    ) {
        bh.consume(readSuccess(s.direct, s.fixture, "readDirectSuccess"));
        counters.successOps++;
    }

    private static int readSuccess(
            ByteBuffer buffer,
            NativeBridgeFixture fixture,
            String label
    ) {
        buffer.clear();
        var result = fixture.clientConnection().read(buffer);
        if (!result.progressed() || result.bytes() <= 0) {
            throw new IllegalStateException(label + " expected a read progress, got " + result);
        }
        return result.bytes();
    }

    @Benchmark
    public void readHeapSuccess(
            ReadState s,
            IoCounters counters,
            Blackhole bh
    ) {
        bh.consume(readSuccess(s.heap, s.fixture, "readHeapSuccess"));
        counters.successOps++;
    }

    @Benchmark
    public void readDirectWouldBlock(
            BlockReadState s,
            IoCounters counters,
            Blackhole bh
    ) {
        bh.consume(readWouldBlock(s.direct, s.fixture, "readDirectWouldBlock"));
        counters.wouldBlockOps++;
    }

    private static int readWouldBlock(
            ByteBuffer buffer,
            NativeBridgeFixture fixture,
            String label
    ) {
        buffer.clear();
        var result = fixture.clientConnection().read(buffer);
        if (result.progressed()) {
            throw new IllegalStateException(label + " expected WOULD_BLOCK, got " + result);
        }
        return 0;
    }

    @Benchmark
    public void readHeapWouldBlock(
            BlockReadState s,
            IoCounters counters,
            Blackhole bh
    ) {
        bh.consume(readWouldBlock(s.heap, s.fixture, "readHeapWouldBlock"));
        counters.wouldBlockOps++;
    }

    /**
     * Shared IO state. Subclasses differ only in fixture drain/feed behaviour, which is what
     * separates "success" from "WOULD_BLOCK" measurements.
     */
    @SuppressWarnings("NotNullFieldNotInitialized")
    @State(Scope.Benchmark)
    public abstract static class IoState {

        final boolean drainServer;
        final boolean feedClient;
        @Param({"64", "1024", "65536"}) int size;
        NativeBridgeFixture fixture;
        ByteBuffer direct;
        ByteBuffer heap;

        IoState(
                boolean drainServer,
                boolean feedClient
        ) {
            super();
            this.drainServer = drainServer;
            this.feedClient = feedClient;
        }

        @Setup(Level.Trial)
        public void setUp() {
            fixture = NativeBridgeFixture.open(
                    NativeBridgeControlBenchmark.nativeLibrary(),
                    2,
                    drainServer,
                    feedClient,
                    size
            );
            direct = ByteBuffer.allocateDirect(size);
            heap = ByteBuffer.allocate(size);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (fixture != null) {
                fixture.close();
            }
        }

    }

    @State(Scope.Benchmark)
    public static class WriteState extends IoState {

        public WriteState() {
            super(true, false);
        }

    }

    @State(Scope.Benchmark)
    public static class BlockWriteState extends IoState {

        public BlockWriteState() {
            super(false, false);
        }

    }

    @State(Scope.Benchmark)
    public static class ReadState extends IoState {

        public ReadState() {
            super(false, true);
        }

    }

    @State(Scope.Benchmark)
    public static class BlockReadState extends IoState {

        public BlockReadState() {
            super(false, false);
        }

    }

    /**
     * Explicit outcome accounting for the L1 native IO matrix. Each benchmark increments exactly
     * one counter after its fail-fast outcome assertion, so the runner reports how many native
     * downcalls produced progress versus how many observed backpressure instead of leaving the
     * distinction implicit in the method name.
     */
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class IoCounters {

        public long successOps;
        public long wouldBlockOps;

    }

}
