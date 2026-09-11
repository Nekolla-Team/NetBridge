package top.tangge233.netbridge.benchmark.transport

import top.tangge233.netbridge.benchmark.common.LatencySamples
import top.tangge233.netbridge.benchmark.common.percentile
import top.tangge233.netbridge.benchmark.common.processCpuNanos
import top.tangge233.netbridge.benchmark.model.*
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val RTT_PAYLOADS = listOf(64, 256, 1024, 4096)
private const val STREAM_CHUNK_BYTES = 65536
private const val LOADED_PING_BYTES = 64
private const val LOADED_PING_INTERVAL_MILLIS = 100L
private const val CONNECT_WARMUP = 20
private const val RTT_WARMUP = 20

class TransportRunner(private val log: PrintStream) {

    private fun requireNativeLibrary(cfg: TransportConfig): Path =
        requireNotNull(cfg.nativeLibrary) {
            "native transport requires -Dnetbridge.native.path"
        }

    private fun deltaNanos(
        idle: LatencySamples,
        loaded: LatencySamples,
        q: Double
    ): Long =
        percentile(loaded.sorted(), q) - percentile(idle.sorted(), q)

    private fun await(
        f: CompletableFuture<ClientSession.StreamOutcome>
    ): ClientSession.StreamOutcome =
        f.get(60, TimeUnit.SECONDS)

    private fun mib(bytes: Long, wallNanos: Long): Double {
        val seconds = wallNanos / 1_000_000_000.0
        return if (seconds > 0) {
            (bytes / (1024.0 * 1024.0)) / seconds
        } else {
            0.0
        }
    }

    private fun hasIntegrityError(
        serverBytes: Long,
        session: ClientSession
    ): Boolean = serverBytes != session.clientSentBytes
            || session.corruptInboundFrames > 0
            || session.disorderEvents > 0

    fun runAll(cfg: TransportConfig): List<TransportMeasurement> =
        cfg.transports.flatMap { runTransport(it, cfg) }

    fun runTransport(
        transport: TransportId,
        cfg: TransportConfig
    ): List<TransportMeasurement> =
        (if (cfg.startServer) newServer(transport, cfg) else null).use { server ->
            val port = server?.port ?: cfg.port
            log.println("[${transport.label}] server on ${cfg.host}:$port")
            runAgainst(
                transport,
                cfg.host,
                port,
                cfg
            )
        }

    fun runAgainst(
        transport: TransportId,
        host: String,
        port: Int,
        cfg: TransportConfig
    ): List<TransportMeasurement> =
        BenchClient.open(
            transport,
            cfg.workers,
            cfg.nativeLibrary
        ).use { client ->
            buildList {
                cfg.cases.forEach { c ->
                    log.println("[${transport.label}] case ${c.arg} ...")
                    when (c) {
                        TransportConfig.Case.CONNECT ->
                            add(measureConnect(transport, client, host, port, cfg))

                        TransportConfig.Case.RTT ->
                            addAll(measureRtt(transport, client, host, port, cfg))

                        TransportConfig.Case.THROUGHPUT ->
                            add(measureThroughput(transport, client, host, port, cfg))

                        TransportConfig.Case.BIDIRECTIONAL ->
                            add(measureBidirectional(transport, client, host, port, cfg))

                        TransportConfig.Case.LOADED_LATENCY ->
                            add(measureLoadedLatency(transport, client, host, port, cfg))
                    }
                }
            }
        }

    private fun newServer(
        transport: TransportId,
        cfg: TransportConfig
    ): BenchServerEndpoint =
        when (transport) {
            TransportId.TCP -> TcpServerEndpoint(cfg.workers, cfg.port)

            TransportId.QUIC,
            TransportId.KCP_BALANCED,
            TransportId.KCP_AGGRESSIVE -> NativeServerEndpoint(
                transport,
                requireNativeLibrary(cfg),
                cfg.workers,
                cfg.port,
                64.coerceAtLeast(cfg.connectIterations + 16)
            )
        }

