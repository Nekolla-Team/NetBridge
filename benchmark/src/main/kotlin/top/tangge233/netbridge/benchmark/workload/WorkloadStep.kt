package top.tangge233.netbridge.benchmark.workload

import top.tangge233.netbridge.benchmark.transport.TransportPayloadCodec

/**
 * One schedule entry in a [MinecraftWorkload].
 *
 * [intervalNanos] is the per-message spacing on an absolute deadline schedule (0 = emit as fast as the
 * transport accepts). Steps in opposite directions are dispatched to separate worker threads so client-
 * and server-driven traffic overlaps, matching real gameplay.
 */
data class WorkloadStep(
    val direction: Direction,
    val payloadBytes: Int,
    val intervalNanos: Long,
    val count: Int
) {

    init {
        require(payloadBytes in 1..TransportPayloadCodec.MAX_FRAME_PAYLOAD) {
            "payloadBytes must be within 1..${TransportPayloadCodec.MAX_FRAME_PAYLOAD}"
        }
        require(count >= 1) {
            "count must be >= 1"
        }
        require(intervalNanos >= 0L) {
            "intervalNanos must be >= 0"
        }
    }

    val totalBytes: Long
        get() = payloadBytes.toLong() * count.toLong()

    enum class Direction {

        CLIENT_TO_SERVER,
        SERVER_TO_CLIENT

    }

    companion object {

        fun clientToServer(
            payloadBytes: Int,
            intervalNanos: Long,
            count: Int
        ): WorkloadStep =
            WorkloadStep(
                Direction.CLIENT_TO_SERVER,
                payloadBytes,
                intervalNanos,
                count
            )

        fun serverToClient(
            payloadBytes: Int,
            intervalNanos: Long,
            count: Int
        ): WorkloadStep =
            WorkloadStep(
                Direction.SERVER_TO_CLIENT,
                payloadBytes,
                intervalNanos,
                count
            )

    }

}
