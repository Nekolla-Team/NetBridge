package top.tangge233.netbridge.benchmark.common

import top.tangge233.netbridge.benchmark.model.BenchmarkJson
import top.tangge233.netbridge.benchmark.model.InvocationManifest
import java.nio.file.Path
import java.time.Instant

fun timestamp(): String =
    Instant.now().toString().replace(':', '-')

fun currentManifest(task: String): Path =
    currentDir.resolve("$task.json")

fun writeManifest(
    task: String,
    suite: String,
    rawResult: Path
) {
    val manifest = InvocationManifest(
        task = task,
        suite = suite,
        rawResult = rawResult.toAbsolutePath().toString()
    )
    BenchmarkJson.writeInvocationManifest(currentManifest(task), manifest)
}
