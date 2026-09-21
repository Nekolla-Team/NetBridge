package team.nekolla.netbridge.benchmark.workload

import team.nekolla.netbridge.benchmark.common.LatencySamples
import team.nekolla.netbridge.benchmark.common.cpuDeltaNanos
import team.nekolla.netbridge.benchmark.common.processCpuNanos
import team.nekolla.netbridge.benchmark.model.MinecraftShapedMeasurement
import team.nekolla.netbridge.benchmark.transport.BenchClient
import team.nekolla.netbridge.benchmark.transport.ClientSession
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

private const val SMALL_MESSAGE_BYTES = 128
private const val MAX_OUTSTANDING_WRITES = 256
private const val DEADLINE_MISS_NANOS = 1_000_000L
private const val RUN_TIMEOUT_MILLIS = 120_000L

/**
 * Drives a [MinecraftWorkload] against a live benchmark server.
 *
 * <p>Client- and server-driven steps are dispatched to separate worker threads and each frame
 * is emitted against an absolute deadline (no per-message `sync`), so the measurement window
 * closes around the actual producer window and schedule slip is observable rather than hidden
 * behind blocking waits. Server-to-client steps ask the server to emit the burst and wait for
 * its completion report, so the direction is genuinely server-driven.
 */
class MinecraftWorkloadRunner {

