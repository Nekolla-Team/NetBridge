package top.tangge233.netbridge.benchmark.workload

import top.tangge233.netbridge.benchmark.common.LatencySamples
import top.tangge233.netbridge.benchmark.common.processCpuNanos
import top.tangge233.netbridge.benchmark.model.MinecraftShapedMeasurement
import top.tangge233.netbridge.benchmark.transport.BenchClient
import top.tangge233.netbridge.benchmark.transport.ClientSession
import top.tangge233.netbridge.benchmark.transport.FrameType
import top.tangge233.netbridge.benchmark.transport.TransportPayloadCodec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

private const val SMALL_MESSAGE_BYTES = 128

class MinecraftWorkloadRunner {

    private val nextSeq = AtomicLong(1)

    fun simulate(
        client: BenchClient,
        host: String,
        port: Int,
        workload: MinecraftWorkload
    ): MinecraftShapedMeasurement {
        val small = LatencySamples()
        val cpuStart = processCpuNanos()
        val wallStart = System.nanoTime()

        ClientSession.open(
            client,
            host,
            port,
            30_000
        ).use { session ->
            var sentOutboundBytes = 0L
            workload.steps.forEach { (direction, payloadBytes, delayNanos, count) ->
                repeat(count) {
                    when (direction) {
                        WorkloadStep.Direction.CLIENT_TO_SERVER -> {
                            val frame = TransportPayloadCodec.encodeFrame(
                                session.channel.alloc(),
                                FrameType.DATA,
                                nextSeq.getAndIncrement(),
                                payloadBytes
                            )
                            session.channel.writeAndFlush(frame).syncUninterruptibly()
                            sentOutboundBytes += payloadBytes.toLong()
                        }

                        WorkloadStep.Direction.SERVER_TO_CLIENT -> {
                            val rtt = session.ping(payloadBytes)
                            if (payloadBytes <= SMALL_MESSAGE_BYTES) {
                                small.add(rtt)
                            }
                        }
                    }

                    if (delayNanos > 0L) {
                        sleepNanos(delayNanos)
                    }
                }
            }

            val serverBytes = session.requestReport().get(60, TimeUnit.SECONDS)
            check(serverBytes == sentOutboundBytes) {
                "integrity mismatch: server counted $serverBytes but client sent $sentOutboundBytes"
            }
        }

        val cpuEnd = processCpuNanos()
        val wallNanos = System.nanoTime() - wallStart

        val appBytes = workload.totalBytes
        val mibPerSecond = (appBytes.toDouble() / (1024.0 * 1024.0)) / (wallNanos.toDouble() / 1e9)
        val smallSummary = small.takeIf { it.size > 0 }?.summary()

        return MinecraftShapedMeasurement(
            name = workload.name,
            transport = client.transport.label,
            payloadBytes = appBytes,
            messages = workload.totalMessages,
            durationNanos = wallNanos,
            completionTimeNanos = wallNanos,
            mibPerSecond = mibPerSecond,
            processCpuNanos = cpuEnd - cpuStart,
            smallMessageCount = small.size,
            smallMessageMeanNanos = smallSummary?.meanNanos,
            smallMessageP50Nanos = smallSummary?.p50Nanos,
            smallMessageP95Nanos = smallSummary?.p95Nanos,
            smallMessageP99Nanos = smallSummary?.p99Nanos,
            smallMessageP999Nanos = smallSummary?.p999Nanos
        )
    }

    private fun sleepNanos(nanos: Long) {
        if (nanos >= 2_000_000L) {
            try {
                Thread.sleep(TimeUnit.NANOSECONDS.toMillis(nanos))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } else if (nanos > 0L) {
            LockSupport.parkNanos(nanos)
        }
    }

}
