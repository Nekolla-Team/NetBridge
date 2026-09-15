package top.tangge233.netbridge.benchmarkreport.adapter

import top.tangge233.netbridge.benchmarkreport.dsl.Cell
import top.tangge233.netbridge.benchmarkreport.dsl.report
import top.tangge233.netbridge.benchmarkreport.format.HumanUnits
import top.tangge233.netbridge.benchmarkreport.model.*
import java.time.Instant

object JmhReportAdapter {

    const val SUITE = "jmh"

    fun adapt(
        task: String,
        results: List<JmhResultDto>,
        hasConsoleOutput: Boolean
    ): ReportModel = report {
        this.task = task
        suite = SUITE
        suiteTitle = SuiteTitles.jmh(results)
        executedAt = Instant.now().toString()
        git = null

        val groups = results.groupBy { it.benchmark.substringBeforeLast('.') }
        val maxForks = results.maxOfOrNull { it.forks } ?: 0

        card("Benchmark Methods", results.size.toString())
        card("Classes", groups.size.toString())
        card("Mode", results.firstOrNull()?.mode ?: HumanUnits.MISSING)
        card("Forks", maxForks.toString())

        scope("Mode", results.map { it.mode }.distinct().joinToString())
        scope("Forks", maxForks.toString())
        results.firstOrNull()?.let { first ->
            scope("Warmup", "${first.warmupIterations} \u00d7 ${first.warmupTime}")
            scope("Measurement", "${first.measurementIterations} \u00d7 ${first.measurementTime}")
            scope("Threads", first.threads.toString())
        }
        scope("Total benchmarks", results.size.toString())

        for ((className, classRows) in groups) {
            val simple = className.substringAfterLast('.')
            table(
                title = simple,
                description = "Microbenchmarks defined in $simple",
                headers = listOf(
                    "Method",
                    "Parameters",
                    "Score",
                    "Score (Formatted)",
                    "Error",
                    "Unit"
                ),
                rows = classRows.map { row ->
                    val metric = row.primaryMetric
                    val score = metric?.score ?: Double.NaN
                    val scoreError = metric?.scoreError ?: Double.NaN
                    val unit = metric?.scoreUnit ?: ""
                    listOf(
                        Cell("Method", TextValue(row.benchmark.substringAfterLast('.'))),
                        Cell("Parameters", TextValue(parametersText(row.params))),
                        Cell("Score", DecimalValue(score)),
                        Cell("Score (Formatted)", formattedScore(score, unit)),
                        Cell("Error", SignedDecimalValue(scoreError)),
                        Cell("Unit", TextValue(unit))
                    )
                }
            )
        }

        keyValues(
            "Environment",
            emptyList(),
            note = "No environment metadata available."
        )

        artifact(
            "raw.json",
            "Exact JMH result JSON"
        )
        if (hasConsoleOutput) {
            artifact(
                "jmh-output.txt",
                "JMH human console output"
            )
        }
    }

    private fun parametersText(params: Map<String, String>): String =
        if (params.isEmpty()) {
            "default"
        } else {
            params.entries.sortedBy { it.key }
                .joinToString(", ") { (k, v) -> "$k=$v" }
        }

    private fun formattedScore(
        score: Double,
        unit: String
    ): ReportValue =
        if (unit.startsWith("ns") && !score.isNaN()) {
            DurationValue(score)
        } else {
            TextValue(HumanUnits.decimal(score))
        }

}
