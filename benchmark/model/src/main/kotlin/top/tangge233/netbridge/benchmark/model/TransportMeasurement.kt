package top.tangge233.netbridge.benchmark.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Typed transport/channel measurement rows.
 *
 * <p>Subtypes mirror the current persisted rows; the existing flat {@code name} field
 * distinguishes them (no {@code @type} discriminator is added to the schema). A custom
 * Jackson deserializer dispatches on that field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
sealed interface TransportMeasurement {

    val name: String
    val transport: String

    companion object {

        const val NAME_CONNECT = "connect"
        const val NAME_RTT = "rtt"
        const val NAME_THROUGHPUT = "throughput"
        const val NAME_BIDIRECTIONAL = "bidirectional"
        const val NAME_LOADED_LATENCY = "loaded-latency"

    }

}

/** Latency summary shared by connect/rtt rows and the loaded-latency idle/loaded blocks. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LatencyBlock(
    val samples: Int,
    val meanNanos: Double,
    val minNanos: Long,
    val maxNanos: Long,
    val p50Nanos: Long,
    val p95Nanos: Long,
    val p99Nanos: Long,
    val p999Nanos: Long
)

/** {@code connect} and {@code rtt} rows: latency percentile summary plus CPU accounting. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LatencyMeasurementResult(
    override val name: String,
    override val transport: String,
    val payloadBytes: Int,
    val samples: Int,
    val meanNanos: Double,
    val minNanos: Long,
    val maxNanos: Long,
    val p50Nanos: Long,
    val p95Nanos: Long,
    val p99Nanos: Long,
    val p999Nanos: Long,
    val processCpuNanos: Long
) : TransportMeasurement

/** {@code throughput} row: one-way stream transfer summary. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ThroughputMeasurementResult(
    override val name: String,
    override val transport: String,
    val payloadBytes: Int,
    val durationNanos: Long,
    val payloadBytesTransferred: Long,
    val mibPerSecond: Double,
    val processCpuNanos: Long,
    val integrityError: Boolean
) : TransportMeasurement

/** {@code bidirectional} row: same base as throughput plus directional accounting. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BidirectionalMeasurementResult(
    override val name: String,
    override val transport: String,
    val payloadBytes: Int,
    val durationNanos: Long,
    val payloadBytesTransferred: Long,
    val mibPerSecond: Double,
    val processCpuNanos: Long,
    val integrityError: Boolean,
    val serverToClientBytes: Long,
    val clientToServerBytes: Long,
    val serverToClientMibPerSecond: Double,
    val clientToServerMibPerSecond: Double
) : TransportMeasurement

/**
 * {@code loaded-latency} row (bufferbloat probe): idle and loaded latency blocks plus
 * percentile deltas and the concurrent stream throughput.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LoadedLatencyMeasurementResult(
    override val name: String,
    override val transport: String,
    val payloadBytes: Int,
    val idleSamples: Int,
    val idleMeanNanos: Double,
    val idleMinNanos: Long,
    val idleMaxNanos: Long,
    val idleP50Nanos: Long,
    val idleP95Nanos: Long,
    val idleP99Nanos: Long,
    val idleP999Nanos: Long,
    val loadedSamples: Int,
    val loadedMeanNanos: Double,
    val loadedMinNanos: Long,
    val loadedMaxNanos: Long,
    val loadedP50Nanos: Long,
    val loadedP95Nanos: Long,
    val loadedP99Nanos: Long,
    val loadedP999Nanos: Long,
    val bufferbloatP50Nanos: Long,
    val bufferbloatP99Nanos: Long,
    val processCpuNanos: Long,
    val streamMibPerSecond: Double,
    val integrityError: Boolean
) : TransportMeasurement {

    val idleBlock: LatencyBlock
        get() = LatencyBlock(
            samples = idleSamples,
            meanNanos = idleMeanNanos,
            minNanos = idleMinNanos,
            maxNanos = idleMaxNanos,
            p50Nanos = idleP50Nanos,
            p95Nanos = idleP95Nanos,
            p99Nanos = idleP99Nanos,
            p999Nanos = idleP999Nanos
        )

    val loadedBlock: LatencyBlock
        get() = LatencyBlock(
            samples = loadedSamples,
            meanNanos = loadedMeanNanos,
            minNanos = loadedMinNanos,
            maxNanos = loadedMaxNanos,
            p50Nanos = loadedP50Nanos,
            p95Nanos = loadedP95Nanos,
            p99Nanos = loadedP99Nanos,
            p999Nanos = loadedP999Nanos
        )

}
