package top.tangge233.netbridge.benchmark.bridge;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import top.tangge233.netbridge.config.SharedIoMode;
import top.tangge233.netbridge.nativebridge.NativeIoResult;

import java.nio.ByteBuffer;

/**
 * B1: the key A/B of this work — the same new native transport, reached either through the legacy
 * {@code connection_write}/{@code connection_read} FFM shim ({@code off}) or through the direct
 * shared-ring data plane ({@code on}). The delta is the per-chunk FFM boundary cost.
 *
 * <p>Each invocation records whether the operation progressed or hit WOULD_BLOCK instead of
 * asserting a single-call outcome: with a live transport either can legitimately happen depending
 * on scheduling. The auxiliary counters make the achieved path mix explicit, so a comparison
 * between {@code off} and {@code on} is only read when the mixes match.
 */
@SuppressWarnings("NullAway")
public class SharedIoModeBenchmark {

    @Benchmark
    public void writeDirect(
            WriteState s,
            IoOutcome counters,
            Blackhole bh
    ) {
        s.direct.clear();
        counters.record(s.fixture.clientConnection().write(s.direct), bh);
    }

    @Benchmark
    public void writeHeap(
            WriteState s,
            IoOutcome counters,
            Blackhole bh
    ) {
        s.heap.clear();
        counters.record(s.fixture.clientConnection().write(s.heap), bh);
    }

    @Benchmark
    public void readDirect(
            ReadState s,
            IoOutcome counters,
            Blackhole bh
    ) {
        s.direct.clear();
        counters.record(s.fixture.clientConnection().read(s.direct), bh);
    }

    @Benchmark
    public void writeBlockProne(
            BlockState s,
            IoOutcome counters,
            Blackhole bh
    ) {
        s.direct.clear();
        counters.record(s.fixture.clientConnection().write(s.direct), bh);
    }

    @Benchmark
    public void readBlockProne(
            BlockState s,
            IoOutcome counters,
            Blackhole bh
    ) {
        s.direct.clear();
        counters.record(s.fixture.clientConnection().read(s.direct), bh);
    }

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class IoOutcome {

        public long successOps;
        public long wouldBlockOps;
        public long bytesOps;

        void record(
                NativeIoResult result,
                Blackhole bh
        ) {
            if (result.progressed()) {
                successOps++;
                bytesOps += result.bytes();
            } else {
                wouldBlockOps++;
            }
            bh.consume(result);
        }

    }

    @State(Scope.Benchmark)
    @SuppressWarnings("NotNullFieldNotInitialized")
    public abstract static class ModeState {

        final boolean drainServer;
        final boolean feedClient;
        @Param({"off", "on"}) String mode;
        @Param({"64", "1024", "65536"}) int size;
        NativeBridgeFixture fixture;
        ByteBuffer direct;
        ByteBuffer heap;

        ModeState(
                boolean drainServer,
                boolean feedClient
        ) {
            this.drainServer = drainServer;
            this.feedClient = feedClient;
        }

        @Setup(Level.Trial)
        public void setUp() {
            var parsed = SharedIoMode.parse(mode);
            if (parsed == null) {
                throw new IllegalStateException("unknown sharedIo mode: " + mode);
            }
            fixture = NativeBridgeFixture.open(
                    NativeBridgeControlBenchmark.nativeLibrary(),
                    2,
                    drainServer,
                    feedClient,
                    size,
                    parsed,
                    0,
                    0
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

    public static class WriteState extends ModeState {

        public WriteState() {
            super(true, false);
        }

    }

    public static class ReadState extends ModeState {

        public ReadState() {
            super(false, true);
        }

    }

    public static class BlockState extends ModeState {

        public BlockState() {
            super(false, false);
        }

    }

}
