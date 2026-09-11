package top.tangge233.netbridge.benchmark.ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Raw FFM bindings to the benchmark-only probe native library.
 *
 * <p>This deliberately uses {@code SymbolLookup.libraryLookup} + the native
 * {@code Linker} directly rather than the production loader, so the measured numbers are the pure
 * JVM-to-native boundary cost with zero product glue.
 */
public final class RawFfmBindings implements AutoCloseable {

    private final Arena arena;
    private final MethodHandle noop;
    private final MethodHandle echoU64;
    private final MethodHandle outU32;
    private final MethodHandle readBuffer;
    private final MethodHandle writeBuffer;
    private final MethodHandle invokeCallback;

    private RawFfmBindings(
            Arena arena,
            SymbolLookup lookup
    ) {
        this.arena = arena;
        var linker = Linker.nativeLinker();
        this.noop = linker.downcallHandle(
                require(lookup, "nb_bench_noop"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT)
        );
        this.echoU64 = linker.downcallHandle(
                require(lookup, "nb_bench_echo_u64"),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS
                )
        );
        this.outU32 = linker.downcallHandle(
                require(lookup, "nb_bench_out_u32"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );
        this.readBuffer = linker.downcallHandle(
                require(lookup, "nb_bench_read_buffer"),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS
                )
        );
        this.writeBuffer = linker.downcallHandle(
                require(lookup, "nb_bench_write_buffer"),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_BYTE
                )
        );
        this.invokeCallback = linker.downcallHandle(
                require(lookup, "nb_bench_invoke_callback"),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS
                )
        );
    }

    private static MemorySegment require(SymbolLookup lookup, String name) {
        return lookup.find(name)
                .orElseThrow(() ->
                        new IllegalStateException("probe symbol not found: " + name)
                );
    }

    public static RawFfmBindings open(Path libraryPath) {
        var arena = Arena.ofShared();
        var lookup = SymbolLookup.libraryLookup(libraryPath, arena);
        return new RawFfmBindings(arena, lookup);
    }

    public int noop() {
        try {
            return (int) noop.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int echoU64(long value, MemorySegment out) {
        try {
            return (int) echoU64.invokeExact(value, out);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int outU32(MemorySegment out) {
        try {
            return (int) outU32.invokeExact(out);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int readBuffer(
            MemorySegment src,
            long length,
            MemorySegment outSum
    ) {
        try {
            return (int) readBuffer.invokeExact(src, length, outSum);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int writeBuffer(
            MemorySegment dst,
            long length,
            byte value
    ) {
        try {
            return (int) writeBuffer.invokeExact(dst, length, value);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public int invokeCallback(
            MemorySegment callback,
            long arg,
            MemorySegment out
    ) {
        try {
            return (int) invokeCallback.invokeExact(callback, arg, out);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Allocates a native buffer for a probe (caller keeps it alive in an arena). */
    public MemorySegment allocate(long bytes) {
        return arena.allocate(bytes);
    }

    /** Converts a Java method into a C ABI upcall stub. */
    public MemorySegment upcallStub(@Nullable MethodHandle target) {
        if (target == null) {
            throw new IllegalStateException("upcall target required");
        }
        return Linker.nativeLinker().upcallStub(
                target,
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                arena
        );
    }

    @Override
    public void close() {
        arena.close();
    }

}
