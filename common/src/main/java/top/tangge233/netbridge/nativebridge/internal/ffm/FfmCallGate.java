package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.nativebridge.NativeException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the Java-side call lifecycle shared by every native downcall performed through an
 * {@link FfmNativeContext}.
 *
 * <p>It rejects new downcalls once the owning context starts closing, accounts for in-flight
 * downcalls so {@link #close(int, Teardown)} can drain them, waits against the drain deadline,
 * and synchronizes the {@link State} transitions. It does not know how socket addresses are
 * encoded and has no notion of transports; the owning context supplies the native teardown
 * steps through {@link Teardown}.
 */
public final class FfmCallGate {

    public enum State {

        OPEN,
        CLOSING,
        CLOSED,
        CLOSE_FAILED

    }

    /**
     * A native downcall body that may return a value. Callers map ABI results to semantic
     * types themselves; the gate only brackets the call with lifecycle accounting.
     */
    @FunctionalInterface
    public interface FfmOperation<T> {

        T run() throws Throwable;

    }

    /**
     * The native teardown executed once all in-flight downcalls have drained. Receives the
     * remaining drain budget in milliseconds so the native shutdown can be bounded by the
     * original close deadline.
     */
    @FunctionalInterface
    public interface Teardown {

        void run(int remainingTimeoutMillis) throws Throwable;

    }

    private static final int DRAIN_POLL_MILLIS = 20;

    private final AtomicLong activeOps = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private volatile State state = State.OPEN;

    public State state() {
        return state;
    }

    /**
     * Runs {@code operation} as an active downcall: rejected while the context is not fully
     * open, accounted so {@code close} can wait for it, and failure-wrapped with the operation
     * name for diagnosability.
     */
    public <T> T call(String operationName, FfmOperation<T> operation) {
        beginOp();
        try {
            return operation.run();
        } catch (Throwable t) {
            throw rethrow(t, operationName);
        } finally {
            endOp();
        }
    }

    public void execute(String operationName, FfmAction action) {
        beginOp();
        try {
            action.run();
        } catch (Throwable t) {
            throw rethrow(t, operationName);
        } finally {
            endOp();
        }
    }

    /**
     * Transitions to {@link State#CLOSING}, waits for in-flight downcalls to drain within the
     * deadline, runs the native teardown, then transitions to {@link State#CLOSED}. Any failure
     * (drain timeout, interrupt, or native teardown) leaves the gate in
     * {@link State#CLOSE_FAILED}.
     */
    public synchronized void close(int drainTimeoutMillis, Teardown teardown) {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSING;
        var deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(drainTimeoutMillis);
        try {
            awaitDrain(deadlineNanos);
            teardown.run(millisUntil(deadlineNanos));
            state = State.CLOSED;
        } catch (Throwable t) {
            state = State.CLOSE_FAILED;
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new NativeException("failed to close native context", t);
        }
    }

    @FunctionalInterface
    public interface FfmAction {

        void run() throws Throwable;

    }

    private void beginOp() {
        if (state != State.OPEN) {
            throw new NativeException("NativeContext is " + state);
        }
        activeOps.incrementAndGet();
        if (state != State.OPEN) {
            activeOps.decrementAndGet();
            throw new NativeException("NativeContext is " + state);
        }
    }

    private void endOp() {
        activeOps.decrementAndGet();
        synchronized (lifecycleLock) {
            lifecycleLock.notifyAll();
        }
    }

    private void awaitDrain(long deadlineNanos) {
        while (activeOps.get() > 0) {
            if (System.nanoTime() > deadlineNanos) {
                throw new NativeException(
                        "TIMEOUT_DRAINING_ACTIVE_OPERATIONS: cannot destroy context with %d active operations".formatted(
                                activeOps.get()
                        )
                );
            }
            synchronized (lifecycleLock) {
                try {
                    lifecycleLock.wait(DRAIN_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new NativeException("INTERRUPTED_DRAINING_ACTIVE_OPERATIONS", e);
                }
            }
        }
    }

    private static int millisUntil(long deadlineNanos) {
        var remainingNanos = deadlineNanos - System.nanoTime();
        return (int) Math.max(
                10,
                remainingNanos / 1_000_000L
        );
    }

    private static RuntimeException rethrow(Throwable t, String operationName) {
        return t instanceof RuntimeException re
                ? re
                : new RuntimeException(operationName + " invocation failed", t);
    }

}
