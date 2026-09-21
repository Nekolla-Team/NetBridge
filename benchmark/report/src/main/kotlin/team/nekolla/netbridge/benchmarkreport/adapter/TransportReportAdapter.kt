package team.nekolla.netbridge.benchmarkreport.adapter

import team.nekolla.netbridge.benchmark.model.*
import team.nekolla.netbridge.benchmarkreport.dsl.Cell
import team.nekolla.netbridge.benchmarkreport.dsl.report
import team.nekolla.netbridge.benchmarkreport.dsl.section
import team.nekolla.netbridge.benchmarkreport.format.HumanUnits
import team.nekolla.netbridge.benchmarkreport.model.*

private val latencyHeaders = listOf(
    "Transport",
    "Payload",
    "Samples",
    "Mean",
    "P50",
    "P95",
    "P99",
    "P99.9",
    "Min",
    "Max",
    "CPU Time"
)

private fun <T : TransportMeasurement> List<T>.sortedByTransport(
    payloadBytes: (T) -> Int
): List<T> = sortedWith(compareBy({ it.transport }, payloadBytes))

private const val P95_MIN_SAMPLES = 20
private const val P99_MIN_SAMPLES = 100
private const val P999_MIN_SAMPLES = 1_000

private fun percentileCell(
    label: String,
    valueNanos: Long,
    samples: Int,
    minSamples: Int
): Cell =
    Cell(
        label,
        if (samples >= minSamples) {
            DurationValue.ofNanos(valueNanos)
        } else {
            MissingValue()
        }
    )

private fun latencyCells(
    transport: String,
    payloadBytes: Long,
    cpuNanos: Long?,
    block: LatencyBlock
): List<Cell> = listOf(
    Cell("Transport", TextValue(transport)),
    Cell("Payload", BytesValue(payloadBytes)),
    Cell("Samples", IntegerValue(block.samples.toLong())),
    Cell("Mean", DurationValue(block.meanNanos)),
    Cell("P50", DurationValue.ofNanos(block.p50Nanos)),
    percentileCell("P95", block.p95Nanos, block.samples, P95_MIN_SAMPLES),
    percentileCell("P99", block.p99Nanos, block.samples, P99_MIN_SAMPLES),
    percentileCell("P99.9", block.p999Nanos, block.samples, P999_MIN_SAMPLES),
    Cell("Min", DurationValue.ofNanos(block.minNanos)),
    Cell("Max", DurationValue.ofNanos(block.maxNanos)),
    Cell(
        "CPU Time",
        cpuNanos?.let { DurationValue.ofNanos(it) } ?: MissingValue()
    )
)

private fun latencyRow(r: LatencyMeasurementResult): List<Cell> =
    latencyCells(
        r.transport,
        r.payloadBytes.toLong(),
        r.processCpuNanos,
        LatencyBlock(
            samples = r.samples,
            meanNanos = r.meanNanos,
            minNanos = r.minNanos,
            maxNanos = r.maxNanos,
            p50Nanos = r.p50Nanos,
            p95Nanos = r.p95Nanos,
            p99Nanos = r.p99Nanos,
            p999Nanos = r.p999Nanos
        )
    )

private fun latencySection(
    name: String,
    rows: List<LatencyMeasurementResult>
): Section {
    val isConnect = name == TransportMeasurement.NAME_CONNECT
    return section {
        title = if (isConnect) "Connection Latency" else "Round-Trip Time (RTT)"
        description = if (isConnect) {
            "Latency to establish a transport connection."
        } else {
            "Ping-pong latency with strict request/response correlation."
        }
        headers = latencyHeaders
        this.rows = rows
            .sortedByTransport { it.payloadBytes }
            .map { latencyRow(it) }
    }
}

