package team.nekolla.netbridge.benchmarkreport

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import team.nekolla.netbridge.benchmarkreport.adapter.ReportPipeline
import team.nekolla.netbridge.benchmarkreport.dsl.Cell
import team.nekolla.netbridge.benchmarkreport.dsl.KeyValue
import team.nekolla.netbridge.benchmarkreport.dsl.report
import team.nekolla.netbridge.benchmarkreport.model.DurationValue
import team.nekolla.netbridge.benchmarkreport.model.TextValue

class EscapingTest {

    private val hostile = "</b><script>alert(1)</script>"

    private fun hostileModel() = report {
        task = "hostile"
        suite = "transport"
        suiteTitle = "L2 Transport Comparison"
        executedAt = "2026-01-01T00:00:00Z"
        git = hostile

        card("Suite", hostile)
        card("Transports", "1", hostile)

        scope("Transports", hostile)

        table(
            title = "Text Table",
            description = hostile,
            headers = listOf("Col"),
            rows = listOf(listOf(Cell("c", TextValue(hostile))))
        )
        table(
            title = "Numeric Table",
            description = null,
            headers = listOf("Mean"),
            rows = listOf(
                listOf(
                    Cell(
                        "m",
                        DurationValue.ofNanos(5),
                        hostile
                    )
                )
            )
        )

        keyValues(
            "Environment",
            listOf(KeyValue("<img src=x onerror=alert(1)>", hostile))
        )

        artifact("raw.json", hostile)
    }

    @Test
    fun `hostile strings are html escaped and never create elements`() {
        val html = ReportPipeline.renderToString(hostileModel())
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertFalse(html.contains("<img src=x"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&lt;img"))

        val doc = Jsoup.parse(html)
        assertTrue(doc.select("script").isEmpty())
        assertTrue(doc.select("img").isEmpty())
        assertTrue(doc.select("b").isEmpty())

        val textContent = doc.select(".card-grid .metric-card-value").first()?.text()
        assertEquals(hostile, textContent)
        val titleAttr = doc.select("td.num").first()?.attr("title")
        assertEquals(hostile, titleAttr)
    }

}
