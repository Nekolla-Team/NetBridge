package top.tangge233.netbridge.benchmark.common

/**
 * Process CPU time, or {@code null} when the runtime cannot provide it. Returning null instead of
 * a sentinel keeps "unsupported" distinct from a real zero (B-032/B-033).
 */
@Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
fun processCpuNanos(): Long? =
    ProcessHandle.current()
            .info()
            .totalCpuDuration()
            .map { it.toNanos() }
            .orElse(null)

/** CPU consumed between two snapshots, or {@code null} if either snapshot was unavailable. */
fun cpuDeltaNanos(startNanos: Long?, endNanos: Long?): Long? =
    if (startNanos == null || endNanos == null) {
        null
    } else {
        (endNanos - startNanos).coerceAtLeast(0L)
    }

/** Describes which process(es) the CPU window covers, so cross-scope deltas are not compared. */
object CpuScope {

    const val LOCAL_BOTH_ENDPOINTS = "LOCAL_BOTH_ENDPOINTS"
    const val CLIENT_ONLY = "CLIENT_ONLY"

}
