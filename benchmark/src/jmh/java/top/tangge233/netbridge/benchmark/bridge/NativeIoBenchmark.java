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
    public void writeDirectSuccess(WriteState s, Blackhole bh) {
        bh.consume(writeSuccess(s.direct, s.fixture, "writeDirectSuccess"));
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
    public void writeHeapSuccess(WriteState s, Blackhole bh) {
        bh.consume(writeSuccess(s.heap, s.fixture, "writeHeapSuccess"));
    }

    @Benchmark
    public void writeDirectWouldBlock(BlockWriteState s, Blackhole bh) {
        bh.consume(writeWouldBlock(s.direct, s.fixture, "writeDirectWouldBlock"));
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
    public void writeHeapWouldBlock(BlockWriteState s, Blackhole bh) {
        bh.consume(writeWouldBlock(s.heap, s.fixture, "writeHeapWouldBlock"));
    }

    @Benchmark
    public void readDirectSuccess(ReadState s, Blackhole bh) {
        bh.consume(readSuccess(s.direct, s.fixture, "readDirectSuccess"));
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
    public void readHeapSuccess(ReadState s, Blackhole bh) {
        bh.consume(readSuccess(s.heap, s.fixture, "readHeapSuccess"));
    }

    @Benchmark
    public void readDirectWouldBlock(BlockReadState s, Blackhole bh) {
        bh.consume(readWouldBlock(s.direct, s.fixture, "readDirectWouldBlock"));
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
    public void readHeapWouldBlock(BlockReadState s, Blackhole bh) {
        bh.consume(readWouldBlock(s.heap, s.fixture, "readHeapWouldBlock"));
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
                    feedClient
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

}
