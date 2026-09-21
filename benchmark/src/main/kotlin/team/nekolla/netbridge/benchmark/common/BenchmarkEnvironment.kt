package team.nekolla.netbridge.benchmark.common

import team.nekolla.netbridge.benchmark.model.BenchmarkEnvironment
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

private const val SUITE_VERSION = "2"
private const val MEASUREMENT_METHOD_VERSION = "transport-v2"

fun captureEnvironment(
    nativeLibrary: Path?,
    nativeWorkerCount: Int
): BenchmarkEnvironment {
    val commit = runQuietly(listOf("git", "rev-parse", "HEAD"))
    return BenchmarkEnvironment(
        suiteVersion = SUITE_VERSION,
        measurementMethodVersion = MEASUREMENT_METHOD_VERSION,
        timestamp = Instant.now(),
        gitCommit = commit,
        gitDirty = commit?.let { isGitDirty() },
        os = System.getProperty("os.name", "-"),
        osVersion = System.getProperty("os.version", "-"),
        arch = System.getProperty("os.arch", "-"),
        availableProcessors = Runtime.getRuntime().availableProcessors(),
        jdkVendor = System.getProperty("java.vendor", "-"),
        jdkVersion = System.getProperty("java.version", "-"),
        jvmName = System.getProperty("java.vm.name", "-"),
        jvmVersion = System.getProperty("java.vm.version", "-"),
        maxHeapBytes = Runtime.getRuntime().maxMemory(),
        rustVersion = runQuietly(listOf("rustc", "--version")),
        nativeWorkerCount = nativeWorkerCount,
        nativeLibrary = nativeLibrary?.toAbsolutePath()?.toString(),
        nativeLibrarySha256 = nativeLibrary?.let { runCatching { sha256Hex(it) }.getOrNull() },
        cpuModel = readCpuModel(),
        logicalCores = Runtime.getRuntime().availableProcessors(),
        jvmArgs = ManagementFactory.getRuntimeMXBean()
                .inputArguments.joinToString(" ").ifBlank { null },
        jvmGc = ManagementFactory.getGarbageCollectorMXBeans()
                .joinToString(",") { it.name }.ifBlank { null },
        loadAverage = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
                .takeIf { it >= 0.0 },
        containerHint = containerHint(),
        networkProfile = System.getProperty("netbridge.benchmark.networkProfile")
                ?.ifBlank { null }
    )
}

/**
 * Runs [command] capturing merged stdout/stderr while draining the stream on a daemon thread.
 * The drain happens concurrently with [Process.waitFor], so a child that never closes stdout can
 * no longer make the timeout ineffective (B-035).
 */
private fun runCaptured(command: List<String>): String? =
    try {
        val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        val output = StringBuilder()
        val drainer = Thread {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    synchronized(output) { output.appendLine(line) }
                }
            }
        }
        drainer.isDaemon = true
        drainer.start()

        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        drainer.join(1_000)
        synchronized(output) { output.toString() }.trim()
    } catch (_: Exception) {
        null
    }

private fun runQuietly(command: List<String>): String? =
    runCaptured(command)?.ifBlank { null }

private fun isGitDirty(): Boolean? =
    runCaptured(listOf("git", "status", "--porcelain"))?.isNotEmpty()

private fun readCpuModel(): String? {
    val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
    return try {
        when {
            os.contains("linux") -> Files.readAllLines(Path.of("/proc/cpuinfo"))
                    .firstOrNull { it.startsWith("model name") || it.startsWith("Model") }
                    ?.substringAfter(':')
                    ?.trim()

            os.contains("mac") ->
                runCaptured(listOf("sysctl", "-n", "machdep.cpu.brand_string"))

            os.contains("win") -> System.getenv("PROCESSOR_IDENTIFIER")

            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun containerHint(): String? {
    if (Files.exists(Path.of("/.dockerenv"))) {
        return "docker"
    }
    val cgroup = runCatching { Files.readString(Path.of("/proc/1/cgroup")) }.getOrNull()
    return if (cgroup != null
        && (cgroup.contains("docker") || cgroup.contains("kubepods") || cgroup.contains("containerd"))
    ) {
        "container"
    } else {
        null
    }
}

fun sha256Hex(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}
