@file:JvmName("MinecraftTrafficMain")

package top.tangge233.netbridge.benchmark.workload

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import top.tangge233.netbridge.benchmark.common.captureEnvironment
import top.tangge233.netbridge.benchmark.common.minecraftShapedDir
import top.tangge233.netbridge.benchmark.common.timestamp
import top.tangge233.netbridge.benchmark.common.writeManifest
import top.tangge233.netbridge.benchmark.model.BenchmarkJson
import top.tangge233.netbridge.benchmark.model.MinecraftShapedConfigurationSnapshot
import top.tangge233.netbridge.benchmark.model.MinecraftShapedRunDocument
import top.tangge233.netbridge.benchmark.transport.*
import java.lang.System.getProperty
import java.nio.file.Path

/**
 * L4A Minecraft-shaped traffic benchmark: drives the built-in workloads over TCP and/or the
 * native QUIC/KCP transports on localhost and writes a JSON result into
 * {@code build/results/minecraft-shaped/}.
 */
class MinecraftTrafficCli : CliktCommand(
    name = "minecraft-traffic",
) {

    private val transport: String? by option(
        "--transport",
        help = "tcp|quic|kcp-balanced|kcp-aggressive|all (comma-separated)"
    )

    private val workload: String? by option(
        "--workload",
        help = "workload name or comma list (default all)"
    )

    private val workers: Int? by option(
        "--workers",
        help = "event loop + native worker threads"
    ).int()

    private val nativeLib: String? by option(
        "--native-lib",
        help = "native library path override"
    )

    override fun run() {
        val transports = parseTransports(transport)
        val workloads = parseWorkloads(workload)
        val workerCount = workers ?: 2
        val nativeLibrary = resolveNativeLibrary(nativeLib)

        requireNativeForTransports(transports, nativeLibrary)

        val runner = MinecraftWorkloadRunner()
        val results = buildList {
            transports.forEach { transport ->
                startServer(
                    transport,
                    workerCount,
                    nativeLibrary
                ).use { server ->
                    BenchClient.open(
                        transport,
                        workerCount,
                        nativeLibrary
                    ).use { client ->
                        workloads.forEach { workload ->
                            println("[${transport.label}] workload ${workload.name}")
                            add(
                                runner.simulate(
                                    client,
                                    "127.0.0.1",
                                    server.port,
                                    workload
                                )
                            )
                        }
                    }
                }
            }
        }

        val env = captureEnvironment(nativeLibrary, workerCount)
        val config = MinecraftShapedConfigurationSnapshot(
            transports = transports.map { it.label },
            workloads = workloads.map { it.name },
            workers = workerCount
        )

        val target = minecraftShapedDir.resolve("minecraft-shaped-${timestamp()}.json")
        val doc = MinecraftShapedRunDocument(
            suite = "minecraft-shaped",
            environment = env,
            configuration = config,
            results = results
        )
        BenchmarkJson.writeFile(target, doc)
        println("results written to ${target.toAbsolutePath()}")

        val taskName = getProperty(
            "netbridge.benchmark.task",
            "minecraftTraffic"
        )
        writeManifest(taskName, "minecraft-shaped", target)
    }

    private fun parseTransports(raw: String?): List<TransportId> =
        if (raw.isNullOrBlank()
            || raw.equals("all", ignoreCase = true)
        ) {
            listOf(
                TransportId.TCP,
                TransportId.QUIC,
                TransportId.KCP_BALANCED,
                TransportId.KCP_AGGRESSIVE
            )
        } else {
            raw.split(",")
                .mapNotNull { TransportId.parse(it.trim()) }
                .distinct()
        }

    private fun parseWorkloads(raw: String?): List<MinecraftWorkload> =
        if (raw.isNullOrBlank()
            || raw.equals("all", ignoreCase = true)
        ) {
            BuiltinMinecraftWorkloads.all()
        } else {
            raw.split(",")
                .map {
                    BuiltinMinecraftWorkloads.byName(it.trim())
                }
        }

    private fun resolveNativeLibrary(fromArg: String?): Path? =
        fromArg?.let(Path::of)
            ?: getProperty("netbridge.native.path")
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of)

    private fun requireNativeForTransports(
        transports: List<TransportId>,
        nativeLibrary: Path?
    ) {
        val nativeRequested = transports.any { it.nativeTransport }
        require(!nativeRequested || nativeLibrary != null) {
            "native transports need -Dnetbridge.native.path or --native-lib"
        }
    }

    private fun startServer(
        transport: TransportId,
        workerCount: Int,
        nativeLibrary: Path?
    ): BenchServerEndpoint {
        return when (transport) {
            TransportId.TCP -> TcpServerEndpoint(workerCount, 0)

            TransportId.QUIC,
            TransportId.KCP_BALANCED,
            TransportId.KCP_AGGRESSIVE -> NativeServerEndpoint(
                transport,
                requireNotNull(nativeLibrary) {
                    "native transport requires -Dnetbridge.native.path or --native-lib"
                },
                workerCount,
                0,
                64
            )
        }
    }

}

fun main(args: Array<String>) = MinecraftTrafficCli().main(args)
