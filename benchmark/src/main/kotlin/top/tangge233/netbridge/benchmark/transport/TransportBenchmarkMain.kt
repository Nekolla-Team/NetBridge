@file:JvmName("TransportBenchmarkMain")

package top.tangge233.netbridge.benchmark.transport

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import top.tangge233.netbridge.benchmark.common.captureEnvironment
import top.tangge233.netbridge.benchmark.common.writeManifest
import java.nio.file.Path

/**
 * L2 transport benchmark entry point (local / server / client).
 */
class TransportBenchmarkCli : CliktCommand(
    name = "transport-benchmark",
) {

    private val mode: String? by argument("mode").optional()

    private val transport: String? by option(
        "--transport",
        help = "tcp|quic|kcp-balanced|kcp-aggressive|all (comma-separated)"
    )

    private val case: String? by option(
        "--case",
        "--cases",
        help = "connect|rtt|throughput|bidirectional|loaded-latency|all (comma)"
    )

    private val payload: Long? by option(
        "--payload",
        help = "RTT payload bytes (default 1024)"
    ).long()

    private val duration: Long? by option(
        "--duration",
        help = "throughput window milliseconds (default 10000)"
    ).long()

    private val iterations: Int? by option(
        "--iterations",
        help = "connect/RTT measured iterations"
    ).int()

    private val host: String? by option(
        "--host",
        help = "remote server host (client mode)"
    )

    private val port: Int? by option(
        "--port",
        help = "server bind / remote target port"
    ).int()

    private val workers: Int? by option(
        "--workers",
        help = "event loop + native worker threads"
    ).int()

    private val server: Boolean by option(
        "--server",
        help = "alias for mode server"
    ).flag()

    private val nativeLib: String? by option(
        "--native-lib",
        help = "native library path override"
    )

    override fun run() {
        val mode = this.mode
        if (mode != null
            && mode !in setOf("local", "server", "client")
        ) {
            throw UsageError("unknown mode: $mode (expected local|server|client)")
        }
        val effectiveMode = mode ?: if (server) "server" else "local"

        val base = if (effectiveMode == "server" || effectiveMode == "client") {
            TransportConfig.defaults().copy(port = 0, startServer = false)
        } else {
            TransportConfig.defaults()
        }

        val transport = this.transport
        val case = this.case
        val payload = this.payload
        val duration = this.duration
        val iterations = this.iterations
        val host = this.host
        val port = this.port
        val workers = this.workers

        val cfg = base
            .let { if (transport != null) it.withTransport(transport) else it }
            .let { if (case != null) it.withCase(case) else it }
            .let { if (payload != null) it.copy(rttPayloadBytes = payload) else it }
            .let { if (duration != null) it.copy(throughputDurationMillis = duration) else it }
            .let {
                if (iterations != null) it.copy(
                    connectIterations = iterations,
                    rttMeasuredIterations = iterations
                ) else it
            }
            .let { if (host != null) it.copy(host = host) else it }
            .let { if (port != null) it.copy(port = port) else it }
            .let { if (workers != null) it.copy(workers = workers) else it }

        val nativeLibrary = resolveNativeLibrary(this.nativeLib, cfg)
        requireNativeForTransports(cfg, nativeLibrary)
        val configured = cfg.copy(nativeLibrary = nativeLibrary)

        if (effectiveMode == "server") {
            runServer(configured, nativeLibrary)
            return
        }

        runClientOrLocal(effectiveMode, configured, nativeLibrary)
    }

    private fun resolveNativeLibrary(
        fromArg: String?,
        cfg: TransportConfig
    ): Path? =
        fromArg?.let(Path::of)
            ?: cfg.nativeLibrary
            ?: System.getProperty("netbridge.native.path")
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of)

    private fun requireNativeForTransports(
        cfg: TransportConfig,
        nativeLibrary: Path?
    ) {
        val nativeRequested = cfg.transports.any { it.nativeTransport }
        require(!nativeRequested || nativeLibrary != null) {
            "native transports need a native library: set -Dnetbridge.native.path or pass --native-lib"
        }
    }

    private fun runServer(cfg: TransportConfig, nativeLibrary: Path?) {
        require(cfg.transports.size == 1) {
            "server mode requires exactly one --transport"
        }
        require(cfg.port != 0) {
            "server mode requires --port"
        }

        val transport = cfg.transports.first()
        startServer(transport, cfg, nativeLibrary).use {
            println("netbridge-benchmark server listening transport=${transport.label} port=${it.port}")
            System.out.flush()
            try {
                Thread.currentThread().join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun runClientOrLocal(
        mode: String,
        cfg: TransportConfig,
        nativeLibrary: Path?
    ) {
        val label = if (mode == "client") "client" else "transport"
        val runner = TransportRunner(System.out)
        val results = if (mode == "client") {
            require(cfg.transports.size == 1) {
                "client mode requires exactly one --transport"
            }
            val transport = cfg.transports.first()
            runner.runAgainst(
                transport,
                cfg.host,
                cfg.port,
                cfg
            )
        } else {
            runner.runAll(cfg)
        }

        val env = captureEnvironment(nativeLibrary, cfg.workers)
        val out = TransportResultWriter.write(
            label,
            env,
            cfg,
            results
        )
        println("results written to ${out.toAbsolutePath()}")

        val taskName = System.getProperty(
            "netbridge.benchmark.task",
            if (mode == "client") "transportClient" else "transportBenchmark"
        )
        writeManifest(taskName, "transport", out)
    }

    private fun startServer(
        transport: TransportId,
        cfg: TransportConfig,
        nativeLibrary: Path?
    ): BenchServerEndpoint {
        return when (transport) {
            TransportId.TCP -> TcpServerEndpoint(cfg.workers, cfg.port)

            TransportId.QUIC,
            TransportId.KCP_BALANCED,
            TransportId.KCP_AGGRESSIVE -> NativeServerEndpoint(
                transport,
                requireNotNull(nativeLibrary) {
                    "native transport requires a native library"
                },
                cfg.workers,
                cfg.port,
                cfg.connectIterations + 16
            )
        }
    }
}

fun main(args: Array<String>) = TransportBenchmarkCli().main(args)
