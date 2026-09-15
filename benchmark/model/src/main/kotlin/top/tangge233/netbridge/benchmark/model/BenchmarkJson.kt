package top.tangge233.netbridge.benchmark.model

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Single well-configured Jackson 3 mapper for the persisted benchmark schema.
 *
 * <p>Registers the Kotlin module (data-class support) and omits null properties by default.
 * Use the helpers here for reading and writing run documents instead of raw mapper calls.
 */
object BenchmarkJson {

    val mapper: ObjectMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .changeDefaultPropertyInclusion {
            it.withValueInclusion(JsonInclude.Include.NON_NULL)
        }
        .build()

    fun writeString(value: Any): String = mapper.writeValueAsString(value)

    fun writeFile(target: Path, value: Any) {
        val json = writeString(value) + "\n"
        writeTextAtomically(target, json)
    }

    /** Writes [text] to [target] by writing a sibling temp file then an atomic rename. */
    private fun writeTextAtomically(target: Path, text: String) {
        target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, text, StandardCharsets.UTF_8)
        try {
            Files.move(
                tmp,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmp,
                target,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    fun readNode(text: String): JsonNode = mapper.readTree(text)

    fun readNode(path: Path): JsonNode {
        val text = Files.readString(path, StandardCharsets.UTF_8)
        return readNode(text)
    }

    /**
     * Reads any persisted run document and returns its typed envelope. The concrete type is
     * chosen from the top-level {@code suite} field; transport measurement rows are dispatched
     * on their existing {@code name} field (no schema discriminator is added).
     */
    fun readRunDocument(path: Path): RunDocument = readRunDocument(readNode(path))

    fun readRunDocument(text: String): RunDocument = readRunDocument(readNode(text))

    private fun readRunDocument(root: JsonNode): RunDocument {
        val suite = root.get("suite")?.asString()
            ?: throw IOException("no suite field in document")

        return when (suite) {
            BenchmarkSuite.TRANSPORT.id,
            BenchmarkSuite.CHANNEL.id -> TransportRunDocument(
                suite = suite,
                environment = readEnvironment(root.path("environment")),
                configuration = readConfiguration(root.path("configuration")),
                results = readTransportMeasurements(root.path("results"))
            )

            BenchmarkSuite.MINECRAFT_SHAPED.id -> root.toValue<MinecraftShapedRunDocument>()

            BenchmarkSuite.MINECRAFT.id -> root.toValue<MinecraftSessionRunDocument>()

            else -> throw IOException("unsupported suite '$suite' in document")
        }
    }

    private fun readEnvironment(node: JsonNode): BenchmarkEnvironment =
        node.toValue<BenchmarkEnvironment>()

    private fun readConfiguration(node: JsonNode): TransportConfigurationSnapshot =
        node.toValue<TransportConfigurationSnapshot>()

    private fun readTransportMeasurements(node: JsonNode): List<TransportMeasurement> =
        buildList {
            node.forEach { add(nodeToMeasurement(it)) }
        }

    private fun nodeToMeasurement(row: JsonNode): TransportMeasurement {
        val name = row.get("name")?.asString()
            ?: throw IOException("transport measurement without a name field")
        return when (name) {
            TransportMeasurement.NAME_CONNECT,
            TransportMeasurement.NAME_RTT ->
                row.toValue<LatencyMeasurementResult>()

            TransportMeasurement.NAME_THROUGHPUT ->
                row.toValue<ThroughputMeasurementResult>()

            TransportMeasurement.NAME_BIDIRECTIONAL ->
                row.toValue<BidirectionalMeasurementResult>()

            TransportMeasurement.NAME_LOADED_LATENCY ->
                row.toValue<LoadedLatencyMeasurementResult>()

            else -> throw IOException("unknown transport measurement name: $name")
        }
    }

    fun readInvocationManifest(path: Path): InvocationManifest =
        readNode(path).toValue()

    fun writeInvocationManifest(path: Path, manifest: InvocationManifest) {
        writeFile(path, manifest)
    }

    private inline fun <reified T> JsonNode.toValue(): T =
        mapper.treeToValue(this, T::class.java)

}
