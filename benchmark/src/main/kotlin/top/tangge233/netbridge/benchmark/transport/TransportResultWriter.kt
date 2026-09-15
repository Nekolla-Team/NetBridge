package top.tangge233.netbridge.benchmark.transport

import top.tangge233.netbridge.benchmark.common.timestamp
import top.tangge233.netbridge.benchmark.common.transportDir
import top.tangge233.netbridge.benchmark.model.*
import java.nio.file.Path

/** Serializes a transport/channel run to the typed persisted schema and returns the raw file. */
object TransportResultWriter {

    fun write(
        label: String,
        environment: BenchmarkEnvironment,
        config: TransportConfig,
        results: List<TransportMeasurement>
    ): Path = writeTo(
        transportDir,
        "transport",
        label,
        environment,
        config,
        results
    )

    fun writeTo(
        dir: Path,
        suite: String,
        label: String,
        environment: BenchmarkEnvironment,
        config: TransportConfig,
        results: List<TransportMeasurement>
    ): Path {
        val target = dir.resolve("$label-${timestamp()}.json")
        val doc = TransportRunDocument(
            suite = suite,
            environment = environment,
            configuration = configurationOf(config),
            results = results
        )
        BenchmarkJson.writeFile(target, doc)
        return target
    }

    fun configurationOf(config: TransportConfig): TransportConfigurationSnapshot =
        TransportConfigurationSnapshot(
            transports = config.transports.map { it.label },
            cases = config.cases.map { it.arg },
            host = config.host,
            port = config.port,
            workers = config.workers,
            rttPayloadBytes = config.rttPayloadBytes,
            throughputDurationMillis = config.throughputDurationMillis,
            connectIterations = config.connectIterations,
            rttMeasuredIterations = config.rttMeasuredIterations,
            startServer = config.startServer,
            nativeLibrary = config.nativeLibrary?.toString()
        )

}
