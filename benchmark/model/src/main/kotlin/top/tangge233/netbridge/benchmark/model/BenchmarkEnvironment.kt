package top.tangge233.netbridge.benchmark.model

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * Typed environment snapshot persisted at the top of every result document.
 *
 * <p>Unknown keys encountered when reading a newer/foreign document are preserved in
 * [unknownFields] so a round trip never loses data. Field names and units match the current
 * JSON schema exactly; nullable fields are omitted from output when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
class BenchmarkEnvironment(
    val suiteVersion: String = "1",
    val timestamp: Instant,
    val gitCommit: String? = null,
    val gitDirty: Boolean? = null,
    val os: String,
    val osVersion: String,
    val arch: String,
    val availableProcessors: Int,
    val jdkVendor: String,
    val jdkVersion: String,
    val jvmName: String,
    val jvmVersion: String,
    val maxHeapBytes: Long,
    val rustVersion: String? = null,
    val nativeWorkerCount: Int,
    val nativeLibrary: String? = null,
    val nativeLibrarySha256: String? = null
) {

    private val extraFields = linkedMapOf<String, JsonNode>()

    @JsonAnySetter
    fun readUnknown(name: String, value: JsonNode) {
        extraFields[name] = value
    }

    @JsonAnyGetter
    fun unknownFields(): Map<String, JsonNode> = extraFields

    override fun toString(): String =
        "BenchmarkEnvironment(timestamp=$timestamp, git=$gitCommit, gitDirty=$gitDirty, " +
                "os=$os $osVersion/$arch, jdk=$jdkVendor $jdkVersion, " +
                "nativeWorkerCount=$nativeWorkerCount)"
}
