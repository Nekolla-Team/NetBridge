package team.nekolla.netbridge.benchmark.transport

import team.nekolla.netbridge.benchmark.common.*
import team.nekolla.netbridge.benchmark.model.*
import java.io.PrintStream
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val STREAM_CHUNK_BYTES = 65536
private const val LOADED_PING_BYTES = 64
private const val LOADED_PING_INTERVAL_MILLIS = 100L
private const val LOADED_IDLE_SAMPLES = 30
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
        f.get(60, TimeUnit.SECONDS).also {
            check(!it.failed) { "stream failed: ${it.error}" }
        }

    private fun awaitStats(
        f: CompletableFuture<SessionStats>,
        timeoutMillis: Long
    ): SessionStats =
        f.get(timeoutMillis, TimeUnit.MILLISECONDS)

    private fun mib(bytes: Long, wallNanos: Long): Double =
        (wallNanos / 1_000_000_000.0).let {
            if (it > 0) {
                (bytes / (1024.0 * 1024.0)) / it
            } else {
                0.0
            }
        }

    private fun integrityError(
        stats: SessionStats,
        session: ClientSession,
        expectedServerBytes: Long
    ): Boolean =
        stats.corruptFrames > 0
                || stats.disorderEvents > 0
                || stats.failedWrites > 0
                || stats.bytesReceived != expectedServerBytes
                || session.corruptInboundFrames > 0
                || session.disorderEvents > 0

    fun runAll(cfg: TransportConfig): List<TransportMeasurement> {
        val rows = mutableListOf<TransportMeasurement>()
        repeat(cfg.repetitions) { repetition ->
            val ordered = cfg.transports.shuffled(Random(cfg.seed + repetition))
            ordered.forEach { transport ->
                rows += runTransport(transport, cfg, repetition)
            }
        }
        return rows
    }

    fun runTransport(
        transport: TransportId,
        cfg: TransportConfig,
        repetition: Int = 0
    ): List<TransportMeasurement> =
        (if (cfg.startServer) newServer(transport, cfg) else null).use { server ->
            val port = server?.port ?: cfg.port
            log.println("[${transport.label}] server on ${cfg.host}:$port")
            runAgainst(
                transport,
                cfg.host,
                port,
                cfg,
                repetition
            )
        }

    fun runAgainst(
        transport: TransportId,
        host: String,
        port: Int,
        cfg: TransportConfig,
        repetition: Int = 0
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
            }.map { stamp(it, repetition, cfg) }
        }

    private fun cpuScope(cfg: TransportConfig): String =
        if (cfg.startServer) CpuScope.LOCAL_BOTH_ENDPOINTS else CpuScope.CLIENT_ONLY

    private fun stamp(
        measurement: TransportMeasurement,
        repetition: Int,
        cfg: TransportConfig
    ): TransportMeasurement =
        cpuScope(cfg).let {
            when (measurement) {
                is LatencyMeasurementResult ->
                    measurement.copy(cpuScope = it, repetition = repetition, seed = cfg.seed)

                is ThroughputMeasurementResult ->
                    measurement.copy(cpuScope = it, repetition = repetition, seed = cfg.seed)

                is BidirectionalMeasurementResult ->
                    measurement.copy(cpuScope = it, repetition = repetition, seed = cfg.seed)

                is LoadedLatencyMeasurementResult ->
                    measurement.copy(cpuScope = it, repetition = repetition, seed = cfg.seed)
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
        warmupConnect(client, host, port)

        val cpuStart = processCpuNanos()
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
            cpuNanos = cpuDeltaNanos(cpuStart, cpuEnd)
        )
    }

    private fun warmupConnect(
        client: BenchClient,
        host: String,
        port: Int
    ) =
        repeat(CONNECT_WARMUP) {
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
                cfg.rttPayloads.forEach { payload ->
                    repeat(RTT_WARMUP) {
                        session.ping(payload)
                    }

                    val cpuStart = processCpuNanos()
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
                            cpuDeltaNanos(cpuStart, cpuEnd)
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
    ): TransportMeasurement =
        ClientSession.open(
            client,
            host,
            port,
            30_000
        ).use { session ->
            val cpuStart = processCpuNanos()
            val outcome = session.startStreaming(STREAM_CHUNK_BYTES)
            Thread.sleep(cfg.throughputDurationMillis)
            session.stopStreaming()
            val stream = await(outcome)
            val cpuEnd = processCpuNanos()
            val stats = awaitStats(session.requestReport(), cfg.runTimeoutMillis)

            throughputMeasurement(
                "throughput",
                transport,
                STREAM_CHUNK_BYTES,
                stats.bytesReceived,
                stream.wallNanos,
                cpuDeltaNanos(cpuStart, cpuEnd),
                integrityError(stats, session, stream.bytesWritten),
                TimeUnit.MILLISECONDS.toNanos(cfg.throughputDurationMillis)
            )
        }

    private fun measureBidirectional(
        transport: TransportId,
        client: BenchClient,
        host: String,
        port: Int,
        cfg: TransportConfig
    ): TransportMeasurement =
        ClientSession.open(
            client,
            host,
            port,
            30_000
        ).use { session ->
            val cpuStart = processCpuNanos()
            session.startServerStream(STREAM_CHUNK_BYTES)

            val outcome = session.startStreaming(STREAM_CHUNK_BYTES)
            Thread.sleep(cfg.throughputDurationMillis)
            session.stopStreaming()
            val stream = await(outcome)
            val stopFuture = session.stopServerStream()
            val stats = awaitStats(stopFuture, cfg.runTimeoutMillis)
            val cpuEnd = processCpuNanos()

            val serverWallNanos =
                (stats.endNanos - stats.startNanos).coerceAtLeast(0L)
            val wall = maxOf(stream.wallNanos, serverWallNanos)
            val clientToServer = stats.bytesReceived
            val serverToClient = stats.bytesSent
            val total = clientToServer + serverToClient
            val integrityError = integrityError(stats, session, stream.bytesWritten)
            val mibPerSecond = mib(total, wall)

            BidirectionalMeasurementResult(
                name = "bidirectional",
                transport = transport.label,
                payloadBytes = STREAM_CHUNK_BYTES,
                durationNanos = wall,
                payloadBytesTransferred = total,
                mibPerSecond = mibPerSecond,
                processCpuNanos = cpuDeltaNanos(cpuStart, cpuEnd),
                integrityError = integrityError,
                serverToClientBytes = serverToClient,
                clientToServerBytes = clientToServer,
                serverToClientMibPerSecond = mib(serverToClient, wall),
                clientToServerMibPerSecond = mib(clientToServer, wall)
            )
        }

    private fun measureLoadedLatency(
        transport: TransportId,
        client: BenchClient,
        host: String,
        port: Int,
        cfg: TransportConfig
    ): TransportMeasurement {
        return ClientSession.open(
            client,
            host,
            port,
            30_000
        ).use { session ->
            val idleValues = pingSeries(
                session,
                LOADED_PING_BYTES,
                LOADED_IDLE_SAMPLES,
                LOADED_PING_INTERVAL_MILLIS
            )

            val cpuStart = processCpuNanos()
            val streamFuture = session.startStreaming(STREAM_CHUNK_BYTES)
            val loadedCount = (cfg.throughputDurationMillis / LOADED_PING_INTERVAL_MILLIS)
                    .coerceAtLeast(1L)
                    .toInt()
            val loadedValues = pingSeries(
                session,
                LOADED_PING_BYTES,
                loadedCount,
                LOADED_PING_INTERVAL_MILLIS
            )
            session.stopStreaming()
            val stream = await(streamFuture)
            val cpuEnd = processCpuNanos()
            val stats = awaitStats(session.requestReport(), cfg.runTimeoutMillis)

            val idle = LatencySamples().also { s -> idleValues.forEach { s.add(it) } }
            val loaded = LatencySamples().also { s -> loadedValues.forEach { s.add(it) } }
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
                processCpuNanos = cpuDeltaNanos(cpuStart, cpuEnd),
                streamMibPerSecond = mib(stats.bytesReceived, stream.wallNanos),
                integrityError = integrityError(stats, session, stream.bytesWritten),
                configuredDurationNanos = TimeUnit.MILLISECONDS.toNanos(
                    cfg.throughputDurationMillis
                )
            )
        }
    }

    /**
     * Issues [count] pings on an absolute {@code t0 + i * interval} schedule, keeping them concurrently
     * in flight, then collects the round-trip samples. Because the next send time is never derived from
     * the previous response, this avoids coordinated omission (B-013).
     */
    private fun pingSeries(
        session: ClientSession,
        payloadBytes: Int,
        count: Int,
        intervalMillis: Long
    ): List<Long> {
        val intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMillis)
        val startNanos = System.nanoTime()
        val futures = ArrayList<CompletableFuture<Long>>(count)
        repeat(count) { i ->
            parkUntil(startNanos + i * intervalNanos)
            futures.add(session.pingAsync(payloadBytes))
        }
        return futures.map { it.get(60, TimeUnit.SECONDS) }
    }

    private fun parkUntil(targetNanos: Long) {
        while (true) {
            val remaining = targetNanos - System.nanoTime()
            if (remaining <= 0L) {
                return
            }

            if (remaining > 1_000_000L) {
                Thread.sleep(remaining / 1_000_000L - 1L)
            } else {
                Thread.onSpinWait()
            }
        }
    }

    private fun latencyMeasurement(
        name: String,
        transport: TransportId,
        payloadBytes: Int,
        samples: LatencySamples,
        cpuNanos: Long?
    ): LatencyMeasurementResult =
        samples.summary().let { s ->
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
        cpuNanos: Long?,
        integrityError: Boolean,
        configuredDurationNanos: Long
    ): ThroughputMeasurementResult =
        ThroughputMeasurementResult(
            name = name,
            transport = transport.label,
            payloadBytes = payloadBytes,
            durationNanos = wallNanos,
            payloadBytesTransferred = transferred,
            mibPerSecond = mib(transferred, wallNanos),
            processCpuNanos = cpuNanos,
            integrityError = integrityError,
            configuredDurationNanos = configuredDurationNanos
        )

}
