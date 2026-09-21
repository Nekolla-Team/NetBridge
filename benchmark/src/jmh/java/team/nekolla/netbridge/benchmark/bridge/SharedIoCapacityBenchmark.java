package team.nekolla.netbridge.benchmark.bridge;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import team.nekolla.netbridge.config.SharedIoMode;
import team.nekolla.netbridge.nativebridge.NativeIoResult;

import java.nio.ByteBuffer;

/**
 * B1/capacity: direct shared-ring throughput across the tuning matrix.
 *
 * <p>Run with a saturating peer (drainer for writes, feeder for reads) so the number reflects
 * steady
 * state at the selected per-connection ring capacity. Success/WOULD_BLOCK counts are reported so a
 * capacity that induces frequent backpressure is visible instead of being averaged away.
 */
@SuppressWarnings("NullAway")
public class SharedIoCapacityBenchmark {

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
    public void readDirect(
            ReadState s,
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
    public abstract static class CapacityState {

        final boolean drainServer;
        final boolean feedClient;
        @Param({"65536", "131072", "262144", "524288"}) int capacity;
        @Param({"256", "4096"}) int size;
        NativeBridgeFixture fixture;
        ByteBuffer direct;

        CapacityState(
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
                    size,
                    SharedIoMode.ON,
                    capacity,
                    capacity
            );
            direct = ByteBuffer.allocateDirect(size);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (fixture != null) {
                fixture.close();
            }
        }

    }

    public static class WriteState extends CapacityState {

        public WriteState() {
            super(true, false);
        }

    }

    public static class ReadState extends CapacityState {

        public ReadState() {
            super(false, true);
        }

    }

}
