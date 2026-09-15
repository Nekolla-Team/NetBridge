package top.tangge233.netbridge.benchmark.ffm;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/**
 * Pure-Java allocation/copy microbenchmarks used to separate allocation cost from the transport
 * data path (also the JMH hello that validates the fork setup on Java 25 without needing any native
 * library).
 */
@State(Scope.Benchmark)
@SuppressWarnings("NullAway")
public class FfmArenaBenchmark {

    private final byte[] pattern = new byte[]{0x01, 0x02, 0x03, 0x04};
    @Param({"64", "1024", "16384", "65536"}) int bufferBytes;

    @Benchmark
    public ByteBuffer allocateDirectBuffer(Blackhole bh) {
        var buffer = ByteBuffer.allocateDirect(bufferBytes);
        if (bufferBytes > 0) {
            buffer.put(0, pattern[0]);
        }
        bh.consume(buffer);
        return buffer;
    }

    @Benchmark
    public MemorySegment arenaSegmentAllocateAndWrite(Blackhole bh) {
        try (var arena = Arena.ofConfined()) {
            var segment = arena.allocate(bufferBytes);
            touch(segment);
            bh.consume(segment);
            return segment;
        }
    }

    private void touch(MemorySegment segment) {
        if (bufferBytes > 0) {
            segment.set(ValueLayout.JAVA_BYTE, 0, pattern[0]);
            segment.set(ValueLayout.JAVA_BYTE, bufferBytes - 1, pattern[3]);
        }
    }

    @Benchmark
    public ByteBuffer wrapHeapBuffer(Blackhole bh) {
        var heap = new byte[bufferBytes];
        var wrapped = ByteBuffer.wrap(heap);
        wrapped.put(0, pattern[0]);
        bh.consume(wrapped);
        return wrapped;
    }

}