private fun throughputSections(
    rows: List<ThroughputMeasurementResult>
): List<Section> =
    listOf(
        section {
            title = "Streaming Throughput"
            description = "One-way stream transfer summary."
            headers = listOf(
                "Transport",
                "Payload",
                "Duration",
                "Data Transferred",
                "Throughput",
                "CPU Time",
                "Integrity"
            )
            this.rows = rows
                .sortedByTransport { it.payloadBytes }
                .map {
                    listOf(
                        Cell("Transport", TextValue(it.transport)),
                        Cell("Payload", BytesValue(it.payloadBytes.toLong())),
                        Cell("Duration", DurationValue.ofNanos(it.durationNanos)),
                        Cell("Data Transferred", BytesValue(it.payloadBytesTransferred)),
                        Cell("Throughput", ThroughputValue(it.mibPerSecond)),
                        Cell(
                            "CPU Time",
                            it.processCpuNanos?.let { cpu -> DurationValue.ofNanos(cpu) }
                                ?: MissingValue()
                        ),
                        Cell("Integrity", StatusValue(ok = !it.integrityError))
                    )
                }
        }
    )

private fun bidirectionalSections(
    rows: List<BidirectionalMeasurementResult>
): List<Section> =
    listOf(
        section {
            title = "Bidirectional Throughput"
            description = "Simultaneous two-way stream transfer summary."
            headers = listOf(
                "Transport",
                "Payload",
                "Duration",
                "Client->Server",
                "Server->Client",
                "Client->Server Throughput",
                "Server->Client Throughput",
                "Integrity"
            )
            this.rows = rows
                .sortedByTransport { it.payloadBytes }
                .map {
                    listOf(
                        Cell("Transport", TextValue(it.transport)),
                        Cell("Payload", BytesValue(it.payloadBytes.toLong())),
                        Cell("Duration", DurationValue.ofNanos(it.durationNanos)),
                        Cell("Client->Server", BytesValue(it.clientToServerBytes)),
                        Cell("Server->Client", BytesValue(it.serverToClientBytes)),
                        Cell(
                            "Client->Server Throughput",
                            ThroughputValue(it.clientToServerMibPerSecond)
                        ),
                        Cell(
                            "Server->Client Throughput",
                            ThroughputValue(it.serverToClientMibPerSecond)
                        ),
                        Cell("Integrity", StatusValue(ok = !it.integrityError))
                    )
                }
        }
    )

private fun loadedLatencySections(
    rows: List<LoadedLatencyMeasurementResult>
): List<Section> {
    val ordered = rows.sortedByTransport { it.payloadBytes }
    return listOf(
        blockSection(
            title = "Loaded Latency \u2014 Idle",
            description = "Latency observed while no traffic is being transferred.",
            rows = ordered,
            block = { it.idleBlock }
        ),
        blockSection(
            title = "Loaded Latency \u2014 Loaded",
            description = "Latency observed while a concurrent stream transfers data.",
            rows = ordered,
            block = { it.loadedBlock }
        ),
        section {
            title = "Bufferbloat"
            description = "P50/P99 latency observed under load together with the concurrent stream throughput."
            headers = listOf(
                "Transport",
                "Payload",
                "P50",
                "P99",
                "Stream Throughput",
                "Integrity"
            )
            this.rows = ordered.map {
                listOf(
                    Cell("Transport", TextValue(it.transport)),
                    Cell("Payload", BytesValue(it.payloadBytes.toLong())),
                    Cell("P50", DurationValue.ofNanos(it.bufferbloatP50Nanos)),
                    if (minOf(it.idleSamples, it.loadedSamples) >= P99_MIN_SAMPLES) {
                        Cell("P99", DurationValue.ofNanos(it.bufferbloatP99Nanos))
                    } else {
                        Cell("P99", MissingValue())
                    },
                    Cell("Stream Throughput", ThroughputValue(it.streamMibPerSecond)),
                    Cell("Integrity", StatusValue(ok = !it.integrityError))
                )
            }
        }
    )
}

private fun blockSection(
    title: String,
    description: String,
    rows: List<LoadedLatencyMeasurementResult>,
    block: (LoadedLatencyMeasurementResult) -> LatencyBlock
): Section = section {
    this.title = title
    this.description = description
    headers = latencyHeaders
    this.rows = rows.map {
        latencyCells(
            it.transport,
            it.payloadBytes.toLong(),
            it.processCpuNanos,
            block(it)
        )
    }
}

private inline fun <reified T : TransportMeasurement> List<TransportMeasurement>.resultsOf(
    name: String
): List<T> =
    filterIsInstance<T>().filter { it.name == name }

