package top.tangge233.netbridge.benchmark.workload

data class WorkloadStep(
    val direction: Direction,
    val payloadBytes: Int,
    val delayNanos: Long,
    val count: Int
) {

    init {
        require(payloadBytes >= 1) { "payloadBytes must be >= 1" }
        require(count >= 1) { "count must be >= 1" }
    }

    enum class Direction {

        CLIENT_TO_SERVER,
        SERVER_TO_CLIENT

    }

    companion object {

        fun clientToServer(
            payloadBytes: Int,
            delayNanos: Long,
            count: Int
        ): WorkloadStep = WorkloadStep(
            Direction.CLIENT_TO_SERVER,
            payloadBytes,
            delayNanos,
            count
        )

        fun serverToClient(
            payloadBytes: Int,
            delayNanos: Long,
            count: Int
        ): WorkloadStep = WorkloadStep(
            Direction.SERVER_TO_CLIENT,
            payloadBytes,
            delayNanos,
            count
        )

    }

}
