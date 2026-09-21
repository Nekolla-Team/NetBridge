package team.nekolla.netbridge.benchmark.model

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
    val processCpuNanos: Long? = null,
    val smallMessageCount: Int,
    val smallMessageMeanNanos: Double? = null,
    val smallMessageP50Nanos: Long? = null,
    val smallMessageP95Nanos: Long? = null,
    val smallMessageP99Nanos: Long? = null,
    val smallMessageP999Nanos: Long? = null,
    val serverToClientBytes: Long? = null,
    val maxScheduleSlipNanos: Long? = null,
    val deadlineMisses: Long? = null
)

/** Real-Minecraft session milestone (see MinecraftBenchmarkRecorder). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MinecraftSessionMilestone(
    val name: String,
    val elapsedNanos: Long? = null,
    val wallNanos: Long? = null,
    val at: String,
    val sessionId: String? = null,
    val attemptId: Int? = null,
    val host: String? = null,
    val port: Int? = null,
    val targetKind: String? = null,
    val targetHash: String? = null,
    val uniqueChunks: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MinecraftSessionEnvironment(
    val os: String,
    val jdkVersion: String,
    val recorder: String
)
