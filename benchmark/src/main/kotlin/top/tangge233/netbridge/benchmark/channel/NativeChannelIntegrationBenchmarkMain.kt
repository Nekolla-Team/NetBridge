@file:JvmName("NativeChannelIntegrationBenchmarkMain")

package top.tangge233.netbridge.benchmark.channel

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import top.tangge233.netbridge.benchmark.common.captureEnvironment
import top.tangge233.netbridge.benchmark.common.channelDir
import top.tangge233.netbridge.benchmark.common.writeManifest
import top.tangge233.netbridge.benchmark.transport.TransportConfig
import top.tangge233.netbridge.benchmark.transport.TransportId
import top.tangge233.netbridge.benchmark.transport.TransportResultWriter
import top.tangge233.netbridge.benchmark.transport.TransportRunner
import java.nio.file.Path

/**
 * L3B end-to-end NativeChannel benchmark (non-JMH, async): pipeline -> NativeChannel -> FFM
 * -> QUIC/KCP on localhost. Reuses the transport measurement model so its latency/throughput
 * output is directly comparable with the transport suite.
 */
class NativeChannelIntegrationBenchmarkCli : CliktCommand(name = "native-channel-integration") {

    private val transport: String? by option(
        "--transport",
        help = "quic|kcp-balanced|kcp-aggressive|all (comma-separated)"
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
        help = "server host (default localhost)"
    )

    private val port: Int? by option(
        "--port",
        help = "server bind port"
    ).int()

    private val workers: Int? by option(
        "--workers",
        help = "event loop + native worker threads"
    ).int()

    private val nativeLib: String? by option(
        "--native-lib",
        help = "native library path override"
    )

    override fun run() {
        val base = TransportConfig.defaults().copy(
            transports = listOf(
                TransportId.QUIC,
                TransportId.KCP_BALANCED,
                TransportId.KCP_AGGRESSIVE
            )
        )

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

        val nativeLibrary = requireNotNull(resolveNativeLibrary(this.nativeLib, cfg)) {
            "set -Dnetbridge.native.path or pass --native-lib"
        }
        val configured = cfg.copy(nativeLibrary = nativeLibrary)

        val runner = TransportRunner(System.out)
        val results = runner.runAll(configured)

        val env = captureEnvironment(
            nativeLibrary,
            configured.workers
        )
        val out = TransportResultWriter.writeTo(
            channelDir,
            "channel",
            "native-channel",
            env,
            configured,
            results
        )
        println("channel results written to ${out.toAbsolutePath()}")

        val taskName = System.getProperty(
            "netbridge.benchmark.task",
            "channelIntegration"
        )
        writeManifest(taskName, "channel", out)
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

}

fun main(args: Array<String>) = NativeChannelIntegrationBenchmarkCli().main(args)
