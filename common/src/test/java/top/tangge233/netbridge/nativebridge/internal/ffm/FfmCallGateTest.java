package top.tangge233.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.nativebridge.NativeException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural tests for {@link FfmCallGate}.
 *
 * <p>The steady-state path ({@code OPEN}) must not touch the lifecycle monitor, while
 * {@link FfmCallGate#close(int, FfmCallGate.Teardown)} must still reliably drain in-flight
 * operations and run teardown exactly once.
 */
class FfmCallGateTest {

    @Test
    void normalCallsRunAndKeepGateOpen() {
        var gate = new FfmCallGate();
        assertEquals(FfmCallGate.State.OPEN, gate.state());

        IntStream.range(0, 1000)
                .mapToObj(_ -> gate.call(
                        "noop",
                        () -> 42
                ))
                .forEach(value -> assertEquals(42, value));

        assertEquals(FfmCallGate.State.OPEN, gate.state());
    }

    @Test
    void closeDrainsInFlightOperationAndRunsTeardown() throws Exception {
        var gate = new FfmCallGate();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var teardownRuns = new AtomicInteger();

        var worker = new Thread(
                () -> gate.execute(
                        "slow",
                        () -> {
                            entered.countDown();
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                        }
                ),
                "gate-worker"
        );
        worker.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        var closer = new Thread(
                () -> gate.close(
                        10_000,
                        _ -> teardownRuns.incrementAndGet()
                ),
                "gate-closer"
        );
        closer.start();

        // close() must block until the in-flight operation finishes.
        assertTrue(closer.isAlive());
        release.countDown();

        worker.join(10_000);
        closer.join(10_000);
        assertFalse(worker.isAlive());
        assertFalse(closer.isAlive());
        assertEquals(1, teardownRuns.get());
        assertEquals(FfmCallGate.State.CLOSED, gate.state());
    }

    @Test
    void newCallsAreRejectedOnceClosing() throws Exception {
        var gate = new FfmCallGate();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        var worker = new Thread(
                () -> gate.execute(
                        "slow",
                        () -> {
                            entered.countDown();
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                        }
                ),
                "gate-worker"
        );
        worker.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        var closer = new Thread(
                () -> gate.close(
                        10_000, _ -> {
                        }
                ),
                "gate-closer"
        );
        closer.start();
        // Give close() a chance to publish CLOSING.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (gate.state() == FfmCallGate.State.OPEN && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(FfmCallGate.State.CLOSING, gate.state());

        assertThrows(NativeException.class, () -> gate.call("late", () -> 1));
        release.countDown();
        closer.join(10_000);
        worker.join(10_000);
    }

    @Test
    void closeIsIdempotent() {
        var gate = new FfmCallGate();
        var teardownRuns = new AtomicInteger();
        gate.close(1_000, _ -> teardownRuns.incrementAndGet());
        gate.close(1_000, _ -> teardownRuns.incrementAndGet());
        assertEquals(1, teardownRuns.get());
        assertEquals(FfmCallGate.State.CLOSED, gate.state());
    }

}