    private fun measureConnect(
        transport: TransportId,
        client: BenchClient,
        host: String,
        port: Int,
        cfg: TransportConfig
    ): TransportMeasurement {
        val cpuStart = processCpuNanos()
        warmupConnect(client, host, port)

        val samples = LatencySamples()
        repeat(cfg.connectIterations) {
            val t0 = System.nanoTime()
            ClientSession.open(client, host, port, 30_000).use { }
            samples.add(System.nanoTime() - t0)
        }

        val cpuEnd = processCpuNanos()

        return latencyMeasurement(
            name = "connect",
            transport = transport,
            payloadBytes = 0,
            samples = samples,
            cpuNanos = cpuEnd - cpuStart
        )
    }

    private fun warmupConnect(
        client: BenchClient,
        host: String,
        port: Int
    ) = repeat(CONNECT_WARMUP) {
        ClientSession.open(
            client,
            host,
            port,
            30_000
        ).use { }
    }

    private fun measureRtt(
        transport: TransportId,
        client: BenchClient,
        host: String,
        port: Int,
        cfg: TransportConfig
    ): List<TransportMeasurement> =
        ClientSession.open(
            client,
            host,
            port,
            30_000
        ).use { session ->
            buildList {
                RTT_PAYLOADS.forEach { payload ->
                    val cpuStart = processCpuNanos()
                    repeat(RTT_WARMUP) {
                        session.ping(payload)
                    }

                    val samples = LatencySamples()
                    repeat(cfg.rttMeasuredIterations) {
                        samples.add(session.ping(payload))
                    }

                    val cpuEnd = processCpuNanos()
                    add(
                        latencyMeasurement(
                            "rtt",
                            transport,
                            payload,
                            samples,
                            cpuEnd - cpuStart
                        )
                    )
                }
            }
        }

    private fun measureThroughput(
        transport: TransportId,
        client: BenchClient,
        host: String,
        port: Int,
        cfg: TransportConfig
    ): TransportMeasurement {
        val cpuStart = processCpuNanos()
        return ClientSession.open(
            client,
            host,
            port,
            30_000
        ).use { session ->
            val outcome = session.startStreaming(STREAM_CHUNK_BYTES)
            Thread.sleep(cfg.throughputDurationMillis)
            session.stopStreaming()
            val wallNanos = await(outcome).wallNanos
            val serverBytes = session.requestReport().get(
                cfg.runTimeoutMillis,
                TimeUnit.MILLISECONDS
            )
            val cpuEnd = processCpuNanos()

            throughputMeasurement(
                "throughput",
                transport,
                STREAM_CHUNK_BYTES,
                serverBytes,
                wallNanos,
                cpuEnd - cpuStart,
                hasIntegrityError(serverBytes, session)
            )
        }
    }

    private fun measureBidirectional(
        transport: TransportId,
        client: BenchClient,
        host: String,
        port: Int,
        cfg: TransportConfig
    ): TransportMeasurement {
        val cpuStart = processCpuNanos()
        return ClientSession.open(
            client,
            host,
            port,
            30_000
        ).use { session ->
            session.startServerStream(STREAM_CHUNK_BYTES)

            val outcome = session.startStreaming(STREAM_CHUNK_BYTES)
            Thread.sleep(cfg.throughputDurationMillis)
            session.stopStreaming()
            val wall = await(outcome).wallNanos
            val serverBytes = session.requestReport().get(
                cfg.runTimeoutMillis,
                TimeUnit.MILLISECONDS
            )
            val cpuEnd = processCpuNanos()
            val inbound = session.clientReceivedBytes
            val total = serverBytes + inbound
            val integrityError = hasIntegrityError(serverBytes, session)
            val mibPerSecond = mib(total, wall)

            BidirectionalMeasurementResult(
                name = "bidirectional",
                transport = transport.label,
                payloadBytes = STREAM_CHUNK_BYTES,
                durationNanos = wall,
                payloadBytesTransferred = total,
                mibPerSecond = mibPerSecond,
                processCpuNanos = cpuEnd - cpuStart,
                integrityError = integrityError,
                serverToClientBytes = inbound,
                clientToServerBytes = serverBytes,
                serverToClientMibPerSecond = mib(inbound, wall),
                clientToServerMibPerSecond = mib(serverBytes, wall)
            )
        }
    }

