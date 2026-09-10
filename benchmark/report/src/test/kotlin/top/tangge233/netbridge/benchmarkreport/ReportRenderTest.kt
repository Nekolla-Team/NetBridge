package top.tangge233.netbridge.benchmarkreport

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.tangge233.netbridge.benchmarkreport.adapter.ReportPipeline
import java.nio.file.Files
import java.nio.file.Path

class ReportRenderTest {

    @TempDir
    lateinit var tmp: Path

    private fun renderSuite(
        suite: String,
        task: String,
        raw: Path,
        jmhOutput: Path? = null
    ): Pair<Document, Path> {
        val reportDir = Files.createDirectories(tmp.resolve(task))
        ReportPipeline.render(suite, task, raw, jmhOutput, reportDir)
        val html = Files.readString(reportDir.resolve("index.html"))
        assertTrue(html.trimStart().startsWith("<!doctype html>"))
        return Jsoup.parse(html) to reportDir
    }

    private fun assertCommonChrome(doc: Document, task: String, suiteTitle: String) {
        assertEquals("$task \u2014 Benchmark Report", doc.title())
        assertEquals(task, doc.selectFirst("h1")?.text())
        assertEquals("NetBridge Benchmark Suite", doc.selectFirst(".breadcrumb")?.text())
        val meta = doc.select(".header-meta").first() ?: error("header-meta missing")
        assertTrue(meta.select("span").text().contains("Suite:"))
        assertTrue(meta.select("span").text().contains(suiteTitle))
        assertTrue(doc.select(".metric-card").isNotEmpty())
        assertTrue(doc.selectFirst("style")?.data()?.isNotBlank() ?: false)
        assertNotNull(doc.selectFirst("footer.report-footer"))
    }

    private fun sectionTable(doc: Document, title: String): Element {
        val section = doc.select("section.report-section")
            .first { it.selectFirst("h2")?.text() == title }
        return section.selectFirst("table.data-table")
            ?: error("table for section '$title' not found")
    }

    @Test
    fun `transport suite renders end to end`() {
        val (doc, reportDir) = renderSuite(
            "transport",
            "transportBenchmark",
            Fixtures.transportJson()
        )
        assertCommonChrome(doc, "transportBenchmark", "L2 Transport Comparison")

        val meta = doc.select(".header-meta").text()
        assertTrue(meta.contains("Git:"))

        assertEquals(4, doc.select(".metric-card").size)
        val scopeKeys = doc.select(".scope-key").eachText()
        assertTrue("Transports" in scopeKeys)
        assertTrue("Cases" in scopeKeys)
        assertTrue("Host" in scopeKeys)
        assertTrue("Port" in scopeKeys)
        assertTrue("Workers" in scopeKeys)
        assertTrue("Rtt payload bytes" in scopeKeys)

        val h2 = doc.select("section h2").eachText()
        assertTrue("Connection Latency" in h2)
        assertTrue("Environment" in h2)
        assertTrue("Complete Configuration" in h2)
        assertTrue("Raw Artifacts" in h2)

        val connectionTable = sectionTable(doc, "Connection Latency")
        val headers = connectionTable.select("thead th").eachText()
        assertTrue("Mean" in headers)
        assertTrue("P50" in headers)
        assertTrue("CPU Time" in headers)
        assertEquals(1, connectionTable.select("tbody tr").size)

        val meanCell = connectionTable.select("tbody tr").first()
            ?.select("td.num")
            ?.first { it.attr("title").isNotEmpty() }
        assertNotNull(meanCell)
        assertTrue(meanCell?.attr("title")?.isNotBlank() == true)
        assertTrue(connectionTable.select("tbody td.num").eachText().contains("1.07 ms"))
        assertTrue(connectionTable.select("tbody td").any { it.text() == "0 B" })

        val artifacts = doc.select(".artifact-list a").eachText()
        assertTrue("raw.json" in artifacts)
        assertTrue(Files.isRegularFile(reportDir.resolve("raw.json")))
        assertTrue(Files.isRegularFile(reportDir.resolve("report-meta.json")))

        val envText = doc.select(".collapsible-details").text()
        assertTrue(envText.contains("Environment"))
        assertTrue(envText.contains("Complete Configuration"))
        assertTrue(envText.contains("suiteVersion"))
        assertTrue(doc.select("code").any { it.text() == "suiteVersion" })
    }

