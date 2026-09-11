package top.tangge233.netbridge.benchmarkreport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.tangge233.netbridge.benchmark.model.BenchmarkJson
import top.tangge233.netbridge.benchmark.model.MinecraftSessionRunDocument
import top.tangge233.netbridge.benchmark.model.MinecraftShapedRunDocument
import top.tangge233.netbridge.benchmark.model.TransportRunDocument
import top.tangge233.netbridge.benchmarkreport.adapter.*

class AdaptersTest {

    @Test
    fun `transport adapter builds header cards scope and connection latency table`() {
        val doc = assertInstanceOf(
            TransportRunDocument::class.java,
            BenchmarkJson.readRunDocument(Fixtures.transportJson())
        )
        val model = TransportReportAdapter.adapt("transportBenchmark", doc)

        assertEquals("transport", model.suite)
        assertEquals("L2 Transport Comparison", model.suiteTitle)
        assertEquals("2026-09-09T12:10:51.344284641Z", model.executedAt)
        assertEquals("2022443d4d02713ba6f95a729424fea03ba45497 (dirty)", model.git)

        val cardValues = model.cards.associate { it.label to it.value }
        assertEquals("transport", cardValues["Suite"])
        assertEquals("1", cardValues["Transports"])
        assertEquals("1", cardValues["Result Rows"])
        assertEquals("0", cardValues["Integrity Errors"])

        val scopeValues = model.scope.associate { it.key to it.value }
        assertEquals("[tcp]", scopeValues["Transports"])
        assertEquals("[connect]", scopeValues["Cases"])
        assertEquals("127.0.0.1", scopeValues["Host"])
        assertEquals("2", scopeValues["Workers"])
        assertEquals("\u2014", scopeValues["Native library"])

        val titles = model.sections.map { it.title }
        assertTrue("Connection Latency" in titles)
        assertTrue("Environment" in titles)
        assertTrue("Complete Configuration" in titles)

        val table = model.sections.first { it.title == "Connection Latency" }
        assertEquals(
            listOf(
                "Transport",
                "Payload",
                "Samples",
                "Mean",
                "P50",
                "P95",
                "P99",
                "P99.9",
                "Min",
                "Max",
                "CPU Time"
            ),
            table.headers
        )
        assertEquals(1, table.rows.size)
        assertEquals("1.07 ms", table.rows[0][3].value.text())
    }

    @Test
    fun `channel adapter groups rtt and throughput rows`() {
        val doc = assertInstanceOf(
            TransportRunDocument::class.java,
            BenchmarkJson.readRunDocument(Fixtures.channelJson())
        )
        val model = ChannelReportAdapter.adapt("channelBenchmark", doc)

        assertEquals("channel", model.suite)
        assertEquals("L3B NativeChannel Integration", model.suiteTitle)
        assertEquals("5", model.cards.first { it.label == "Result Rows" }.value)

        val rtt = model.sections.first { it.title == "Round-Trip Time (RTT)" }
        assertEquals(4, rtt.rows.size)
        assertEquals("64 B", rtt.rows[0][1].value.text())
        assertEquals("4 KiB", rtt.rows[3][1].value.text())

        val throughput = model.sections.first { it.title == "Streaming Throughput" }
        assertEquals(1, throughput.rows.size)
        assertTrue("Streaming Throughput" in model.sections.map { it.title })
        val headers = throughput.headers
        assertTrue("Integrity" in headers)
        assertEquals("69.31 MiB", throughput.rows[0][3].value.text())
        assertEquals("45.70 MiB/s", throughput.rows[0][4].value.text())
    }

    @Test
    fun `minecraft shaped adapter builds workload table with missing small messages`() {
        val doc = assertInstanceOf(
            MinecraftShapedRunDocument::class.java,
            BenchmarkJson.readRunDocument(Fixtures.minecraftShapedJson())
        )
        val model = MinecraftShapedReportAdapter.adapt("minecraftTraffic", doc)

        assertEquals("minecraft-shaped", model.suite)
        assertEquals("L4A Minecraft-shaped Workload", model.suiteTitle)
        assertEquals("1", model.cards.first { it.label == "Result Rows" }.value)

        val scopeValues = model.scope.associate { it.key to it.value }
        assertEquals("[login]", scopeValues["Workloads"])

        val table = model.sections.first { it.title == "Minecraft Workload Execution" }
        assertEquals(1, table.rows.size)
        assertEquals("login", table.rows[0][0].value.text())
        assertEquals("9", table.rows[0][2].value.text())
        assertEquals("2.64 KiB", table.rows[0][3].value.text())
        assertEquals("536.80 ms", table.rows[0][4].value.text())
        assertEquals("0.00 MiB/s", table.rows[0][5].value.text())
        assertEquals("\u2014", table.rows[0][6].value.text())
        assertEquals("\u2014", table.rows[0][7].value.text())
        assertEquals("200.00 ms", table.rows[0][8].value.text())
    }

    @Test
    fun `minecraft session adapter lists milestones`() {
        val doc = assertInstanceOf(
            MinecraftSessionRunDocument::class.java,
            BenchmarkJson.readRunDocument(Fixtures.minecraftSessionJson())
        )
        val model = MinecraftSessionReportAdapter.adapt("minecraftSession", doc)

        assertEquals("minecraft", model.suite)
        assertEquals("Real Minecraft Session", model.suiteTitle)
        assertEquals("5", model.cards.first { it.label == "Milestones" }.value)

        val table = model.sections.first { it.title == "Session Milestones" }
        assertEquals(5, table.rows.size)
        assertEquals("CONNECT_REQUESTED", table.rows[0][0].value.text())
        assertEquals("mc.example", table.rows[0][2].value.text())
    }

    @Test
    fun `jmh adapter groups rows by benchmark class`() {
        val results = JmhJson.read(Fixtures.text("jmh.json"))
        assertEquals(12, results.size)
        assertEquals(3, results.map { it.benchmark }.distinct().size)

        val model = JmhReportAdapter.adapt("jmhArena", results, hasConsoleOutput = false)
        val cardValues = model.cards.associate { it.label to it.value }
        assertEquals("12", cardValues["Benchmark Methods"])
        assertEquals("1", cardValues["Classes"])
        assertEquals("avgt", cardValues["Mode"])

        val classTables = model.sections.filter { it.title == "FfmArenaBenchmark" }
        assertEquals(1, classTables.size)
        assertEquals(12, classTables[0].rows.size)
        assertEquals(
            listOf("Method", "Parameters", "Score", "Score (Formatted)", "Error", "Unit"),
            classTables[0].headers
        )
        val first = classTables[0].rows[0]
        assertEquals("allocateDirectBuffer", first[0].value.text())
        assertEquals("bufferBytes=64", first[1].value.text())
        assertEquals("ns/op", first[5].value.text())
        assertEquals("426.19 ns", first[3].value.text())

        val scopeValues = model.scope.associate { it.key to it.value }
        assertEquals("2 \u00d7 500 ms", scopeValues["Warmup"])
        assertEquals("3 \u00d7 500 ms", scopeValues["Measurement"])
        assertEquals("12", scopeValues["Total benchmarks"])
    }

}
