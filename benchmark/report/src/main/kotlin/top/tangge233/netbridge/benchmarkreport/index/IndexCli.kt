@file:JvmName("IndexCliMain")

package top.tangge233.netbridge.benchmarkreport.index

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import gg.jte.ContentType
import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import top.tangge233.netbridge.benchmark.model.BenchmarkJson
import top.tangge233.netbridge.benchmarkreport.model.ReportMeta
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.streams.asSequence

private const val CATALOG_TEMPLATE = "report/catalog.jte"

private val engine: TemplateEngine by lazy {
    TemplateEngine.createPrecompiled(ContentType.Html)
}

class IndexCli : CliktCommand(name = "index") {

    private val reportsDir by option(
        "--reports-dir",
        help = "Root directory that contains one subdirectory per report task."
    ).path(
        mustExist = true,
        canBeFile = false
    ).required()

    override fun run() {
        val catalogPath = renderCatalog(reportsDir)
        echo("catalog written: $catalogPath")
    }
}

fun renderCatalog(reportsDir: Path): Path {
    val rows = Files.list(reportsDir).use { stream ->
        stream.asSequence()
            .mapNotNull { catalogRow(it) }
            .sortedByDescending { it.executedAt }
            .toList()
    }

    val data = CatalogData(
        rows = rows,
        generatedAt = Instant.now().toString()
    )
    val output = StringOutput()
    engine.render(CATALOG_TEMPLATE, data, output)
    val index = reportsDir.resolve("index.html")
    Files.writeString(
        index,
        output.toString(),
        StandardCharsets.UTF_8
    )
    return index
}

private fun catalogRow(dir: Path): CatalogRow? {
    if (!dir.isDirectory()) return null
    val metaFile = dir.resolve("report-meta.json")
    if (!Files.isRegularFile(metaFile)) return null
    val meta = readMeta(metaFile)
    return CatalogRow(
        task = meta.task,
        suite = meta.suiteTitle,
        executedAt = meta.executedAt,
        reportPath = meta.reportPath
    )
}

private fun readMeta(path: Path): ReportMeta =
    BenchmarkJson.mapper.readValue(
        path.readText(StandardCharsets.UTF_8),
        ReportMeta::class.java
    )

fun main(args: Array<String>) = IndexCli().main(args)
