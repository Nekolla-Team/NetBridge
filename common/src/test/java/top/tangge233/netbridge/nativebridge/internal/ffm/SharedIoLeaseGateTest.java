package top.tangge233.netbridge.nativebridge.internal.ffm;

import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.nativebridge.NativeException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Close-versus-IO stress for the direct data-plane lease gate. This is the automated form of the
 * Phase 4 exit criterion: a closer must never let the Java view be invalidated while an operation
 * is still in flight.
 */
class SharedIoLeaseGateTest {

    @Test
    void beginAndEndTrackActiveOperations() {
        var gate = new SharedIoLeaseGate();
        assertTrue(gate.begin());
        assertEquals(1, gate.activeCount());
        gate.end();
        assertEquals(0, gate.activeCount());
        assertFalse(gate.isClosing());
    }

    @Test
    void beginIsRejectedOnceClosingStarts() {
        var gate = new SharedIoLeaseGate();
        assertTrue(gate.beginClose());
        assertTrue(gate.isClosing());
        assertFalse(gate.begin());
        assertFalse(gate.beginClose(), "closing is a one-shot transition");
    }

    @Test
    void awaitDrainWaitsForAnInFlightOperation() throws Exception {
        var gate = new SharedIoLeaseGate();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = Thread.ofPlatform().start(() -> {
            gate.begin();
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                gate.end();
            }
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(gate.beginClose());
        var drained = new AtomicBoolean();
        var drainer = Thread.ofPlatform().start(() -> {
            gate.awaitDrain(5_000);
            drained.set(true);
        });

        Thread.sleep(50);
        assertFalse(
                drained.get(),
                "drain must not finish while an operation is active"
        );
        release.countDown();
        drainer.join(5_000);
        worker.join(5_000);
        assertTrue(drained.get());
        assertEquals(0, gate.activeCount());
    }

    @Test
    void awaitDrainTimesOutWithoutHanging() {
        var gate = new SharedIoLeaseGate();
        assertTrue(gate.begin());
        assertTrue(gate.beginClose());
        assertThrows(NativeException.class, () -> gate.awaitDrain(50));
        gate.end();
        assertEquals(0, gate.activeCount());
    }

    @Test
    void closeVersusIoStressLeavesNoActiveOperations() throws Exception {
        var gate = new SharedIoLeaseGate();
        var threads = 8;
        var iterations = 20_000;
        var start = new CountDownLatch(1);
        var failure = new AtomicReference<@Nullable Throwable>();
        var accepted = new AtomicInteger();
        var workers = IntStream.range(0, threads)
                .mapToObj(_ -> Thread.ofPlatform()
                        .start(() -> {
                            try {
                                start.await();
                                IntStream.range(0, iterations)
                                        .filter(_ -> gate.begin())
                                        .forEach(_ -> {
                                            try {
                                                accepted.incrementAndGet();
                                                Thread.onSpinWait();
                                            } finally {
                                                gate.end();
                                            }
                                        });
                            } catch (Throwable tr) {
                                failure.compareAndSet(null, tr);
                            }
                        })
                )
                .toArray(Thread[]::new);

        start.countDown();
        Thread.sleep(5);
        assertTrue(gate.beginClose());
        gate.awaitDrain(10_000);

        for (var worker : workers) {
            worker.join(10_000);
            assertFalse(worker.isAlive(), "IO worker failed to finish after close");
        }

        assertNull(failure.get(), () -> "unexpected failure: " + failure.get());
        assertEquals(0, gate.activeCount());
        assertTrue(
                accepted.get() > 0,
                "some operations must have been admitted before closing"
        );
    }

}
