package top.tangge233.netbridge.benchmarkreport

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.tangge233.netbridge.benchmarkreport.adapter.ReportPipeline
import java.nio.file.Files
import java.nio.file.Path

class EmptyResultsTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `empty run document still renders`() {
        val text = Fixtures.text("transport.json")
        val start = text.indexOf("\"results\"")
        val emptyDoc = text.substring(0, start) + "\"results\":[]}"
        val raw = tmp.resolve("empty-transport.json")
        Files.writeString(raw, emptyDoc)

        val reportDir = Files.createDirectories(tmp.resolve("emptyTransport"))
        ReportPipeline.render(
            "transport",
            "emptyTransport",
            raw,
            null,
            reportDir
        )
        val doc = Jsoup.parse(Files.readString(reportDir.resolve("index.html")))

        assertEquals(
            "emptyTransport",
            doc.selectFirst("h1")?.text()
        )
        assertEquals(
            "0",
            doc.select(".metric-card")
                .first { it.selectFirst(".metric-card-label")?.text() == "Result Rows" }
                .selectFirst(".metric-card-value")
                ?.text()
        )
        val textContent = doc.text()
        assertTrue("Environment" in textContent)
        assertTrue("Complete Configuration" in textContent)
        assertTrue("Execution Scope" in textContent)
        assertTrue(Files.isRegularFile(reportDir.resolve("report-meta.json")))
    }

    @Test
    fun `empty jmh result renders note`() {
        val raw = tmp.resolve("empty-jmh.json")
        Files.writeString(raw, "[]")
        val reportDir = Files.createDirectories(tmp.resolve("emptyJmh"))
        ReportPipeline.render("jmh", "emptyJmh", raw, null, reportDir)
        val doc = Jsoup.parse(Files.readString(reportDir.resolve("index.html")))
        assertEquals("emptyJmh", doc.selectFirst("h1")?.text())
        assertTrue(doc.text().contains("No environment metadata available."))
    }

}
