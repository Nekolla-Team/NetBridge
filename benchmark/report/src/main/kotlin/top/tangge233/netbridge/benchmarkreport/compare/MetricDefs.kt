package top.tangge233.netbridge.benchmarkreport.compare

import top.tangge233.netbridge.benchmark.model.*
import top.tangge233.netbridge.benchmarkreport.adapter.JmhResultDto

enum class MetricKind {

    DURATION,
    BYTES,
    RATE,
    DECIMAL,
    COUNT

}

data class MetricDef<T>(
    val label: String,
    val kind: MetricKind,
    val isRelativeDeltaValid: Boolean,
    val extract: (T) -> Double
)

data class MetricObservation(
    val key: String,
    val label: String,
    val kind: MetricKind,
    val relativeValid: Boolean,
    val value: Double?
)

private fun <T> def(
    label: String,
    kind: MetricKind,
    relative: Boolean,
    extract: (T) -> Double
): MetricDef<T> = MetricDef(label, kind, relative, extract)

object MetricDefs {

    val latency: List<MetricDef<LatencyMeasurementResult>> = listOf(
        def("Samples", MetricKind.COUNT, false) { it.samples.toDouble() },
        def("Mean", MetricKind.DURATION, true) { it.meanNanos },
        def("P50", MetricKind.DURATION, true) { it.p50Nanos.toDouble() },
        def("P95", MetricKind.DURATION, true) { it.p95Nanos.toDouble() },
        def("P99", MetricKind.DURATION, true) { it.p99Nanos.toDouble() },
        def("P99.9", MetricKind.DURATION, true) { it.p999Nanos.toDouble() },
        def("Min", MetricKind.DURATION, true) { it.minNanos.toDouble() },
        def("Max", MetricKind.DURATION, true) { it.maxNanos.toDouble() },
        def("CPU Time", MetricKind.DURATION, false) { it.processCpuNanos.toDouble() }
    )

    val throughput: List<MetricDef<ThroughputMeasurementResult>> = listOf(
        def("Duration", MetricKind.DURATION, true) { it.durationNanos.toDouble() },
        def("Data Transferred", MetricKind.BYTES, true) { it.payloadBytesTransferred.toDouble() },
        def("Throughput", MetricKind.RATE, true) { it.mibPerSecond },
        def("CPU Time", MetricKind.DURATION, false) { it.processCpuNanos.toDouble() }
    )

    val bidirectional: List<MetricDef<BidirectionalMeasurementResult>> = listOf(
        def("Duration", MetricKind.DURATION, true) { it.durationNanos.toDouble() },
        def("Client->Server", MetricKind.BYTES, true) { it.clientToServerBytes.toDouble() },
        def("Server->Client", MetricKind.BYTES, true) { it.serverToClientBytes.toDouble() },
        def("Client->Server Throughput", MetricKind.RATE, true) { it.clientToServerMibPerSecond },
        def("Server->Client Throughput", MetricKind.RATE, true) { it.serverToClientMibPerSecond },
        def("CPU Time", MetricKind.DURATION, false) { it.processCpuNanos.toDouble() }
    )

    val loadedLatency: List<MetricDef<LoadedLatencyMeasurementResult>> = listOf(
        def("Idle Mean", MetricKind.DURATION, true) { it.idleMeanNanos },
        def("Idle P50", MetricKind.DURATION, true) { it.idleP50Nanos.toDouble() },
        def("Idle P99", MetricKind.DURATION, true) { it.idleP99Nanos.toDouble() },
        def("Loaded Mean", MetricKind.DURATION, true) { it.loadedMeanNanos },
        def("Loaded P50", MetricKind.DURATION, true) { it.loadedP50Nanos.toDouble() },
        def("Loaded P99", MetricKind.DURATION, true) { it.loadedP99Nanos.toDouble() },
        def("Bufferbloat P50", MetricKind.DURATION, true) { it.bufferbloatP50Nanos.toDouble() },
        def("Bufferbloat P99", MetricKind.DURATION, true) { it.bufferbloatP99Nanos.toDouble() },
        def("Stream Throughput", MetricKind.RATE, true) { it.streamMibPerSecond },
        def("CPU Time", MetricKind.DURATION, false) { it.processCpuNanos.toDouble() }
    )

    val minecraftShaped: List<MetricDef<MinecraftShapedMeasurement>> = listOf(
        def("Messages", MetricKind.COUNT, false) { it.messages.toDouble() },
        def("Duration", MetricKind.DURATION, true) { it.durationNanos.toDouble() },
        def("Throughput", MetricKind.RATE, true) { it.mibPerSecond },
        def("CPU Time", MetricKind.DURATION, false) { it.processCpuNanos.toDouble() }
    )

    val jmh: List<MetricDef<JmhResultDto>> = listOf(
        def("Score", MetricKind.DURATION, true) {
            it.primaryMetric?.score ?: Double.NaN
        },
        def("Score Error", MetricKind.DECIMAL, false) {
            it.primaryMetric?.scoreError ?: Double.NaN
        },
        def("P50", MetricKind.DURATION, true) {
            it.primaryMetric?.scorePercentiles?.get("50.0") ?: Double.NaN
        },
        def("P95", MetricKind.DURATION, true) {
            it.primaryMetric?.scorePercentiles?.get("95.0") ?: Double.NaN
        },
        def("P99", MetricKind.DURATION, true) {
            it.primaryMetric?.scorePercentiles?.get("99.0") ?: Double.NaN
        }
    )

}
