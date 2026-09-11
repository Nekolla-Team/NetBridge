package top.tangge233.netbridge.benchmark.common

import top.tangge233.netbridge.benchmark.model.BenchmarkEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

private const val SUITE_VERSION = "1"

fun captureEnvironment(
    nativeLibrary: Path?,
    nativeWorkerCount: Int
): BenchmarkEnvironment {
    val commit = runQuietly(listOf("git", "rev-parse", "HEAD"))
    return BenchmarkEnvironment(
        suiteVersion = SUITE_VERSION,
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
        nativeLibrarySha256 = nativeLibrary?.let { runCatching { sha256Hex(it) }.getOrNull() }
    )
}

private fun runQuietly(command: List<String>): String? =
    try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream
            .bufferedReader(StandardCharsets.UTF_8)
            .readText()
            .trim()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            output.ifBlank { null }
        }
    } catch (_: Exception) {
        null
    }

private fun isGitDirty(): Boolean =
    !runQuietly(listOf("git", "status", "--porcelain")).isNullOrEmpty()

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
