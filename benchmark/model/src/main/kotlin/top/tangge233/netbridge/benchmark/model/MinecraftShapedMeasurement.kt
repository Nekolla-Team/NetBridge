package top.tangge233.netbridge.benchmark.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Minecraft-shaped workload result row. Small-message latency metrics are present only when
 * [smallMessageCount] is greater than zero (matching the current schema).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MinecraftShapedMeasurement(
    val name: String,
    val transport: String,
    val payloadBytes: Long,
    val messages: Long,
    val durationNanos: Long,
    val completionTimeNanos: Long,
    val mibPerSecond: Double,
    val processCpuNanos: Long,
    val smallMessageCount: Int,
    val smallMessageMeanNanos: Double? = null,
    val smallMessageP50Nanos: Long? = null,
    val smallMessageP95Nanos: Long? = null,
    val smallMessageP99Nanos: Long? = null,
    val smallMessageP999Nanos: Long? = null
)

/** Real-Minecraft session milestone (see MinecraftBenchmarkRecorder). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MinecraftSessionMilestone(
    val name: String,
    val wallNanos: Long,
    val at: String,
    val host: String? = null,
    val port: Int? = null,
    val uniqueChunks: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MinecraftSessionEnvironment(
    val os: String,
    val jdkVersion: String,
    val recorder: String
)
