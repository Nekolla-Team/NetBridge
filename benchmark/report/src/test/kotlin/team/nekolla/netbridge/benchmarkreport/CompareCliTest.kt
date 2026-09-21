package team.nekolla.netbridge.benchmarkreport

import com.github.ajalt.clikt.core.UsageError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import team.nekolla.netbridge.benchmarkreport.compare.compareTexts

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

    @Test
    fun `incompatible measurement method version is rejected unless forced`() {
        val before = Fixtures.text("transport.json")
        val after = before.replace("\"suiteVersion\": \"1\"", "\"suiteVersion\": \"2\"")
        assertThrows<UsageError> {
            compareTexts(before, after)
        }

        val forced = compareTexts(before, after, force = true)
        assertTrue(
            forced.any { it.startsWith("WARNING: forced comparison") },
            "expected a forced-comparison warning, got ${forced.joinToString()}"
        )
    }

    @Test
    fun `jmh score unit mismatch is rejected`() {
        val before = Fixtures.text("jmh.json")
        val after = before.replace(
            "\"scoreUnit\": \"ns/op\"",
            "\"scoreUnit\": \"ops/s\""
        )
        assertThrows<UsageError> {
            compareTexts(before, after)
        }
    }

}
