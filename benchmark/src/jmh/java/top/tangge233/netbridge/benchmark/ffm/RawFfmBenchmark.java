package top.tangge233.netbridge.benchmark.ffm;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.stream.IntStream;

/**
 * L0 raw Java&lt;-&gt;native boundary benchmarks against the benchmark-only probe library. Java
 * baselines run on the same work to expose pure FFM overhead.
 */
@State(Scope.Benchmark)
@SuppressWarnings({"NullAway", "unused", "NotNullFieldNotInitialized"})
public class RawFfmBenchmark {

    @Param({"64", "1024", "16384", "65536"}) int bufferBytes;

    RawFfmBindings bindings;
    MemorySegment outU64;
    MemorySegment outU32;
    MemorySegment srcBuffer;
    MemorySegment dstBuffer;
    MemorySegment callbackSegment;

    long sinkValue = 0x1234_5678_9ABC_DEFL;

    private static long increment(long value) {
        return value + 1;
    }

    @Setup(Level.Trial)
    public void setUp() {
        var path = System.getProperty("netbridge.probe.native.path");
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                    "-Dnetbridge.probe.native.path is required for RawFfmBenchmark"
            );
        }

        bindings = RawFfmBindings.open(Path.of(path));
        outU64 = bindings.allocate(8);
        outU32 = bindings.allocate(4);
        srcBuffer = bindings.allocate(bufferBytes);
        dstBuffer = bindings.allocate(bufferBytes);
        IntStream.range(0, bufferBytes)
                .forEach(i ->
                        srcBuffer.set(ValueLayout.JAVA_BYTE, i, (byte) i)
                );
        try {
            var lookup = MethodHandles.lookup();
            var target = lookup.findStatic(
                    RawFfmBenchmark.class,
                    "increment",
                    MethodType.methodType(long.class, long.class)
            );
            callbackSegment = bindings.upcallStub(target);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("upcall target unavailable", e);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (bindings != null) {
            bindings.close();
        }
    }

    // ------------------------------------------------------------------
    // Java baselines
    // ------------------------------------------------------------------

    @Benchmark
    public void javaNoop(Blackhole bh) {
        sinkValue = sinkValue * 31 + 17;
        bh.consume(sinkValue);
    }

    @Benchmark
    public void javaEchoU64(Blackhole bh) {
        sinkValue = (sinkValue ^ (sinkValue >>> 33)) * 0xFF51AFD7ED558CCDL;
        bh.consume(sinkValue);
    }

    // ------------------------------------------------------------------
    // Raw FFM downcalls
    // ------------------------------------------------------------------

    @Benchmark
    public void ffmNoop(Blackhole bh) {
        bh.consume(bindings.noop());
    }

    @Benchmark
    public void ffmEchoU64(Blackhole bh) {
        bindings.echoU64(sinkValue, outU64);
        bh.consume(outU64.get(ValueLayout.JAVA_LONG, 0));
    }

    @Benchmark
    public void ffmOutPointer(Blackhole bh) {
        bindings.outU32(outU32);
        bh.consume(outU32.get(ValueLayout.JAVA_INT, 0));
    }

    @Benchmark
    public void ffmReadDirectBuffer(Blackhole bh) {
        bindings.readBuffer(srcBuffer, bufferBytes, outU64);
        bh.consume(outU64.get(ValueLayout.JAVA_LONG, 0));
    }

    @Benchmark
    public void ffmWriteDirectBuffer(Blackhole bh) {
        bindings.writeBuffer(dstBuffer, bufferBytes, (byte) 0x5A);
        bh.consume(dstBuffer.get(ValueLayout.JAVA_BYTE, bufferBytes - 1));
    }

    // ------------------------------------------------------------------
    // Java -> native -> Java upcall round trip
    // ------------------------------------------------------------------

    @Benchmark
    public void ffmUpcall(Blackhole bh) {
        bindings.invokeCallback(callbackSegment, sinkValue, outU64);
        bh.consume(outU64.get(ValueLayout.JAVA_LONG, 0));
    }

}
