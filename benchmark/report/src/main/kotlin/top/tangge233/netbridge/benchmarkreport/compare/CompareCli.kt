@file:JvmName("CompareCliMain")

package top.tangge233.netbridge.benchmarkreport.compare

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import top.tangge233.netbridge.benchmark.model.*
import top.tangge233.netbridge.benchmarkreport.adapter.JmhJson
import top.tangge233.netbridge.benchmarkreport.adapter.JmhResultDto
import top.tangge233.netbridge.benchmarkreport.format.HumanUnits
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.round

class CompareCli : CliktCommand(name = "compare") {

    private val before by option(
        "--before",
        help = "Raw result document captured before."
    ).path(
        mustExist = true,
        canBeFile = true
    ).required()

    private val after by option(
        "--after",
        help = "Raw result document captured after."
    ).path(
        mustExist = true,
        canBeFile = true
    ).required()

    override fun run() {
        comparePaths(before, after).forEach(::echo)
    }

}

private fun comparePaths(before: Path, after: Path): List<String> =
    compareTexts(
        Files.readString(before, StandardCharsets.UTF_8),
        Files.readString(after, StandardCharsets.UTF_8)
    )

fun compareTexts(beforeText: String, afterText: String): List<String> {
    val beforeIsJmh = JmhJson.looksLikeJmh(beforeText)
    val afterIsJmh = JmhJson.looksLikeJmh(afterText)
    if (beforeIsJmh != afterIsJmh) {
        throw UsageError(
            "Cannot compare a JMH result with a run document; inputs must have the same format."
        )
    }

    return if (beforeIsJmh) {
        compareJmh(JmhJson.read(beforeText), JmhJson.read(afterText))
    } else {
        compareRunDocuments(beforeText, afterText)
    }
}

private fun compareRunDocuments(beforeText: String, afterText: String): List<String> {
    val beforeDoc = BenchmarkJson.readRunDocument(beforeText)
    val afterDoc = BenchmarkJson.readRunDocument(afterText)
    if (beforeDoc.suite != afterDoc.suite) {
        throw UsageError(
            "Cannot compare documents with different suites: " + "'${beforeDoc.suite}' vs '${afterDoc.suite}'."
        )
    }
    return when (beforeDoc) {
        is TransportRunDocument ->
            compareTransport(beforeDoc, afterDoc as TransportRunDocument)

        is MinecraftShapedRunDocument ->
            compareMinecraftShaped(beforeDoc, afterDoc as MinecraftShapedRunDocument)

        else -> throw UsageError(
            "Comparison is not supported for suite '${beforeDoc.suite}'."
        )
    }
}

private fun compareTransport(
    before: TransportRunDocument,
    after: TransportRunDocument
): List<String> =
    compareRows(
        before.results,
        after.results
    ) { transportRowKey(it) }

private fun compareMinecraftShaped(
    before: MinecraftShapedRunDocument,
    after: MinecraftShapedRunDocument
): List<String> =
    compareRows(
        before.results,
        after.results
    ) { shapedRowKey(it) }

private fun shapedRowKey(row: MinecraftShapedMeasurement): String =
    "${row.name} ${row.transport} ${row.payloadBytes}"

private fun <T : Any> compareRows(
    beforeRows: List<T>,
    afterRows: List<T>,
    keyOf: (T) -> String
): List<String> {
    val beforeMap = beforeRows.associateBy(keyOf)
    val afterMap = afterRows.associateBy(keyOf)
    return buildList {
        beforeMap.entries
            .sortedBy { it.key }
            .forEach { (key, before) ->
                val after = afterMap[key]
                if (after == null) {
                    add("row only in before: $key")
                    return@forEach
                }

                val beforeObs = observationsOf(before, key)
                val afterObs = observationsOf(after, key)
                beforeObs
                    .zip(afterObs)
                    .forEach { (first, second) ->
                        if (!sameValue(first.value, second.value)) {
                            add(
                                renderDelta(
                                    second,
                                    first.value,
                                    second.value
                                )
                            )
                        }
                    }
            }

        afterMap.keys
            .sorted()
            .forEach {
                if (it !in beforeMap) {
                    add("row only in after: $it")
                }
            }
    }
}

