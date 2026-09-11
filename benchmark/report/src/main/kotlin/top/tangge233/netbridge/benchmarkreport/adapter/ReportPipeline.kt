package top.tangge233.netbridge.benchmarkreport.adapter

import gg.jte.ContentType
import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import top.tangge233.netbridge.benchmark.model.*
import top.tangge233.netbridge.benchmarkreport.model.ReportMeta
import top.tangge233.netbridge.benchmarkreport.model.ReportModel
import top.tangge233.netbridge.benchmarkreport.model.ReportPage
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object ReportPipeline {

    private const val REPORT_TEMPLATE = "report/index.jte"

    private val engine: TemplateEngine by lazy {
        TemplateEngine.createPrecompiled(ContentType.Html)
    }

    fun isSupportedSuite(suite: String): Boolean =
        JmhReportAdapter.SUITE == suite || BenchmarkSuite.fromId(suite) != null

    fun render(
        suite: String,
        task: String,
        raw: Path,
        jmhOutput: Path?,
        reportDir: Path
    ): ReportMeta {
        require(isSupportedSuite(suite)) {
            "unsupported suite '$suite'"
        }
        Files.createDirectories(reportDir)
        val rawText = Files.readString(raw, StandardCharsets.UTF_8)

        val outcome = if (suite == JmhReportAdapter.SUITE) {
            val results = JmhJson.read(rawText)
            ModelOutcome(
                model = JmhReportAdapter.adapt(
                    task,
                    results,
                    jmhOutput != null
                ),
                resultRows = results.size,
                benchmarkCaseCount = results
                    .map { it.benchmark }
                    .distinct().size
            )
        } else {
            modelFor(task, BenchmarkJson.readRunDocument(rawText))
        }

        writeIndex(reportDir, outcome.model)
        val meta = ReportMeta(
            task = task,
            suite = outcome.model.suite,
            suiteTitle = outcome.model.suiteTitle,
            executedAt = outcome.model.executedAt,
            resultRows = outcome.resultRows,
            benchmarkCaseCount = outcome.benchmarkCaseCount,
            reportPath = "${reportDir.fileName}/index.html"
        )
        BenchmarkJson.writeFile(
            reportDir.resolve("report-meta.json"),
            meta
        )
        copyRaw(raw, reportDir)
        jmhOutput?.let {
            copyTo(
                it,
                reportDir.resolve("jmh-output.txt")
            )
        }
        return meta
    }

    fun writeIndex(reportDir: Path, model: ReportModel) {
        Files.createDirectories(reportDir)
        Files.writeString(
            reportDir.resolve("index.html"),
            renderPage(model),
            StandardCharsets.UTF_8
        )
    }

    fun renderToString(model: ReportModel): String = renderPage(model)

    private fun renderPage(model: ReportModel): String {
        val output = StringOutput()
        engine.render(REPORT_TEMPLATE, ReportPage(model, reportCss()), output)
        return output.toString()
    }

    private fun reportCss(): String =
        checkNotNull(
            ReportPipeline::class.java.classLoader.getResourceAsStream(
                "report/report.css"
            )
        ) { "report/report.css resource not found" }
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }

    private fun modelFor(task: String, doc: RunDocument): ModelOutcome =
        when (doc) {
            is TransportRunDocument -> {
                val model = if (doc.suite == "channel") {
                    ChannelReportAdapter.adapt(task, doc)
                } else {
                    TransportReportAdapter.adapt(task, doc)
                }
                ModelOutcome(model, doc.results.size)
            }

            is MinecraftShapedRunDocument -> ModelOutcome(
                MinecraftShapedReportAdapter.adapt(task, doc),
                doc.results.size
            )

            is MinecraftSessionRunDocument -> ModelOutcome(
                MinecraftSessionReportAdapter.adapt(task, doc),
                doc.results.size
            )
        }

    private data class ModelOutcome(
        val model: ReportModel,
        val resultRows: Int,
        val benchmarkCaseCount: Int? = null
    )

    private fun copyRaw(raw: Path, reportDir: Path) {
        copyTo(raw, reportDir.resolve("raw.json"))
    }

    private fun copyTo(source: Path, target: Path) {
        if (samePath(source, target)) return
        Files.copy(
            source,
            target,
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun samePath(a: Path, b: Path): Boolean =
        try {
            Files.isSameFile(a, b)
        } catch (_: IOException) {
            a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()
        }

}
