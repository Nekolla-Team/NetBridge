package top.tangge233.netbridge.nativebridge.internal.ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FfmNativeLibrary implements AutoCloseable {

    private final Arena arena;
    private final SymbolLookup lookup;
    private final FfmApiV1 api;
    private final Object stateLock = new Object();
    private final Set<FfmNativeContext> activeContexts = ConcurrentHashMap.newKeySet();
    private State state = State.OPEN;
    private int creatingContexts = 0;

    private FfmNativeLibrary(
            Arena arena,
            SymbolLookup lookup,
            FfmApiV1 api
    ) {
        this.arena = arena;
        this.lookup = lookup;
        this.api = api;
    }

    public static FfmNativeLibrary load(Path libraryPath) {
        var arena = Arena.ofShared();
        try {
            var lookup = SymbolLookup.libraryLookup(libraryPath, arena);
            var getApiSymbol = lookup.find("netbridge_get_api")
                    .orElseThrow(() ->
                            new IllegalStateException("Missing required symbol: netbridge_get_api")
                    );

            var linker = Linker.nativeLinker();
            var getApiHandle = linker.downcallHandle(
                    getApiSymbol,
                    FfmApiLayouts.GET_API_DESC
            );

            var tablePtrSegment = arena.allocate(ValueLayout.ADDRESS);
            var status = (int) getApiHandle.invokeExact(1, 0, tablePtrSegment);
            FfmStatus.checkStatus(status, "netbridge_get_api");

            var tableAddr = tablePtrSegment.get(ValueLayout.ADDRESS, 0);
            if (tableAddr.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("netbridge_get_api returned null API table");
            }

            var api = FfmApiV1.fromAddress(tableAddr, arena);
            var requiredFeatures = FfmApiV1.FEATURE_QUIC
                    | FfmApiV1.FEATURE_KCP
                    | FfmApiV1.FEATURE_WRITABLE_EVENT
                    | FfmApiV1.FEATURE_BINARY_SOCKET_ADDRESS;
            if ((api.featureBits() & requiredFeatures) != requiredFeatures) {
                throw new IllegalStateException(
                        "Native library missing required feature bits: expected at least %d, got %d".formatted(
                                requiredFeatures,
                                api.featureBits()
                        )
                );
            }

            return new FfmNativeLibrary(
                    arena,
                    lookup,
                    api
            );
        } catch (Throwable t) {
            arena.close();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(
                    "Failed to load net-bridge native library from " + libraryPath,
                    t
            );
        }
    }

    public FfmNativeContext createContext(int workerThreads) {
        synchronized (stateLock) {
            if (state != State.OPEN) {
                throw new IllegalStateException("FfmNativeLibrary is " + state);
            }
            creatingContexts++;
        }

        try (var localArena = Arena.ofConfined()) {
            var dispatcher = new NativeEventDispatcher();
            var mh = MethodHandles.lookup().bind(
                    dispatcher,
                    "onNativeEvent",
                    MethodType.methodType(
                            void.class,
                            int.class,
                            long.class,
                            long.class,
                            long.class
                    )
            );
            var upcallStub = Linker.nativeLinker().upcallStub(
                    mh,
                    FfmApiLayouts.EVENT_CALLBACK_DESC,
                    arena
            );

            var callbacks = localArena.allocate(FfmApiLayouts.CALLBACKS_V1);
            callbacks.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.CALLBACKS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("struct_size")
                    ),
                    (int) FfmApiLayouts.CALLBACKS_V1.byteSize()
            );
            callbacks.set(
                    ValueLayout.ADDRESS,
                    FfmApiLayouts.CALLBACKS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("on_event")
                    ),
                    upcallStub
            );

            var options = localArena.allocate(FfmApiLayouts.CONTEXT_OPTIONS_V1);
            options.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.CONTEXT_OPTIONS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("struct_size")
                    ),
                    (int) FfmApiLayouts.CONTEXT_OPTIONS_V1.byteSize()
            );
            options.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.CONTEXT_OPTIONS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("worker_threads")
                    ),
                    workerThreads
            );

            var outContext = localArena.allocate(ValueLayout.ADDRESS);
            var status = (int) api.contextCreate().invokeExact(
                    options,
                    callbacks,
                    outContext
            );
            FfmStatus.checkStatus(status, "context_create");

            var ctxAddr = outContext.get(ValueLayout.ADDRESS, 0);
            if (ctxAddr.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("context_create returned null context pointer");
            }

            var ctx = new FfmNativeContext(
                    this,
                    api,
                    ctxAddr,
                    dispatcher
            );
            activeContexts.add(ctx);
            return ctx;
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("createContext failed", t);
        } finally {
            synchronized (stateLock) {
                creatingContexts--;
                stateLock.notifyAll();
            }
        }
    }

    void unregisterContext(FfmNativeContext context) {
        activeContexts.remove(context);
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
    }

    private void ensureOpen() {
        synchronized (stateLock) {
            if (state != State.OPEN) {
                throw new IllegalStateException("FfmNativeLibrary is " + state);
            }
        }
    }

    public State state() {
        synchronized (stateLock) {
            return state;
        }
    }

    public FfmApiV1 api() {
        return api;
    }

    public SymbolLookup lookup() {
        return lookup;
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            while (state == State.CLOSING) {
                try {
                    stateLock.wait(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while waiting for closing library",
                            e
                    );
                }
            }

            if (state == State.CLOSED) {
                return;
            }

            state = State.CLOSING;
            while (creatingContexts > 0) {
                try {
                    stateLock.wait(20);
                } catch (InterruptedException e) {
                    state = State.CLOSE_FAILED;
                    stateLock.notifyAll();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while waiting for in-flight context creations",
                            e
                    );
                }
            }
        }

        Throwable firstError = null;
        for (var ctx : activeContexts) {
            try {
                ctx.close();
            } catch (Throwable t) {
                if (firstError == null) {
                    firstError = t;
                }
            }
        }

        if (firstError != null || !activeContexts.isEmpty()) {
            synchronized (stateLock) {
                state = State.CLOSE_FAILED;
                stateLock.notifyAll();
            }
            throw new IllegalStateException(
                    "Cannot safely close FfmNativeLibrary Arena: %d contexts still active".formatted(
                            activeContexts.size()
                    ),
                    firstError
            );
        }

        try {
            arena.close();
            synchronized (stateLock) {
                state = State.CLOSED;
                stateLock.notifyAll();
            }
        } catch (Throwable t) {
            synchronized (stateLock) {
                state = State.CLOSE_FAILED;
                stateLock.notifyAll();
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Failed to close library Arena", t);
        }
    }

    public enum State {

        OPEN,
        CLOSING,
        CLOSED,
        CLOSE_FAILED

    }

}