    private fun measureLoadedLatency(
        transport: TransportId,
        client: BenchClient,
        host: String,
        port: Int,
        cfg: TransportConfig
    ): TransportMeasurement {
        val cpuStart = processCpuNanos()
        return ClientSession.open(
            client,
            host,
            port,
            30_000
        ).use { session ->
            val idle = LatencySamples()
            repeat(30) {
                idle.add(session.ping(LOADED_PING_BYTES))
            }

            val loaded = LatencySamples()
            val deadline = System.nanoTime() +
                    TimeUnit.MILLISECONDS.toNanos(cfg.throughputDurationMillis)
            val outcome = session.startStreaming(STREAM_CHUNK_BYTES)
            while (System.nanoTime() < deadline) {
                Thread.sleep(LOADED_PING_INTERVAL_MILLIS)
                loaded.add(session.ping(LOADED_PING_BYTES))
            }
            session.stopStreaming()
            await(outcome)
            val serverBytes = session.requestReport().get(
                cfg.runTimeoutMillis,
                TimeUnit.MILLISECONDS
            )
            val cpuEnd = processCpuNanos()

            val idleSummary = idle.summary()
            val loadedSummary = loaded.summary()

            LoadedLatencyMeasurementResult(
                name = "loaded-latency",
                transport = transport.label,
                payloadBytes = LOADED_PING_BYTES,
                idleSamples = idleSummary.samples,
                idleMeanNanos = idleSummary.meanNanos,
                idleMinNanos = idleSummary.minNanos,
                idleMaxNanos = idleSummary.maxNanos,
                idleP50Nanos = idleSummary.p50Nanos,
                idleP95Nanos = idleSummary.p95Nanos,
                idleP99Nanos = idleSummary.p99Nanos,
                idleP999Nanos = idleSummary.p999Nanos,
                loadedSamples = loadedSummary.samples,
                loadedMeanNanos = loadedSummary.meanNanos,
                loadedMinNanos = loadedSummary.minNanos,
                loadedMaxNanos = loadedSummary.maxNanos,
                loadedP50Nanos = loadedSummary.p50Nanos,
                loadedP95Nanos = loadedSummary.p95Nanos,
                loadedP99Nanos = loadedSummary.p99Nanos,
                loadedP999Nanos = loadedSummary.p999Nanos,
                bufferbloatP50Nanos = deltaNanos(idle, loaded, 0.50),
                bufferbloatP99Nanos = deltaNanos(idle, loaded, 0.99),
                processCpuNanos = cpuEnd - cpuStart,
                streamMibPerSecond = mib(serverBytes, cfg.throughputDurationMillis * 1_000_000L),
                integrityError = hasIntegrityError(serverBytes, session)
            )
        }
    }

    private fun latencyMeasurement(
        name: String,
        transport: TransportId,
        payloadBytes: Int,
        samples: LatencySamples,
        cpuNanos: Long
    ): LatencyMeasurementResult {
        val s = samples.summary()
        return LatencyMeasurementResult(
            name = name,
            transport = transport.label,
            payloadBytes = payloadBytes,
            samples = s.samples,
            meanNanos = s.meanNanos,
            minNanos = s.minNanos,
            maxNanos = s.maxNanos,
            p50Nanos = s.p50Nanos,
            p95Nanos = s.p95Nanos,
            p99Nanos = s.p99Nanos,
            p999Nanos = s.p999Nanos,
            processCpuNanos = cpuNanos
        )
    }

    private fun throughputMeasurement(
        name: String,
        transport: TransportId,
        payloadBytes: Int,
        transferred: Long,
        wallNanos: Long,
        cpuNanos: Long,
        integrityError: Boolean
    ): ThroughputMeasurementResult =
        ThroughputMeasurementResult(
            name = name,
            transport = transport.label,
            payloadBytes = payloadBytes,
            durationNanos = wallNanos,
            payloadBytesTransferred = transferred,
            mibPerSecond = mib(transferred, wallNanos),
            processCpuNanos = cpuNanos,
            integrityError = integrityError
        )

}
