package top.tangge233.netbridge.benchmark.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Immutable persisted snapshot of a transport/channel run configuration.
 *
 * <p>Stores primitives only (the runtime {@code TransportConfig} may use richer types);
 * key names match the current JSON schema.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TransportConfigurationSnapshot(
    val transports: List<String>,
    val cases: List<String>,
    val host: String,
    val port: Int,
    val workers: Int,
    val rttPayloadBytes: Long,
    val throughputDurationMillis: Long,
    val connectIterations: Int,
    val rttMeasuredIterations: Int,
    val startServer: Boolean,
    /** Serialized as an explicit JSON null when unset, matching current output. */
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val nativeLibrary: String? = null
)

/** Immutable persisted snapshot of a minecraft-shaped run configuration. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MinecraftShapedConfigurationSnapshot(
    val transports: List<String>,
    val workloads: List<String>,
    val workers: Int
)
