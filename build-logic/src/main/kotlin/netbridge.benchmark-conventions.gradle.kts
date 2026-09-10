import me.champeau.jmh.JMHTask
import me.champeau.jmh.JmhParameters
import top.tangge233.netbridge.build.BuildNativeLibrary
import top.tangge233.netbridge.build.NativePlatform

// ---------------------------------------------------------------------------
// netbridge.benchmark-conventions
//
// One-stop orchestration for the :benchmark module:
//   * Kotlin tooling conventions + JMH plugin
//   * benchmark-native staging (generalized BuildNativeLibrary)
//   * JMH default ("normal") profile + quick/normal suite wrappers
//   * transport / channel / minecraft-shaped JavaExec launchers
//   * report finalizers that render through a separate :benchmark:report
//     runtime (never on the measured benchmark classpath)
//   * compare + top-level catalog aliases
//
// Reporting boundary: benchmark JVMs only write raw JSON + an invocation
// manifest (build/results/current/<task>.json). Rendering happens in a
// separate JVM from the resolvable `benchmarkReportRuntime` configuration.
// ---------------------------------------------------------------------------

val REPORT_MAIN = "top.tangge233.netbridge.benchmarkreport.cli.ReportCliMain"
val COMPARE_MAIN = "top.tangge233.netbridge.benchmarkreport.compare.CompareCliMain"
val INDEX_MAIN = "top.tangge233.netbridge.benchmarkreport.index.IndexCliMain"

plugins {
    id("netbridge.kotlin-tooling-conventions")
    id("me.champeau.jmh")
}

description =
    "On-demand performance analysis toolbox (L0 raw FFM .. L4 Minecraft-shaped)."

// Report generation runtime: a separate classpath resolved from
// :benchmark:report so no report/JTE/Clikt code ever sits on the measured
// runtime or leaks into production packaging.
val benchmarkReportRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// ---------------------------------------------------------------------------
// Isolation: this module never participates in a plain root `build`/`check`.
// ---------------------------------------------------------------------------
tasks.named("check") {
    enabled = false
}

// ---------------------------------------------------------------------------
// JMH default ("normal") profile. Run everything with a stable, forked setup.
// ---------------------------------------------------------------------------
val jmhParams = extensions.getByName("jmh") as JmhParameters

// Keep the plugin's JMH version aligned with gradle/libs.versions.toml
// [versions].jmh (fixed, never dynamic). The catalog accessor for a version
// that shares its key with a plugin id is not generated, hence the literal.
jmhParams.jmhVersion.set("1.37")
jmhParams.includeTests.set(false)
jmhParams.resultFormat.set("JSON")
jmhParams.resultsFile.set(layout.buildDirectory.file("results/jmh/result.json").get().asFile)
jmhParams.humanOutputFile.set(layout.buildDirectory.file("results/jmh/human.txt").get().asFile)
jmhParams.benchmarkMode.set(listOf("avgt"))
jmhParams.timeUnit.set("ns")
jmhParams.fork.set(3)
jmhParams.warmupIterations.set(5)
jmhParams.warmup.set("1s")
jmhParams.iterations.set(8)
jmhParams.timeOnIteration.set("1s")
jmhParams.threads.set(1)
jmhParams.failOnError.set(true)
jmhParams.verbosity.set("NORMAL")
jmhParams.includes.set(listOf(".*"))
jmhParams.jvmArgsAppend.set(
    listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--illegal-native-access=deny"
    )
)

// ---------------------------------------------------------------------------
// Benchmark-only native libraries. Never part of production packaging.
//   runtime probe  : cargo build -p net-bridge-native          (release)
//   L0 probe crate : cargo build -p net-bridge-benchmark-native (release)
// ---------------------------------------------------------------------------
val rustDir = rootProject.layout.projectDirectory.dir("rust")

val runtimeNativeDir = layout.buildDirectory.dir("native/runtime")
val probeNativeDir = layout.buildDirectory.dir("native/probe")

val buildRuntimeNative = tasks.register<BuildNativeLibrary>("buildRuntimeNative") {
    description =
        "Builds production net-bridge-native with --release and stages it for benchmark use."
    group = "benchmark"
    profile.set("release")
    crateName.set("net-bridge-native")
    libraryBaseName.set("net_bridge_native")
    cargoDir.set(rustDir.asFile)
    outputDir.set(runtimeNativeDir)
}

