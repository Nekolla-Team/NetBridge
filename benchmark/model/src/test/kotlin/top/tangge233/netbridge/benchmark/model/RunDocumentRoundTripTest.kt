package top.tangge233.netbridge.benchmark.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files

class RunDocumentRoundTripTest {

    private fun resource(name: String): String {
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "missing fixture $name"
        }
        return stream.use { it.readAllBytes().decodeToString() }
    }

    @Test
    fun `transport fixture parses into typed document`() {
        val doc = BenchmarkJson.readRunDocument(
            resource("transport.json")
        ) as TransportRunDocument
        assertEquals("transport", doc.suite)
        assertEquals("1", doc.environment.suiteVersion)
        assertEquals("2022443d4d02713ba6f95a729424fea03ba45497", doc.environment.gitCommit)
        assertTrue(doc.environment.gitDirty == true)
        assertEquals("Linux", doc.environment.os)
        assertEquals("amd64", doc.environment.arch)
        assertEquals(16, doc.environment.availableProcessors)
        assertEquals(4714397696L, doc.environment.maxHeapBytes)
        assertNull(doc.environment.nativeLibrary)
        assertNull(doc.environment.nativeLibrarySha256)

        val cfg = doc.configuration
        assertEquals(listOf("tcp"), cfg.transports)
        assertEquals(listOf("connect"), cfg.cases)
        assertEquals("127.0.0.1", cfg.host)
        assertEquals(0, cfg.port)
        assertEquals(2, cfg.workers)
        assertEquals(1024L, cfg.rttPayloadBytes)
        assertEquals(10_000L, cfg.throughputDurationMillis)
        assertEquals(5, cfg.connectIterations)
        assertEquals(5, cfg.rttMeasuredIterations)
        assertTrue(cfg.startServer)
        assertNull(cfg.nativeLibrary)

        val row = assertInstanceOf(
            LatencyMeasurementResult::class.java,
            doc.results.single()
        )
        assertEquals("connect", row.name)
        assertEquals("tcp", row.transport)
        assertEquals(0, row.payloadBytes)
        assertEquals(5, row.samples)
        assertEquals(1067826.6, row.meanNanos)
        assertEquals(836043L, row.minNanos)
        assertEquals(1354174L, row.maxNanos)
        assertEquals(1069749L, row.p50Nanos)
        assertEquals(1354174L, row.p95Nanos)
        assertEquals(1354174L, row.p99Nanos)
        assertEquals(1354174L, row.p999Nanos)
        assertEquals(160_000_000L, row.processCpuNanos)
    }

    @Test
    fun `channel fixture parses rtt and throughput rows`() {
        val doc = BenchmarkJson.readRunDocument(
            resource("channel.json")
        ) as TransportRunDocument
        assertEquals("channel", doc.suite)
        assertEquals(
            "/mnt/d/Github/source/repos/NetBridge/benchmark/build/native/runtime/linux-x86_64/libnet_bridge_native.so",
            doc.environment.nativeLibrary
        )
        assertEquals(
            "70749abab48ad14ef16b88452a31e3b513283326ca8607f5cedb5366cfc17123",
            doc.environment.nativeLibrarySha256
        )

        val rtt = doc.results.filterIsInstance<LatencyMeasurementResult>()
        assertEquals(
            listOf(64, 256, 1024, 4096),
            rtt.map { it.payloadBytes }
        )
        assertTrue(rtt.all { it.name == "rtt" && it.transport == "quic" })

        val tp = doc.results.filterIsInstance<ThroughputMeasurementResult>().single()
        assertEquals("throughput", tp.name)
        assertEquals(65_536, tp.payloadBytes)
        assertEquals(1_516_758_064L, tp.durationNanos)
        assertEquals(72_679_424L, tp.payloadBytesTransferred)
        assertEquals(45.69779561099469, tp.mibPerSecond)
        assertFalse(tp.integrityError)
    }

    @Test
    fun `minecraft-shaped fixture parses typed result`() {
        val doc = BenchmarkJson.readRunDocument(
            resource("minecraft-shaped.json")
        ) as MinecraftShapedRunDocument
        assertEquals("minecraft-shaped", doc.suite)
        assertEquals(listOf("tcp"), doc.configuration.transports)
        assertEquals(listOf("login"), doc.configuration.workloads)
        val row = doc.results.single()
        assertEquals("login", row.name)
        assertEquals("tcp", row.transport)
        assertEquals(2706L, row.payloadBytes)
        assertEquals(9L, row.messages)
        assertEquals(536_804_070L, row.durationNanos)
        assertEquals(536_804_070L, row.completionTimeNanos)
        assertEquals(0.004807420145296799, row.mibPerSecond)
        assertEquals(0, row.smallMessageCount)
        assertNull(row.smallMessageMeanNanos)
    }

    @Test
    fun `minecraft session fixture parses milestones`() {
        val doc = BenchmarkJson.readRunDocument(
            resource("minecraft-session.json")
        ) as MinecraftSessionRunDocument
        assertEquals("minecraft", doc.suite)
        assertEquals("MinecraftBenchmarkRecorder", doc.environment.recorder)
        assertEquals(5, doc.results.size)
        val first = doc.results.first()
        assertEquals("CONNECT_REQUESTED", first.name)
        assertEquals("mc.example", first.host)
        assertEquals(25565, first.port)
        val chunk = doc.results.last()
        assertEquals("FIRST_CHUNK", chunk.name)
        assertEquals(1, chunk.uniqueChunks)
        val login = doc.results[2]
        assertNull(login.host)
        assertNull(login.port)
    }

    @Test
    fun `serialize then parse keeps numeric schema values`() {
        val doc = BenchmarkJson.readRunDocument(
            resource("channel.json")
        ) as TransportRunDocument
        val reparsed = BenchmarkJson.readRunDocument(
            BenchmarkJson.writeString(doc)
        ) as TransportRunDocument
        assertEquals(
            doc.results.size,
            reparsed.results.size
        )
        doc.results
            .zip(reparsed.results)
            .forEach { (a, b) ->
                assertEquals(a.name, b.name)
                assertEquals(a.transport, b.transport)
                assertEquals(a::class, b::class)
            }
        val originalRtt = doc.results.filterIsInstance<LatencyMeasurementResult>()
        val roundTripRtt = reparsed.results.filterIsInstance<LatencyMeasurementResult>()
        assertEquals(
            originalRtt.map { it.meanNanos },
            roundTripRtt.map { it.meanNanos }
        )
        assertEquals(
            originalRtt.map { it.p50Nanos },
            roundTripRtt.map { it.p50Nanos }
        )
        assertEquals(
            originalRtt.map { it.minNanos },
            roundTripRtt.map { it.minNanos }
        )
    }

    @Test
    fun `transport config nativeLibrary null is written explicitly`() {
        val cfg = TransportConfigurationSnapshot(
            transports = listOf("tcp"),
            cases = listOf("connect"),
            host = "127.0.0.1",
            port = 0,
            workers = 2,
            rttPayloadBytes = 1024,
            throughputDurationMillis = 10_000,
            connectIterations = 5,
            rttMeasuredIterations = 5,
            startServer = true,
            nativeLibrary = null
        )
        val json = BenchmarkJson.writeString(cfg)
        assertTrue(json.contains("\"nativeLibrary\":null"), json)
        assertFalse(json.contains("\"transports\":null"))
    }

    @Test
    fun `unknown environment fields are preserved on round trip`() {
        val doc = BenchmarkJson.readRunDocument(
            resource("transport.json")
        ) as TransportRunDocument
        val node = assertInstanceOf(
            ObjectNode::class.java,
            BenchmarkJson.readNode(resource("transport.json"))
        )
        val envNode = assertInstanceOf(
            ObjectNode::class.java,
            node.get("environment")
        )
        envNode.put("futureField", 1234)

        val withExtra = BenchmarkJson.readRunDocument(
            node.toString()
        ) as TransportRunDocument
        assertTrue(withExtra.environment.unknownFields().containsKey("futureField"))
        assertEquals(
            1234,
            withExtra.environment.unknownFields()["futureField"]?.asInt()
        )

        val rewritten = BenchmarkJson.writeString(withExtra)
        assertTrue(rewritten.contains("\"futureField\":1234"), rewritten)
    }

    @Test
    fun `typed construction of every measurement subtype serializes with name`() {
        val rows: List<TransportMeasurement> = listOf(
            LatencyMeasurementResult(
                name = "rtt",
                transport = "tcp",
                payloadBytes = 64,
                samples = 1,
                meanNanos = 10.5,
                minNanos = 10,
                maxNanos = 11,
                p50Nanos = 10,
                p95Nanos = 11,
                p99Nanos = 11,
                p999Nanos = 11,
                processCpuNanos = 1
            ),
            ThroughputMeasurementResult(
                name = "throughput",
                transport = "tcp",
                payloadBytes = 65_536,
                durationNanos = 1000L,
                payloadBytesTransferred = 1024L,
                mibPerSecond = 1.0,
                processCpuNanos = 2,
                integrityError = false
            ),
            BidirectionalMeasurementResult(
                name = "bidirectional",
                transport = "tcp",
                payloadBytes = 65_536,
                durationNanos = 1000L,
                payloadBytesTransferred = 2048L,
                mibPerSecond = 2.0,
                processCpuNanos = 2,
                integrityError = false,
                serverToClientBytes = 1024L,
                clientToServerBytes = 1024L,
                serverToClientMibPerSecond = 1.0,
                clientToServerMibPerSecond = 1.0
            ),
            LoadedLatencyMeasurementResult(
                name = "loaded-latency",
                transport = "tcp",
                payloadBytes = 64,
                idleSamples = 1,
                idleMeanNanos = 5.0,
                idleMinNanos = 5,
                idleMaxNanos = 5,
                idleP50Nanos = 5,
                idleP95Nanos = 5,
                idleP99Nanos = 5,
                idleP999Nanos = 5,
                loadedSamples = 2,
                loadedMeanNanos = 20.0,
                loadedMinNanos = 15,
                loadedMaxNanos = 25,
                loadedP50Nanos = 20,
                loadedP95Nanos = 25,
                loadedP99Nanos = 25,
                loadedP999Nanos = 25,
                bufferbloatP50Nanos = 15L,
                bufferbloatP99Nanos = 20L,
                processCpuNanos = 3,
                streamMibPerSecond = 9.0,
                integrityError = false
            )
        )
        val json = BenchmarkJson.writeString(rows)
        val parsed = BenchmarkJson.readNode(json)
        assertEquals(4, parsed.size())
        assertInstanceOf(ArrayNode::class.java, parsed)
        val names = buildList {
            parsed.forEach { add(it.get("name").asString()) }
        }
        assertEquals(
            listOf(
                "rtt",
                "throughput",
                "bidirectional",
                "loaded-latency"
            ),
            names
        )
    }

    @Test
    fun `writeFile is atomic and readable`() {
        val doc = BenchmarkJson.readRunDocument(resource("transport.json"))
        val target = Files.createTempFile("nb-model-", ".json")
        try {
            Files.deleteIfExists(target)
            BenchmarkJson.writeFile(target, doc)
            val reread = BenchmarkJson.readRunDocument(target)
            assertEquals("transport", reread.suite)
        } finally {
            Files.deleteIfExists(target)
        }
    }

    @Test
    fun `suite id constants match persisted strings`() {
        assertEquals("transport", BenchmarkSuite.TRANSPORT.id)
        assertEquals("channel", BenchmarkSuite.CHANNEL.id)
        assertEquals("minecraft-shaped", BenchmarkSuite.MINECRAFT_SHAPED.id)
        assertEquals("minecraft", BenchmarkSuite.MINECRAFT.id)
        assertEquals(BenchmarkSuite.TRANSPORT, BenchmarkSuite.fromId("transport"))
        assertNull(BenchmarkSuite.fromId("nope"))
    }

    @Test
    fun `invocation manifest round trips`() {
        val path = Files.createTempFile("nb-manifest-", ".json")
        try {
            BenchmarkJson.writeInvocationManifest(
                path,
                InvocationManifest(
                    task = "jmhArena",
                    suite = "jmh",
                    rawResult = "/abs/x.json"
                )
            )
            val manifest = BenchmarkJson.readInvocationManifest(path)
            assertEquals("jmhArena", manifest.task)
            assertEquals("jmh", manifest.suite)
            assertEquals("/abs/x.json", manifest.rawResult)
        } finally {
            Files.deleteIfExists(path)
        }
    }

}