    fun simulate(
        client: BenchClient,
        host: String,
        port: Int,
        workload: MinecraftWorkload
    ): MinecraftShapedMeasurement {
        val small = LatencySamples()
        val clientSent = AtomicLong()
        val serverSent = AtomicLong()
        val failedServerSends = AtomicLong()
        val maxSlip = AtomicLong(0L)
        val deadlineMisses = AtomicLong(0L)

        val wallStart = System.nanoTime()
        val outcome = ClientSession.open(
            client,
            host,
            port,
            30_000
        ).use { session ->
            val cpuStart = processCpuNanos()
            val expectedNanos = AtomicLong(-1L)
            val expectedInterval = AtomicLong(0L)
            session.onInboundData { arrival ->
                val base = expectedNanos.get()
                if (base >= 0L) {
                    small.add((arrival - base).coerceAtLeast(0L))
                    expectedNanos.set(base + expectedInterval.get())
                }
            }

            val permits = Semaphore(MAX_OUTSTANDING_WRITES)
            val clientSteps = workload.steps.filter {
                it.direction == WorkloadStep.Direction.CLIENT_TO_SERVER
            }
            val serverSteps = workload.steps.filter {
                it.direction == WorkloadStep.Direction.SERVER_TO_CLIENT
            }

            val executor = Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "nb-mc-workload").apply { isDaemon = true }
            }
            try {
                val clientTask = executor.submit {
                    runClientSteps(
                        session,
                        clientSteps,
                        permits,
                        clientSent,
                        maxSlip,
                        deadlineMisses
                    )
                }
                val serverTask = executor.submit {
                    runServerSteps(
                        session,
                        serverSteps,
                        expectedNanos,
                        expectedInterval,
                        serverSent,
                        failedServerSends
                    )
                }
                clientTask.get(RUN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                serverTask.get(RUN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }

            session.onInboundData(null)
            val stats = session.requestReport().get(RUN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            val cpuEnd = processCpuNanos()

            val expectedServerBytes = serverSent.get()
            val integrityError =
                stats.bytesReceived != clientSent.get()
                        || stats.corruptFrames > 0
                        || stats.disorderEvents > 0
                        || stats.failedWrites > 0
                        || failedServerSends.get() > 0
                        || session.corruptInboundFrames > 0
                        || session.disorderEvents > 0
                        || session.clientReceivedBytes != expectedServerBytes

            check(!integrityError) {
                "minecraft workload integrity failure: serverStats=$stats, " +
                        "clientSent=${clientSent.get()}, expectedServerBytes=$expectedServerBytes, " +
                        "failedServerSends=${failedServerSends.get()}"
            }

            WorkloadOutcome(
                serverToClientBytes = expectedServerBytes,
                cpuNanos = cpuDeltaNanos(cpuStart, cpuEnd)
            )
        }
        val wallNanos = System.nanoTime() - wallStart
        val appBytes = workload.totalBytes
        val mibPerSecond =
            (appBytes.toDouble() / (1024.0 * 1024.0)) / (wallNanos.toDouble() / 1e9)
        val smallSummary = small.takeIf { it.size > 0 }?.summary()

        return MinecraftShapedMeasurement(
            name = workload.name,
            transport = client.transport.label,
            payloadBytes = appBytes,
            messages = workload.totalMessages,
            durationNanos = wallNanos,
            completionTimeNanos = wallNanos,
            mibPerSecond = mibPerSecond,
            processCpuNanos = outcome.cpuNanos,
            smallMessageCount = small.size,
            smallMessageMeanNanos = smallSummary?.meanNanos,
            smallMessageP50Nanos = smallSummary?.p50Nanos,
            smallMessageP95Nanos = smallSummary?.p95Nanos,
            smallMessageP99Nanos = smallSummary?.p99Nanos,
            smallMessageP999Nanos = smallSummary?.p999Nanos,
            serverToClientBytes = outcome.serverToClientBytes,
            maxScheduleSlipNanos = maxSlip.get(),
            deadlineMisses = deadlineMisses.get()
        )
    }

    private fun runClientSteps(
        session: ClientSession,
        steps: List<WorkloadStep>,
        permits: Semaphore,
        clientSent: AtomicLong,
        maxSlip: AtomicLong,
        deadlineMisses: AtomicLong
    ) {
        steps.forEach { step ->
            val startNanos = System.nanoTime()
            (0 until step.count).forEach { index ->
                val deadline = startNanos + step.intervalNanos * index
                parkUntil(deadline)
                recordSlip(
                    System.nanoTime() - deadline,
                    maxSlip,
                    deadlineMisses
                )

                permits.acquire()
                val payload = step.payloadBytes
                clientSent.addAndGet(payload.toLong())
                session.writeWorkloadData(payload).addListener {
                    permits.release()
                }
            }
        }

        permits.acquire(MAX_OUTSTANDING_WRITES)
        permits.release(MAX_OUTSTANDING_WRITES)
    }

    private fun runServerSteps(
        session: ClientSession,
        steps: List<WorkloadStep>,
        expectedNanos: AtomicLong,
        expectedInterval: AtomicLong,
        serverSent: AtomicLong,
        failedServerSends: AtomicLong
    ) {
        steps.forEach { step ->
            val startNanos = System.nanoTime()
            if (step.payloadBytes <= SMALL_MESSAGE_BYTES) {
                expectedInterval.set(step.intervalNanos)
                expectedNanos.set(startNanos)
            }
            val result = session
                    .serverSend(step.payloadBytes, step.count, step.intervalNanos)
                    .get(RUN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            expectedNanos.set(-1L)
            serverSent.addAndGet(result.bytesSent)
            failedServerSends.addAndGet(result.failures)
        }
    }

    private fun parkUntil(deadlineNanos: Long) {
        while (true) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0L || Thread.currentThread().isInterrupted) {
                return
            }
            LockSupport.parkNanos(remaining)
        }
    }

    private fun recordSlip(
        slip: Long,
        maxSlip: AtomicLong,
        deadlineMisses: AtomicLong
    ) {
        if (slip <= 0L) {
            return
        }

        maxSlip.accumulateAndGet(slip) { a, b -> maxOf(a, b) }
        if (slip > DEADLINE_MISS_NANOS) {
            deadlineMisses.incrementAndGet()
        }
    }

    private class WorkloadOutcome(
        val serverToClientBytes: Long,
        val cpuNanos: Long?
    )

}