val buildProbeNative = tasks.register<BuildNativeLibrary>("buildProbeNative") {
    description = "Builds the L0 benchmark-only Rust probe crate (--release) and stages it."
    group = "benchmark"
    profile.set("release")
    crateName.set("net-bridge-benchmark-native")
    libraryBaseName.set("net_bridge_benchmark_native")
    cargoDir.set(rustDir.asFile)
    outputDir.set(probeNativeDir)
}

// JMH forks its own JVM, so native paths must be forwarded as -D system
// properties on the forked process command line.
fun stagedNativePath(dir: Provider<out Directory>, baseName: String): String =
    dir.get().asFile
        .resolve(NativePlatform.subdir)
        .resolve(NativePlatform.cdylibNameFor(baseName))
        .absolutePath

val runtimeNativeLibPath: String = stagedNativePath(runtimeNativeDir, "net_bridge_native")
val probeNativeLibPath: String = stagedNativePath(probeNativeDir, "net_bridge_benchmark_native")

fun reportDirFor(taskName: String): Provider<Directory> =
    layout.buildDirectory.dir("reports/benchmarks/$taskName")

// ---------------------------------------------------------------------------
// JMH report finalizer: renders build/results/jmh/<task>.json through the
// standalone :benchmark:report runtime.
// ---------------------------------------------------------------------------
fun wireJmhReporting(
    jmhTaskName: String,
    jsonFileSub: String = "$jmhTaskName.json",
    txtFileSub: String = "$jmhTaskName.txt"
) {
    val reportTaskName = "${jmhTaskName}Report"
    val jmhTask = tasks.named<JMHTask>(jmhTaskName)

    val reportTask = tasks.register<JavaExec>(reportTaskName) {
        group = "benchmark"
        description = "Generates standalone HTML benchmark report for $jmhTaskName."
        classpath = benchmarkReportRuntime
        mainClass.set(REPORT_MAIN)

        val jsonFile = layout.buildDirectory.file("results/jmh/$jsonFileSub")
        val txtFile = layout.buildDirectory.file("results/jmh/$txtFileSub")
        val reportDir = reportDirFor(jmhTaskName)

        inputs.file(jsonFile)
        inputs.file(txtFile).optional(true)
        outputs.dir(reportDir)

        argumentProviders.add {
            buildList {
                add("--task"); add(jmhTaskName)
                add("--suite"); add("jmh")
                add("--raw"); add(jsonFile.get().asFile.absolutePath)
                add("--jmh-output"); add(txtFile.get().asFile.absolutePath)
                add("--report-dir"); add(reportDir.get().asFile.absolutePath)
            }
        }
    }

    jmhTask.configure {
        // Stale result cleanup before running this benchmark task.
        doFirst {
            val json = layout.buildDirectory.file("results/jmh/$jsonFileSub").get().asFile
            val txt = layout.buildDirectory.file("results/jmh/$txtFileSub").get().asFile
            val reportDir = reportDirFor(jmhTaskName).get().asFile
            if (json.exists()) json.delete()
            if (txt.exists()) txt.delete()
            if (reportDir.exists()) reportDir.deleteRecursively()
        }
        finalizedBy(reportTask)
    }
}

// ---------------------------------------------------------------------------
// Manifest-based report finalizer for the non-JMH launchers. The benchmark
// JVM writes build/results/current/<task>.json (InvocationManifest) after its
// raw JSON succeeds; the finalizer reads it to render. If the run never
// produced a manifest (e.g. a failed run), the report step is skipped.
// ---------------------------------------------------------------------------
fun wireManifestReporting(taskName: String) {
    val reportTaskName = "${taskName}Report"
    val manifestFile = layout.buildDirectory.file("results/current/$taskName.json")
    val reportTask = tasks.register<JavaExec>(reportTaskName) {
        group = "benchmark"
        description = "Renders the standalone HTML benchmark report for $taskName."
        classpath = benchmarkReportRuntime
        mainClass.set(REPORT_MAIN)

        val reportDir = reportDirFor(taskName)
        inputs.file(manifestFile).optional(true)
        outputs.dir(reportDir)

        onlyIf {
            manifestFile.get().asFile.exists()
        }

        argumentProviders.add {
            buildList {
                add("--task"); add(taskName)
                add("--manifest"); add(manifestFile.get().asFile.absolutePath)
                add("--report-dir"); add(reportDir.get().asFile.absolutePath)
            }
        }
    }
    tasks.named<JavaExec>(taskName).configure {
        finalizedBy(reportTask)
    }
}

