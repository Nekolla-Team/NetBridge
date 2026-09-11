package top.tangge233.netbridge.benchmarkreport

import com.github.ajalt.clikt.core.UsageError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import top.tangge233.netbridge.benchmarkreport.compare.compareTexts

class CompareCliTest {

    @Test
    fun `comparing identical transport documents produces no delta lines`() {
        val text = Fixtures.text("transport.json")
        val lines = compareTexts(text, text)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `scaling a mean produces a delta line with percent`() {
        val before = Fixtures.text("transport.json")
        val after = before.replace(
            Regex("\"meanNanos\"\\s*:\\s*1067826\\.6"),
            "\"meanNanos\": 2135653.2"
        )
        val lines = compareTexts(before, after)
        assertTrue(lines.isNotEmpty())
        val meanLines = lines.filter { it.contains("Mean") }
        assertTrue(
            meanLines.isNotEmpty(),
            "expected a Mean delta line, got ${lines.joinToString()}"
        )
        assertTrue(meanLines[0].contains("before: 1.07 ms"))
        assertTrue(meanLines[0].contains("after: 2.14 ms"))
        assertTrue(meanLines[0].contains("delta: 1.07 ms"))
        assertTrue(meanLines[0].contains("100.00%"))
    }

    @Test
    fun `mismatched suites are rejected`() {
        val transport = Fixtures.text("transport.json")
        val shaped = Fixtures.text("minecraft-shaped.json")
        assertThrows<UsageError> {
            compareTexts(transport, shaped)
        }
    }

}
