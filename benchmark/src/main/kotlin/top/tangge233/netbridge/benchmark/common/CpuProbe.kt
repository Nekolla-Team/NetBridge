package top.tangge233.netbridge.benchmark.common

@Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
fun processCpuNanos(): Long =
    ProcessHandle.current()
        .info()
        .totalCpuDuration()
        .map { it.toNanos() }
        .orElse(-1L)