// Thin JMH wrappers: only override include regex, iteration profile, native
// dependency and native-path system property. The plugin provides the actual
// JMH implementation via jmhJar + the JMH runner.
fun registerJmhWrapper(
    taskName: String,
    taskDescription: String,
    includeRegex: String,
    nativeDep: Task? = null,
    nativeSysProps: List<Pair<String, String>> = emptyList(),
    quick: Boolean = false
) {
    tasks.register<JMHTask>(taskName) {
        group = "benchmark"
        description = taskDescription

        jmhClasspath.from(configurations.named("jmh"))
        testRuntimeClasspath.from(configurations.named("jmhRuntimeClasspath"))
        jarArchive.set(tasks.named<Jar>("jmhJar").flatMap { it.archiveFile })

        resultsFile.set(layout.buildDirectory.file("results/jmh/$taskName.json").get().asFile)
        humanOutputFile.set(layout.buildDirectory.file("results/jmh/$taskName.txt").get().asFile)

        includes.set(listOf(includeRegex))
        if (nativeDep != null) {
            dependsOn(nativeDep)
        }
        jvmArgsAppend.set(
            buildList {
                add("--enable-native-access=ALL-UNNAMED")
                add("--illegal-native-access=deny")
                nativeSysProps.forEach { (name, value) ->
                    add("-D$name=$value")
                }
            }
        )
        if (quick) {
            fork.set(1)
            warmupIterations.set(2)
            warmup.set("500ms")
            iterations.set(3)
            timeOnIteration.set("500ms")
        }
    }
    wireJmhReporting(taskName)
}

registerJmhWrapper(
    taskName = "jmhQuick",
    taskDescription = "Quick microbenchmark pass over every benchmark (for a fast look only).",
    includeRegex = ".*",
    nativeDep = buildRuntimeNative.get(),
    nativeSysProps = listOf(
        "netbridge.native.path" to runtimeNativeLibPath,
        "netbridge.probe.native.path" to probeNativeLibPath
    ),
    quick = true
)

registerJmhWrapper(
    taskName = "jmhFfm",
    taskDescription = "L0 raw FFM benchmarks (probe native, no production classes).",
    includeRegex = ".*[Ff]fm.*",
    nativeDep = buildProbeNative.get(),
    nativeSysProps = listOf("netbridge.probe.native.path" to probeNativeLibPath)
)

registerJmhWrapper(
    taskName = "jmhBridge",
    taskDescription = "L1 production Java<->native bridge benchmarks (release native required).",
    includeRegex = ".*[Bb]ridge.*",
    nativeDep = buildRuntimeNative.get(),
    nativeSysProps = listOf("netbridge.native.path" to runtimeNativeLibPath)
)

registerJmhWrapper(
    taskName = "jmhArena",
    taskDescription = "Pure-Java FFM allocation microbenchmarks (no native library needed).",
    includeRegex = ".*Arena.*",
    quick = true
)

registerJmhWrapper(
    taskName = "jmhChannel",
    taskDescription = "L3A NativeChannel microbenchmarks over a fake NativeConnection (no native).",
    includeRegex = ".*[Cc]hannel.*"
)

registerJmhWrapper(
    taskName = "jmhChannelQuick",
    taskDescription = "Fast L3A NativeChannel microbenchmarks (no native).",
    includeRegex = ".*[Cc]hannel.*",
    quick = true
)

// The full micro suite needs both natives (L0 probe + L1 production bridge).
tasks.named<JMHTask>("jmh") {
    dependsOn(buildRuntimeNative)
    dependsOn(buildProbeNative)
    jvmArgsAppend.set(
        listOf(
            "--enable-native-access=ALL-UNNAMED",
            "--illegal-native-access=deny",
            "-Dnetbridge.native.path=$runtimeNativeLibPath",
            "-Dnetbridge.probe.native.path=$probeNativeLibPath"
        )
    )
}
wireJmhReporting("jmh", jsonFileSub = "result.json", txtFileSub = "human.txt")

tasks.named<JMHTask>("jmhQuick") {
    dependsOn(buildProbeNative)
}

// ---------------------------------------------------------------------------
// L2 transport harness (plain JavaExec, no JMH).
// ---------------------------------------------------------------------------
val benchmarkTransportProp = providers.gradleProperty("benchmarkTransport")
val benchmarkCaseProp = providers.gradleProperty("benchmarkCase")
val benchmarkHostProp = providers.gradleProperty("benchmarkHost")
val benchmarkPortProp = providers.gradleProperty("benchmarkPort")
val benchmarkWorkloadProp = providers.gradleProperty("benchmarkWorkload")
val benchmarkPayloadProp = providers.gradleProperty("benchmarkPayload")
val benchmarkIterationsProp = providers.gradleProperty("benchmarkIterations")

