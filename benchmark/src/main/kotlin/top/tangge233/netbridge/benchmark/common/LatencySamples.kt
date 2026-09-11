package top.tangge233.netbridge.benchmark.common

import kotlin.math.ceil

private const val INITIAL_CAPACITY = 1024

class LatencySamples {

    private var samples = LongArray(INITIAL_CAPACITY)
    private var count = 0

    val size: Int
        get() = count

    fun add(value: Long) {
        if (count == samples.size) {
            samples = samples.copyOf(count + (count shr 1))
        }
        samples[count++] = value
    }

    fun summary(): Summary {
        val sorted = sorted()
        check(sorted.isNotEmpty()) { "no samples" }
        return Summary(
            samples = count,
            meanNanos = sorted.sum().toDouble() / count,
            minNanos = sorted.first(),
            maxNanos = sorted.last(),
            p50Nanos = percentile(sorted, 0.50),
            p95Nanos = percentile(sorted, 0.95),
            p99Nanos = percentile(sorted, 0.99),
            p999Nanos = percentile(sorted, 0.999)
        )
    }

    fun sorted(): LongArray {
        val copy = samples.copyOf(count)
        copy.sort()
        return copy
    }

}

fun percentile(sortedAscending: LongArray, q: Double): Long {
    require(sortedAscending.isNotEmpty()) { "no samples" }
    val index = (ceil(q * sortedAscending.size).toInt() - 1).coerceAtLeast(0)
    return sortedAscending[index]
}

data class Summary(
    val samples: Int,
    val meanNanos: Double,
    val minNanos: Long,
    val maxNanos: Long,
    val p50Nanos: Long,
    val p95Nanos: Long,
    val p99Nanos: Long,
    val p999Nanos: Long
)
