package top.tangge233.netbridge.benchmarkreport.adapter

import top.tangge233.netbridge.benchmark.model.MinecraftSessionRunDocument
import top.tangge233.netbridge.benchmark.model.MinecraftShapedConfigurationSnapshot
import top.tangge233.netbridge.benchmark.model.MinecraftShapedRunDocument
import top.tangge233.netbridge.benchmarkreport.dsl.Cell
import top.tangge233.netbridge.benchmarkreport.dsl.KeyValue
import top.tangge233.netbridge.benchmarkreport.dsl.report
import top.tangge233.netbridge.benchmarkreport.model.*

object MinecraftShapedReportAdapter {

    fun adapt(task: String, doc: MinecraftShapedRunDocument): ReportModel =
        report {
            this.task = task
            suite = doc.suite
            suiteTitle = SuiteTitles.of(doc.suite)
            executedAt = doc.environment.timestamp.toString()
            git = gitLabel(doc.environment.gitCommit, doc.environment.gitDirty)

            card(
                "Suite",
                doc.suite
            )
            card(
                "Transports",
                doc.results.map { it.transport }.distinct().size.toString()
            )
            card(
                "Result Rows",
                doc.results.size.toString()
            )
            card(
                "Integrity Errors",
                "0",
                "No integrity signal"
            )

            scope("Transports", doc.configuration.transports.pretty())
            scope("Workloads", doc.configuration.workloads.pretty())
            scope("Workers", doc.configuration.workers.toString())

            val order = doc.configuration.workloads.withIndex()
                .associate { (i, name) -> name to i }
            val ordered = doc.results.sortedWith(
                compareBy(
                    { order[it.name] ?: -1 },
                    { it.transport }
                )
            )
            table(
                title = "Minecraft Workload Execution",
                description = "Replay of synthetic Minecraft traffic profiles.",
                headers = listOf(
                    "Workload",
                    "Transport",
                    "Messages",
                    "Payload",
                    "Duration",
                    "Throughput",
                    "Small Msg P50",
                    "Small Msg P99",
                    "CPU Time"
                ),
                rows = ordered.map {
                    listOf(
                        Cell("Workload", TextValue(it.name)),
                        Cell("Transport", TextValue(it.transport)),
                        Cell("Messages", IntegerValue(it.messages)),
                        Cell("Payload", BytesValue(it.payloadBytes)),
                        Cell("Duration", DurationValue.ofNanos(it.durationNanos)),
                        Cell("Throughput", ThroughputValue(it.mibPerSecond)),
                        Cell(
                            "Small Msg P50",
                            smallDuration(it.smallMessageCount, it.smallMessageP50Nanos)
                        ),
                        Cell(
                            "Small Msg P99",
                            smallDuration(it.smallMessageCount, it.smallMessageP99Nanos)
                        ),
                        Cell("CPU Time", DurationValue.ofNanos(it.processCpuNanos))
                    )
                }
            )

            keyValues(
                "Environment",
                environmentEntries(doc.environment)
            )
            keyValues(
                "Complete Configuration",
                minecraftShapedConfigurationEntries(doc.configuration)
            )

            artifact(
                "raw.json",
                "Canonical ResultEnvelope JSON"
            )
        }
}

private fun minecraftShapedConfigurationEntries(
    config: MinecraftShapedConfigurationSnapshot
): List<KeyValue> =
    listOf(
        KeyValue("transports", config.transports.pretty()),
        KeyValue("workloads", config.workloads.pretty()),
        KeyValue("workers", config.workers.toString())
    )

private fun smallDuration(count: Int, nanos: Long?): ReportValue =
    if (count > 0 && nanos != null) {
        DurationValue.ofNanos(nanos)
    } else {
        MissingValue()
    }

object MinecraftSessionReportAdapter {

    fun adapt(task: String, doc: MinecraftSessionRunDocument): ReportModel =
        report {
            this.task = task
            suite = doc.suite
            suiteTitle = SuiteTitles.of(doc.suite)
            executedAt = doc.startedAt
            git = null

            card("Suite", doc.suite)
            card("Milestones", doc.results.size.toString())
            card("Session started", doc.startedAt)

            scope("OS", doc.environment.os)
            scope("JDK Version", doc.environment.jdkVersion)
            scope("Recorder", doc.environment.recorder)

            table(
                title = "Session Milestones",
                description = "Milestones recorded during a live Minecraft client session.",
                headers = listOf(
                    "Milestone",
                    "At",
                    "Host",
                    "Port",
                    "Unique Chunks",
                    "Wall Time"
                ),
                rows = doc.results.map { m ->
                    listOf(
                        Cell("Milestone", TextValue(m.name)),
                        Cell("At", TextValue(m.at)),
                        Cell("Host", m.host?.let { TextValue(it) } ?: MissingValue()),
                        Cell(
                            "Port",
                            m.port?.let { IntegerValue(it.toLong()) } ?: MissingValue()
                        ),
                        Cell(
                            "Unique Chunks",
                            m.uniqueChunks?.let { IntegerValue(it.toLong()) } ?: MissingValue()
                        ),
                        Cell("Wall Time", DurationValue.ofNanos(m.wallNanos))
                    )
                }
            )

            artifact("raw.json", "Canonical ResultEnvelope JSON")
        }

}