    @Test
    fun `channel fixture has rtt rows and throughput section`() {
        val (doc, _) = renderSuite(
            "channel",
            "channelBenchmark",
            Fixtures.channelJson()
        )
        assertCommonChrome(
            doc,
            "channelBenchmark",
            "L3B NativeChannel Integration"
        )

        val rtt = sectionTable(doc, "Round-Trip Time (RTT)")
        assertEquals(4, rtt.select("tbody tr").size)
        val payloads = rtt.select("tbody tr td").eachText()
        assertTrue(payloads.contains("64 B"))
        assertTrue(payloads.contains("1 KiB"))
        assertTrue(payloads.contains("4 KiB"))

        val throughput = sectionTable(doc, "Streaming Throughput")
        val throughputText = throughput.select("tbody tr td").eachText()
        assertTrue(throughputText.contains("69.31 MiB"))
        assertTrue(throughputText.contains("45.70 MiB/s"))
        assertTrue(throughputText.contains("verified"))
    }

    @Test
    fun `minecraft shaped suite renders end to end`() {
        val (doc, _) = renderSuite(
            "minecraft-shaped",
            "minecraftTraffic",
            Fixtures.minecraftShapedJson()
        )
        assertCommonChrome(
            doc,
            "minecraftTraffic",
            "L4A Minecraft-shaped Workload"
        )

        val h2 = doc.select("section h2").eachText()
        assertTrue("Minecraft Workload Execution" in h2)
        assertTrue("Environment" in h2)
        assertTrue("Complete Configuration" in h2)

        val scopeKeys = doc.select(".scope-key").eachText()
        assertTrue("Workloads" in scopeKeys)

        val table = sectionTable(doc, "Minecraft Workload Execution")
        val headers = table.select("thead th").eachText()
        assertTrue("Workload" in headers)
        assertTrue("Small Msg P50" in headers)
        assertTrue("Small Msg P99" in headers)
        assertEquals(1, table.select("tbody tr").size)
        val text = table.select("tbody td").eachText()
        assertTrue(text.contains("536.80 ms"))
        assertTrue(text.contains("2.64 KiB"))
    }

    @Test
    fun `minecraft session suite renders end to end`() {
        val (doc, _) = renderSuite(
            "minecraft",
            "minecraftSession",
            Fixtures.minecraftSessionJson()
        )
        assertCommonChrome(doc, "minecraftSession", "Real Minecraft Session")

        val h2 = doc.select("section h2").eachText()
        assertTrue("Session Milestones" in h2)

        val scopeKeys = doc.select(".scope-key").eachText()
        assertTrue("OS" in scopeKeys)
        assertTrue("Recorder" in scopeKeys)

        val table = sectionTable(doc, "Session Milestones")
        val headers = table.select("thead th").eachText()
        assertTrue("Milestone" in headers)
        assertTrue("Unique Chunks" in headers)
        assertEquals(5, table.select("tbody tr").size)
        assertTrue(table.select("tbody tr td").eachText().contains("CONNECT_REQUESTED"))
    }

    @Test
    fun `jmh suite renders end to end with console artifact`() {
        val console = Files.createTempFile(tmp, "jmh-out", ".txt")
        Files.writeString(console, "Benchmark console output\n")
        val (doc, reportDir) = renderSuite(
            "jmh",
            "jmhArena",
            Fixtures.jmhJson(),
            jmhOutput = console
        )
        assertCommonChrome(doc, "jmhArena", "L0 Raw FFM")

        val h2 = doc.select("section h2").eachText()
        assertTrue("FfmArenaBenchmark" in h2)

        val scopeKeys = doc.select(".scope-key").eachText()
        assertTrue("Mode" in scopeKeys)
        assertTrue("Forks" in scopeKeys)
        assertTrue("Warmup" in scopeKeys)
        assertTrue("Total benchmarks" in scopeKeys)

        val table = sectionTable(doc, "FfmArenaBenchmark")
        val headers = table.select("thead th").eachText()
        assertTrue("Score (Formatted)" in headers)
        assertEquals(12, table.select("tbody tr").size)
        val unitTexts = table.select("tbody tr td").eachText()
        assertTrue(unitTexts.contains("ns/op"))
        assertTrue(unitTexts.contains("426.19 ns"))
        assertTrue(unitTexts.contains("1.40 \u00b5s"))

        assertTrue(Files.isRegularFile(reportDir.resolve("jmh-output.txt")))
        assertTrue(doc.select(".artifact-list a").eachText().contains("jmh-output.txt"))
        assertTrue(doc.select(".artifact-list a").eachText().contains("raw.json"))
    }

    @Test
    fun `numeric cells carry raw title attribute`() {
        val (doc, _) = renderSuite("channel", "channelBenchmark", Fixtures.channelJson())
        val numeric = doc.select("td.num")
        assertTrue(numeric.size > 10)
        assertTrue(numeric.all { it.attr("title").isNotBlank() })
        val titleValues = numeric.map { it.attr("title") }
        assertFalse("1067826.6" in titleValues)
    }

}
