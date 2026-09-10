package top.tangge233.netbridge.benchmark.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** Top-level typed result document produced by a benchmark invocation. */
sealed interface RunDocument {

    val suite: String

}

/** Envelope for {@code transport} and {@code channel} suites. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TransportRunDocument(
    override val suite: String,
    val environment: BenchmarkEnvironment,
    val configuration: TransportConfigurationSnapshot,
    val results: List<TransportMeasurement>
) : RunDocument

/** Envelope for the {@code minecraft-shaped} suite. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MinecraftShapedRunDocument(
    override val suite: String,
    val environment: BenchmarkEnvironment,
    val configuration: MinecraftShapedConfigurationSnapshot,
    val results: List<MinecraftShapedMeasurement>
) : RunDocument

/** Envelope for the real-Minecraft recorder suite. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MinecraftSessionRunDocument(
    override val suite: String,
    val environment: MinecraftSessionEnvironment,
    val startedAt: String,
    val results: List<MinecraftSessionMilestone>
) : RunDocument
