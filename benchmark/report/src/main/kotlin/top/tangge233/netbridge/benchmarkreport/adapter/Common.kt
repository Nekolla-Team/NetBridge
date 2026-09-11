package top.tangge233.netbridge.benchmarkreport.adapter

import top.tangge233.netbridge.benchmark.model.BenchmarkEnvironment
import top.tangge233.netbridge.benchmark.model.BenchmarkSuite
import top.tangge233.netbridge.benchmark.model.TransportConfigurationSnapshot
import top.tangge233.netbridge.benchmarkreport.dsl.KeyValue
import top.tangge233.netbridge.benchmarkreport.format.HumanUnits

internal object SuiteTitles {

    fun of(suite: String): String =
        when (BenchmarkSuite.fromId(suite)) {
            BenchmarkSuite.TRANSPORT -> "L2 Transport Comparison"
            BenchmarkSuite.CHANNEL -> "L3B NativeChannel Integration"
            BenchmarkSuite.MINECRAFT_SHAPED -> "L4A Minecraft-shaped Workload"
            BenchmarkSuite.MINECRAFT -> "Real Minecraft Session"
            null -> "Benchmark"
        }

    fun jmh(results: List<JmhResultDto>): String {
        val className = results
            .firstOrNull { it.benchmark.substringBeforeLast('.').isNotBlank() }
            ?.benchmark?.substringBeforeLast('.')
            ?: return "JMH Microbenchmarks"
        return when {
            className.contains(".ffm.") -> "L0 Raw FFM"
            className.contains(".bridge.") -> "L1 Production Native Bridge"
            className.contains(".channel.") -> "L3A NativeChannel"
            else -> "JMH Microbenchmarks"
        }
    }

}

internal fun gitLabel(
    gitCommit: String?,
    gitDirty: Boolean?
): String? =
    if (gitCommit.isNullOrBlank()) {
        null
    } else if (gitDirty == true) {
        "$gitCommit (dirty)"
    } else {
        gitCommit
    }

internal fun environmentEntries(env: BenchmarkEnvironment): List<KeyValue> =
    buildList {
        fun put(key: String, value: String?) {
            if (value != null) add(KeyValue(key, value))
        }

        put("suiteVersion", env.suiteVersion)
        put("timestamp", env.timestamp.toString())
        put("gitCommit", env.gitCommit)
        put("gitDirty", env.gitDirty?.toString())
        put("os", env.os)
        put("osVersion", env.osVersion)
        put("arch", env.arch)
        put("availableProcessors", env.availableProcessors.toString())
        put("jdkVendor", env.jdkVendor)
        put("jdkVersion", env.jdkVersion)
        put("jvmName", env.jvmName)
        put("jvmVersion", env.jvmVersion)
        put("maxHeapBytes", env.maxHeapBytes.toString())
        put("rustVersion", env.rustVersion)
        put("nativeWorkerCount", env.nativeWorkerCount.toString())
        put("nativeLibrary", env.nativeLibrary)
        put("nativeLibrarySha256", env.nativeLibrarySha256)
        for ((name, node) in env.unknownFields()) {
            add(KeyValue(name, node.toString()))
        }
    }

internal fun transportConfigurationEntries(
    config: TransportConfigurationSnapshot
): List<KeyValue> =
    listOf(
        KeyValue("transports", config.transports.toString()),
        KeyValue("cases", config.cases.toString()),
        KeyValue("host", config.host),
        KeyValue("port", config.port.toString()),
        KeyValue("workers", config.workers.toString()),
        KeyValue("rttPayloadBytes", config.rttPayloadBytes.toString()),
        KeyValue("throughputDurationMillis", config.throughputDurationMillis.toString()),
        KeyValue("connectIterations", config.connectIterations.toString()),
        KeyValue("rttMeasuredIterations", config.rttMeasuredIterations.toString()),
        KeyValue("startServer", config.startServer.toString()),
        KeyValue("nativeLibrary", config.nativeLibrary ?: HumanUnits.MISSING)
    )

internal fun List<String>.pretty(): String =
    joinToString(
        prefix = "[",
        separator = ", ",
        postfix = "]"
    )