private fun observationsOf(row: Any, key: String): List<MetricObservation> =
    when (row) {
        is LatencyMeasurementResult ->
            observations(MetricDefs.latency, row, key)

        is ThroughputMeasurementResult ->
            observations(MetricDefs.throughput, row, key)

        is BidirectionalMeasurementResult ->
            observations(MetricDefs.bidirectional, row, key)

        is LoadedLatencyMeasurementResult ->
            observations(MetricDefs.loadedLatency, row, key)

        is MinecraftShapedMeasurement ->
            observations(MetricDefs.minecraftShaped, row, key)

        else -> emptyList()
    }

private fun <T> observations(
    defs: List<MetricDef<T>>,
    row: T,
    key: String
): List<MetricObservation> =
    defs.map {
        MetricObservation(
            key,
            it.label,
            it.kind,
            it.isRelativeDeltaValid,
            it.extract(row)
        )
    }

private fun compareJmh(
    before: List<JmhResultDto>,
    after: List<JmhResultDto>
): List<String> {
    val beforeMap = before.associateBy { jmhRowKey(it) }
    val afterMap = after.associateBy { jmhRowKey(it) }
    val defs = MetricDefs.jmh

    return buildList {
        beforeMap.entries
            .sortedBy { it.key }
            .forEach { (key, beforeRow) ->
                val afterRow = afterMap[key]
                if (afterRow == null) {
                    add("row only in before: $key")
                    return@forEach
                }

                val beforeObs = observations(defs, beforeRow, key)
                val afterObs = observations(defs, afterRow, key)
                beforeObs
                    .zip(afterObs)
                    .forEach { (first, second) ->
                        if (!sameValue(first.value, second.value)) {
                            add(
                                renderDelta(
                                    second,
                                    first.value,
                                    second.value
                                )
                            )
                        }
                    }
            }

        afterMap.keys
            .sorted()
            .forEach { key ->
                if (key !in beforeMap) {
                    add("row only in after: $key")
                }
            }
    }
}

private fun jmhRowKey(row: JmhResultDto): String =
    if (row.params.isEmpty()) {
        row.benchmark
    } else {
        "${row.benchmark} ${
            row.params.entries
                .sortedBy { it.key }
                .joinToString(", ") { (k, v) -> "$k=$v" }
        }"
    }

private fun transportRowKey(row: TransportMeasurement): String =
    when (row) {
        is LatencyMeasurementResult -> rowKey(row.name, row.transport, row.payloadBytes)
        is ThroughputMeasurementResult -> rowKey(row.name, row.transport, row.payloadBytes)
        is BidirectionalMeasurementResult -> rowKey(row.name, row.transport, row.payloadBytes)
        is LoadedLatencyMeasurementResult -> rowKey(row.name, row.transport, row.payloadBytes)
    }

private fun rowKey(
    name: String,
    transport: String,
    payloadBytes: Int
): String =
    "$name $transport $payloadBytes"

private fun renderDelta(
    obs: MetricObservation,
    beforeValue: Double?,
    afterValue: Double?
): String {
    val beforeText = valueText(obs.kind, beforeValue)
    val afterText = valueText(obs.kind, afterValue)
    val delta = if (beforeValue != null && afterValue != null) {
        valueText(obs.kind, afterValue - beforeValue)
    } else {
        HumanUnits.MISSING
    }

    val percent =
        if (obs.relativeValid
            && beforeValue != null
            && afterValue != null
            && beforeValue != 0.0
            && beforeValue.isFinite()
            && afterValue.isFinite()
        ) {
            val change = (afterValue - beforeValue) / beforeValue * 100.0
            " (${HumanUnits.percent(change)})"
        } else {
            ""
        }

    val key = obs.key.padEnd(34)
    val label = obs.label.padEnd(22)
    return "$key $label before: $beforeText | after: $afterText | delta: $delta$percent"
}

private fun valueText(
    kind: MetricKind,
    value: Double?
): String =
    when (value) {
        null -> HumanUnits.MISSING
        else -> when (kind) {
            MetricKind.DURATION -> HumanUnits.nanos(value)
            MetricKind.BYTES -> HumanUnits.bytes(value.toLong())
            MetricKind.RATE -> HumanUnits.throughput(value)
            MetricKind.DECIMAL -> HumanUnits.decimal(value)
            MetricKind.COUNT -> if (value == round(value)) {
                value.toLong().toString()
            } else {
                "%.2f".format(value)
            }
        }
    }

private fun sameValue(a: Double?, b: Double?): Boolean =
    when {
        a == null || b == null -> a == b
        a.isNaN() && b.isNaN() -> true
        else -> a == b
    }

fun main(args: Array<String>) = CompareCli().main(args)
