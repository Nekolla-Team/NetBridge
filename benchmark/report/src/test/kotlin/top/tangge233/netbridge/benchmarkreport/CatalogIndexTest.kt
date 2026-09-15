package top.tangge233.netbridge.benchmarkreport

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.tangge233.netbridge.benchmark.model.BenchmarkJson
import top.tangge233.netbridge.benchmarkreport.adapter.ReportPipeline
import top.tangge233.netbridge.benchmarkreport.index.renderCatalog
import top.tangge233.netbridge.benchmarkreport.model.ReportMeta
import java.nio.file.Files
import java.nio.file.Path

class CatalogIndexTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `meta round trips and catalog lists tasks ordered newest first`() {
        val root = Files.createDirectories(tmp.resolve("reports"))
        val taskA = root.resolve("taskA")
        val taskB = root.resolve("taskB")
        val junk = Files.createDirectories(root.resolve("junk"))
        Files.writeString(junk.resolve("some-file.txt"), "no meta here")

        val metaA = ReportPipeline.render(
            "transport",
            "taskA",
            Fixtures.transportJson(),
            null,
            taskA
        )
        val metaB = ReportPipeline.render(
            "channel",
            "taskB",
            Fixtures.channelJson(),
            null,
            taskB
        )

        val parsedA = BenchmarkJson.mapper.readValue(
            Files.readString(taskA.resolve("report-meta.json")),
            ReportMeta::class.java
        )
        assertEquals(metaA, parsedA)
        assertEquals("taskA/index.html", parsedA.reportPath)
        assertEquals("transport", parsedA.suite)
        assertEquals("L2 Transport Comparison", parsedA.suiteTitle)
        assertEquals(1, parsedA.resultRows)
        assertNull(parsedA.benchmarkCaseCount)

        val index = renderCatalog(root)
        assertTrue(Files.isRegularFile(index))
        val doc = Jsoup.parse(Files.readString(index))

        val rows = doc.select("tbody tr")
        assertEquals(2, rows.size)
        val tasks = rows.map { it.select("td code").text() }
        assertEquals(listOf("taskA", "taskB"), tasks)

        val links = rows.map { it.select("a").attr("href") }
        assertEquals(listOf("taskA/index.html", "taskB/index.html"), links)
        assertFalse("junk" in doc.text())
        assertFalse("some-file.txt" in doc.text())
    }

    @Test
    fun `catalog shows empty message when no reports exist`() {
        val root = Files.createDirectories(tmp.resolve("empty-reports"))
        val index = renderCatalog(root)
        val doc = Jsoup.parse(Files.readString(index))
        assertTrue(doc.text().contains("No benchmark reports found yet."))
    }

}