private fun measurementSections(
    results: List<TransportMeasurement>
): List<Section> =
    buildList {
        results.resultsOf<LatencyMeasurementResult>(
            TransportMeasurement.NAME_CONNECT
        ).run {
            if (isNotEmpty()) {
                add(latencySection(TransportMeasurement.NAME_CONNECT, this))
            }
        }

        results.resultsOf<LatencyMeasurementResult>(
            TransportMeasurement.NAME_RTT
        ).run {
            if (isNotEmpty()) {
                add(latencySection(TransportMeasurement.NAME_RTT, this))
            }
        }

        results.resultsOf<ThroughputMeasurementResult>(
            TransportMeasurement.NAME_THROUGHPUT
        ).run {
            if (isNotEmpty()) {
                addAll(throughputSections(this))
            }
        }

        results.resultsOf<BidirectionalMeasurementResult>(
            TransportMeasurement.NAME_BIDIRECTIONAL
        ).run {
            if (isNotEmpty()) {
                addAll(bidirectionalSections(this))
            }
        }

        results.resultsOf<LoadedLatencyMeasurementResult>(
            TransportMeasurement.NAME_LOADED_LATENCY
        ).run {
            if (isNotEmpty()) {
                addAll(loadedLatencySections(this))
            }
        }
    }

private fun transportReport(
    task: String,
    doc: TransportRunDocument
): ReportModel =
    report {
        val results = doc.results
        this.task = task
        suite = doc.suite
        suiteTitle = SuiteTitles.of(doc.suite)
        executedAt = doc.environment.timestamp.toString()
        git = gitLabel(doc.environment.gitCommit, doc.environment.gitDirty)

        val integrityErrors = results.count {
            when (it) {
                is ThroughputMeasurementResult -> it.integrityError
                is BidirectionalMeasurementResult -> it.integrityError
                is LoadedLatencyMeasurementResult -> it.integrityError
                else -> false
            }
        }

        card(
            "Suite",
            doc.suite
        )
        card(
            "Transports",
            results
                .map { it.transport }
                .distinct().size.toString()
        )
        card(
            "Result Rows",
            results.size.toString()
        )
        card(
            "Integrity Errors",
            integrityErrors.toString(),
            if (integrityErrors == 0) {
                "No integrity error reported by available checks"
            } else {
                "$integrityErrors row(s) with a payload mismatch"
            }
        )

        scope("Transports", doc.configuration.transports.pretty())
        val cases = doc.configuration.cases.ifEmpty {
            results
                .map { it.name }
                .distinct()
        }
        scope("Cases", cases.pretty())
        scope("Host", doc.configuration.host)
        scope("Port", doc.configuration.port.toString())
        scope("Workers", doc.configuration.workers.toString())
        scope("Rtt payloads", doc.configuration.rttPayloads.pretty())
        scope(
            "Throughput duration millis",
            doc.configuration.throughputDurationMillis.toString()
        )
        scope("Connect iterations", doc.configuration.connectIterations.toString())
        scope("Rtt measured iterations", doc.configuration.rttMeasuredIterations.toString())
        scope("Start server", doc.configuration.startServer.toString())
        scope("Native library", doc.configuration.nativeLibrary ?: HumanUnits.MISSING)

        tableSections(measurementSections(results))

        keyValues(
            "Environment",
            environmentEntries(doc.environment)
        )
        keyValues(
            "Complete Configuration",
            transportConfigurationEntries(doc.configuration)
        )

        artifact(
            "raw.json",
            "Canonical ResultEnvelope JSON"
        )
    }

object TransportReportAdapter {

    fun adapt(task: String, doc: TransportRunDocument): ReportModel {
        check(doc.suite == BenchmarkSuite.TRANSPORT.id) {
            "adapter requires a transport document, got suite '${doc.suite}'"
        }
        return transportReport(task, doc)
    }

}

object ChannelReportAdapter {

    fun adapt(task: String, doc: TransportRunDocument): ReportModel {
        check(doc.suite == BenchmarkSuite.CHANNEL.id) {
            "adapter requires a channel document, got suite '${doc.suite}'"
        }
        return transportReport(task, doc)
    }

}