fun booleanNeedsNativeTransport(raw: String?): Boolean =
    raw.isNullOrBlank()
            || raw.equals("all", ignoreCase = true)
            || raw.split(",").any { !it.equals("tcp", ignoreCase = true) }

val nativeNeededForTransport = booleanNeedsNativeTransport(benchmarkTransportProp.orNull)

fun JavaExec.addBenchmarkLauncher() {
    group = "benchmark"
    isIgnoreExitValue = false
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--illegal-native-access=deny"
    )
}

fun JavaExec.nativePathArgWhenNeeded() {
    if (nativeNeededForTransport) {
        dependsOn(buildRuntimeNative)
        jvmArgs("-Dnetbridge.native.path=$runtimeNativeLibPath")
    }
}

fun registerTransportTask(
    name: String,
    description: String,
    mode: String,
    extraArgs: List<String> = emptyList()
) {
    val exec = tasks.register<JavaExec>(name) {
        this.description = description
        addBenchmarkLauncher()
        nativePathArgWhenNeeded()
        mainClass.set("top.tangge233.netbridge.benchmark.transport.TransportBenchmarkMain")
        val reportDir = reportDirFor(name).get().asFile.absolutePath
        jvmArgs("-Dnetbridge.benchmark.task=$name", "-Dnetbridge.benchmark.report.dir=$reportDir")
        if (mode != "server") {
            doFirst {
                val dir = reportDirFor(name).get().asFile
                if (dir.exists()) dir.deleteRecursively()
                val manifest = layout.buildDirectory.file("results/current/$name.json").get().asFile
                if (manifest.exists()) manifest.delete()
            }
        }
        argumentProviders.add {
            buildList {
                add(mode)
                benchmarkTransportProp.orNull?.let { add("--transport"); add(it) }
                benchmarkCaseProp.orNull?.let { add("--case"); add(it) }
                benchmarkHostProp.orNull?.let { add("--host"); add(it) }
                benchmarkPortProp.orNull?.let { add("--port"); add(it) }
                benchmarkWorkloadProp.orNull?.let { add("--duration"); add(it) }
                benchmarkPayloadProp.orNull?.let { add("--payload"); add(it) }
                benchmarkIterationsProp.orNull?.let { add("--iterations"); add(it) }
                addAll(extraArgs)
            }
        }
    }
    if (mode != "server") {
        wireManifestReporting(name)
    }
}

registerTransportTask(
    "transportBenchmark",
    "L2 transport comparison harness (default: TCP/QUIC/KCP loopback on localhost).",
    "local"
)
registerTransportTask(
    "transportServer",
    "Remote-mode transport server (blocks; pair with :benchmark:transportClient).",
    "server"
)
registerTransportTask(
    "transportClient",
    "Remote-mode transport client; measures against a running :benchmark:transportServer.",
    "client"
)

// ---------------------------------------------------------------------------
// L3B NativeChannel real-integration and L4A Minecraft-shaped traffic.
// ---------------------------------------------------------------------------
tasks.register<JavaExec>("channelIntegration") {
    description =
        "L3B NativeChannel->FFM->QUIC/KCP end-to-end benchmark (release native required)."
    group = "benchmark"
    classpath = sourceSets.main.get().runtimeClasspath
    isIgnoreExitValue = false
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--illegal-native-access=deny",
        "-Dnetbridge.native.path=$runtimeNativeLibPath",
        "-Dnetbridge.benchmark.task=channelIntegration",
        "-Dnetbridge.benchmark.report.dir=${reportDirFor("channelIntegration").get().asFile.absolutePath}"
    )
    doFirst {
        val dir = reportDirFor("channelIntegration").get().asFile
        if (dir.exists()) dir.deleteRecursively()
        val manifest = layout.buildDirectory.file("results/current/channelIntegration.json")
                .get().asFile
        if (manifest.exists()) manifest.delete()
    }
    dependsOn(buildRuntimeNative)
    mainClass.set("top.tangge233.netbridge.benchmark.channel.NativeChannelIntegrationBenchmarkMain")
    argumentProviders.add {
        buildList {
            benchmarkTransportProp.orNull?.let { add("--transport"); add(it) }
            benchmarkCaseProp.orNull?.let { add("--case"); add(it) }
            benchmarkWorkloadProp.orNull?.let { add("--duration"); add(it) }
            benchmarkIterationsProp.orNull?.let { add("--iterations"); add(it) }
        }
    }
}
wireManifestReporting("channelIntegration")

