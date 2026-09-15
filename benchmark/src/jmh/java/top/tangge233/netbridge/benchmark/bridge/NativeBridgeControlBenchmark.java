package top.tangge233.netbridge.benchmark.bridge;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * L1 control-plane benchmarks against the production Java&lt;-&gt;native bridge.
 *
 * <p>{@code connectionStateDowncall} uses {@code FfmNativeContext.connectionState}
 * directly (a real downcall every time). {@code connectionStateCached} goes through
 * {@code FfmNativeConnection.state()}, which is a Java cached read once the connection is CONNECTED
 * — this contrast is the whole point.
 */
@State(Scope.Benchmark)
@SuppressWarnings("NullAway")
public class NativeBridgeControlBenchmark {

    @Nullable NativeBridgeFixture fixture;

    @Setup(Level.Trial)
    public void setUp() {
        fixture = NativeBridgeFixture.open(
                nativeLibrary(),
                2,
                false,
                false
        );
    }

    static Path nativeLibrary() {
        var path = System.getProperty("netbridge.native.path");
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                    "-Dnetbridge.native.path is required for bridge benchmarks"
            );
        }
        return Path.of(path);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Benchmark
    public void connectionStateDowncall(Blackhole bh) {
        bh.consume(fixture.context().connectionState(fixture.clientConnection().id()));
    }

    @Benchmark
    public void connectionStateCached(Blackhole bh) {
        bh.consume(fixture.clientConnection().state());
    }

}
