package top.tangge233.netbridge.benchmark.bridge;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;

/**
 * L1 data-plane matrix over the production bridge: direct/heap buffers against QUIC loopback,
 * measuring real progress and WOULD_BLOCK behaviour.
 */
@SuppressWarnings("NullAway")
public class NativeIoBenchmark {

    @Benchmark
    public void writeDirectSuccess(WriteState s, Blackhole bh) {
        bh.consume(write(s.direct, s.fixture));
    }

    private static int write(ByteBuffer buffer, NativeBridgeFixture fixture) {
        buffer.clear();
        return fixture.clientConnection().write(buffer).bytes();
    }

    @Benchmark
    public void writeHeapSuccess(WriteState s, Blackhole bh) {
        bh.consume(write(s.heap, s.fixture));
    }

    @Benchmark
    public void writeDirectWouldBlock(BlockWriteState s, Blackhole bh) {
        bh.consume(write(s.direct, s.fixture));
    }

    @Benchmark
    public void writeHeapWouldBlock(BlockWriteState s, Blackhole bh) {
        bh.consume(write(s.heap, s.fixture));
    }

    @Benchmark
    public void readDirectSuccess(ReadState s, Blackhole bh) {
        bh.consume(read(s.direct, s.fixture));
    }

    private static int read(ByteBuffer buffer, NativeBridgeFixture fixture) {
        buffer.clear();
        return fixture.clientConnection().read(buffer).bytes();
    }

    @Benchmark
    public void readHeapSuccess(ReadState s, Blackhole bh) {
        bh.consume(read(s.heap, s.fixture));
    }

    @Benchmark
    public void readDirectWouldBlock(BlockReadState s, Blackhole bh) {
        bh.consume(read(s.direct, s.fixture));
    }

    @Benchmark
    public void readHeapWouldBlock(BlockReadState s, Blackhole bh) {
        bh.consume(read(s.heap, s.fixture));
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
