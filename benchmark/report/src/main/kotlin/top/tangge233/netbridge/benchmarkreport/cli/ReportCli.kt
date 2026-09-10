@file:JvmName("ReportCliMain")

package top.tangge233.netbridge.benchmarkreport.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import top.tangge233.netbridge.benchmark.model.BenchmarkJson
import top.tangge233.netbridge.benchmarkreport.adapter.JmhReportAdapter
import top.tangge233.netbridge.benchmarkreport.adapter.ReportPipeline
import java.nio.file.Files
import java.nio.file.Path

class ReportCli : CliktCommand(name = "report") {

    private val task by option(
        "--task",
        help = "User-facing task name shown in the report header."
    )
    private val suite by option(
        "--suite",
        help = "Benchmark suite id: jmh|transport|channel|minecraft-shaped|minecraft"
    )
    private val raw by option(
        "--raw",
        help = "Explicit raw JSON result file to render."
    ).path(
        mustExist = true,
        canBeFile = true
    )
    private val manifest by option(
        "--manifest",
        help = "Invocation manifest JSON (task/suite/rawResult)."
    ).path(
        mustExist = true,
        canBeFile = true
    )
    private val jmhOutput by option(
        "--jmh-output",
        help = "Optional JMH console output copied next to the report."
    ).path(
        mustExist = true,
        canBeFile = true
    )
    private val reportDir by option(
        "--report-dir",
        help = "Directory to write index.html and report-meta.json into."
    ).path(canBeFile = false).required()

    override fun run() {
        val inputs = resolveInputs()
        validateSuite(inputs.suite)
        if (!Files.isRegularFile(inputs.raw)) {
            throw UsageError("raw result file does not exist: ${inputs.raw}")
        }
        if (inputs.suite == JmhReportAdapter.SUITE && jmhOutput == null) {
            throw UsageError("--jmh-output is required when rendering a jmh suite.")
        }

        val reportMeta = ReportPipeline.render(
            inputs.suite,
            inputs.task,
            inputs.raw,
            jmhOutput,
            reportDir
        )
        echo("report written: ${reportDir.resolve("index.html")}")
        echo(
            "report meta: task=${reportMeta.task} suite=${reportMeta.suite} " +
                    "resultRows=${reportMeta.resultRows}"
        )
    }

    private data class ResolvedInputs(
        val task: String,
        val suite: String,
        val raw: Path
    )

    private fun resolveInputs(): ResolvedInputs {
        val manifestPath = manifest
        val rawPath = raw
        val suiteOption = suite
        val taskOption = task
        return when {
            manifestPath != null -> {
                val m = BenchmarkJson.readInvocationManifest(manifestPath)
                ResolvedInputs(
                    taskOption ?: m.task,
                    m.suite,
                    Path.of(m.rawResult)
                )
            }

            rawPath != null -> {
                if (suiteOption == null) {
                    throw UsageError("--suite is required when --raw is used.")
                }
                if (taskOption == null) {
                    throw UsageError("--task is required when --raw is used.")
                }
                ResolvedInputs(
                    taskOption,
                    suiteOption,
                    rawPath
                )
            }

            else -> throw UsageError(
                "Exactly one of --manifest or --raw must be provided."
            )
        }
    }

    private fun validateSuite(suite: String) {
        if (!ReportPipeline.isSupportedSuite(suite)) {
            throw UsageError(
                "--suite must be one of jmh|transport|channel|minecraft-shaped|minecraft, got '$suite'."
            )
        }
    }
}

fun main(args: Array<String>) = ReportCli().main(args)