tasks.register<JavaExec>("minecraftTraffic") {
    description = "L4A Minecraft-shaped workload comparison (TCP/QUIC/KCP loopback)."
    group = "benchmark"
    addBenchmarkLauncher()
    nativePathArgWhenNeeded()
    val reportDir = reportDirFor("minecraftTraffic").get().asFile.absolutePath
    jvmArgs(
        "-Dnetbridge.benchmark.task=minecraftTraffic",
        "-Dnetbridge.benchmark.report.dir=$reportDir"
    )
    doFirst {
        val dir = reportDirFor("minecraftTraffic").get().asFile
        if (dir.exists()) dir.deleteRecursively()
        val manifest = layout.buildDirectory.file("results/current/minecraftTraffic.json")
                .get().asFile
        if (manifest.exists()) manifest.delete()
    }
    mainClass.set("top.tangge233.netbridge.benchmark.workload.MinecraftTrafficMain")
    argumentProviders.add {
        buildList {
            benchmarkTransportProp.orNull?.let { add("--transport"); add(it) }
            benchmarkWorkloadProp.orNull?.let { add("--workload"); add(it) }
        }
    }
}
wireManifestReporting("minecraftTraffic")

tasks.register<JavaExec>("renderMinecraftSession") {
    description =
        "Renders a standalone HTML report for a real Minecraft session JSON file " +
                "(produced by MinecraftBenchmarkRecorder) -Pinput=<path to .json>."
    group = "benchmark"
    classpath = benchmarkReportRuntime
    isIgnoreExitValue = false
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny")
    mainClass.set(REPORT_MAIN)
    val reportDir = reportDirFor("renderMinecraftSession").get().asFile.absolutePath
    argumentProviders.add {
        buildList {
            add("--task"); add("renderMinecraftSession")
            add("--suite"); add("minecraft")
            add("--raw"); add(
            providers.gradleProperty("input").getOrElse(
                throw GradleException(
                    "renderMinecraftSession requires -Pinput=<path to minecraft session JSON>"
                )
            )
        )
            add("--report-dir"); add(reportDir)
        }
    }
    doFirst {
        val dir = reportDirFor("renderMinecraftSession").get().asFile
        if (dir.exists()) dir.deleteRecursively()
    }
}

// ---------------------------------------------------------------------------
// Compare / catalog (pure calculators over already-written JSON results). They
// run on the :benchmark:report runtime, not the measured benchmark classpath.
// ---------------------------------------------------------------------------
tasks.register<JavaExec>("compare") {
    description = "Prints per-row deltas between two result documents (-Pbefore, -Pafter)."
    group = "benchmark"
    isIgnoreExitValue = false
    classpath = benchmarkReportRuntime
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--illegal-native-access=deny"
    )
    mainClass.set(COMPARE_MAIN)
    argumentProviders.add {
        buildList {
            providers.gradleProperty("before").orNull?.let { add("--before"); add(it) }
            providers.gradleProperty("after").orNull?.let { add("--after"); add(it) }
        }
    }
}

tasks.register<JavaExec>("benchmarkReport") {
    description = "Refreshes the top-level HTML report catalog at build/reports/benchmarks/index.html."
    group = "benchmark"
    isIgnoreExitValue = false
    classpath = benchmarkReportRuntime
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--illegal-native-access=deny"
    )
    mainClass.set(INDEX_MAIN)
    argumentProviders.add {
        listOf(
            "--reports-dir",
            layout.buildDirectory.dir("reports/benchmarks").get().asFile.absolutePath
        )
    }
}

// ---------------------------------------------------------------------------
// Aggregate entry point: run every on-demand benchmark layer in one invocation.
// ---------------------------------------------------------------------------
val aggregateBenchmarkTasks = listOf(
    "jmh",
    "transportBenchmark",
    "channelIntegration",
    "minecraftTraffic"
)

tasks.register("benchmarkAll") {
    description =
        "Runs every benchmark layer (JMH L0/L1/L3A, L2 transport, L3B NativeChannel integration, " +
                "L4A Minecraft-shaped workloads) and refreshes the top-level report catalog."
    group = "benchmark"
    dependsOn(aggregateBenchmarkTasks)
    dependsOn("benchmarkReport")
}

tasks.named("benchmarkReport") {
    mustRunAfter(*aggregateBenchmarkTasks.map { "${it}Report" }.toTypedArray())
}
