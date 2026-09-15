package top.tangge233.netbridge.benchmark.common

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.ThreadLocalRandom

class LatencySamplesTest {

    @Test
    fun summaryPercentilesAreMonotonicOnSortedData() {
        val samples = LatencySamples()
        repeat(1000) {
            samples.add(ThreadLocalRandom.current().nextLong(10_000))
        }
        val sorted = samples.sorted()
        (1 until sorted.size)
            .forEach {
                assertTrue(
                    sorted[it] >= sorted[it - 1],
                    "samples must be sorted ascending"
                )
            }
        val s = samples.summary()
        assertEquals(1000, s.samples)
        assertTrue(s.minNanos <= s.p50Nanos)
        assertTrue(s.p50Nanos <= s.p95Nanos)
        assertTrue(s.p95Nanos <= s.p99Nanos)
        assertTrue(s.p99Nanos <= s.p999Nanos)
        assertTrue(s.p999Nanos <= s.maxNanos)
    }

    @Test
    fun percentileNearestRank() {
        val sorted = longArrayOf(1, 2, 3, 4)
        assertEquals(
            2,
            percentile(sorted, 0.50)
        )
        assertEquals(
            4,
            percentile(sorted, 0.999)
        )
    }

    @Test
    fun emptySamplesRejected() {
        val samples = LatencySamples()
        assertThrows(IllegalStateException::class.java) {
            samples.summary()
        }
    }

}
