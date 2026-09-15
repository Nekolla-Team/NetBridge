package top.tangge233.netbridge.benchmark.workload

class MinecraftWorkload(
    val name: String,
    steps: List<WorkloadStep>
) {

    val steps: List<WorkloadStep> = steps.toList()

    val totalBytes: Long
        get() = steps.sumOf { it.payloadBytes.toLong() * it.count.toLong() }

    val totalMessages: Long
        get() = steps.sumOf { it.count.toLong() }

    val hasSmallMessages: Boolean
        get() = steps.any { it.payloadBytes <= 128 }

}
