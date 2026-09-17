package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.nativebridge.NativeException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-connection gate that makes direct shared-ring access safe against concurrent close.
 *
 * <p>The state is a single {@link AtomicInteger}: the high bit records {@code CLOSING} and the low
 * 31 bits count in-flight direct IO operations. {@link #begin()} rejects new IO once closing
 * starts; {@link #beginClose()} flips the bit and then {@link #awaitDrain(long)} waits for the
 * count to reach zero before the Java view is invalidated.
 *
 * <p>Like the hardened {@link FfmCallGate#endOp()}, the common path only touches the monitor when
 * a closer is actually waiting and the operation was the last in flight.
 */
final class SharedIoLeaseGate {

    private static final int CLOSING = 1 << 31;
    private static final int ACTIVE_MASK = CLOSING - 1;
    private static final int DRAIN_POLL_MILLIS = 20;

    private final AtomicInteger state = new AtomicInteger();
    private final Object lifecycleLock = new Object();

    /** Enters the gate; returns {@code false} once closing has started. */
    boolean begin() {
        while (true) {
            var current = state.get();
            if ((current & CLOSING) != 0) {
                return false;
            }
            if (state.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Leaves the gate; wakes a waiting closer only when this was the last active operation. */
    void end() {
        var remaining = state.decrementAndGet();
        if ((remaining & CLOSING) != 0 && (remaining & ACTIVE_MASK) == 0) {
            synchronized (lifecycleLock) {
                lifecycleLock.notifyAll();
            }
        }
    }

    /** Flips the gate into closing; returns {@code false} when it was already closing. */
    boolean beginClose() {
        while (true) {
            var current = state.get();
            if ((current & CLOSING) != 0) {
                return false;
            }
            if (state.compareAndSet(current, current | CLOSING)) {
                return true;
            }
        }
    }

    /** Waits until every in-flight direct IO operation has left the gate. */
    void awaitDrain(long timeoutMillis) {
        var deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (lifecycleLock) {
            while ((state.get() & ACTIVE_MASK) != 0) {
                var remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new NativeException(
                            "TIMEOUT_DRAINING_SHARED_IO: %d direct operations still active".formatted(
                                    state.get() & ACTIVE_MASK
                            )
                    );
                }
                try {
                    lifecycleLock.wait(Math.clamp(
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos),
                            1,
                            DRAIN_POLL_MILLIS
                    ));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new NativeException("INTERRUPTED_DRAINING_SHARED_IO", e);
                }
            }
        }
    }

    boolean isClosing() {
        return (state.get() & CLOSING) != 0;
    }

    int activeCount() {
        return state.get() & ACTIVE_MASK;
    }

}
