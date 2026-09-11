package top.tangge233.netbridge.benchmark.common

import java.nio.file.Path

val resultsRoot: Path = Path.of("build", "results")
val jmhDir: Path = resultsRoot.resolve("jmh")
val transportDir: Path = resultsRoot.resolve("transport")
val channelDir: Path = resultsRoot.resolve("channel")
val minecraftShapedDir: Path = resultsRoot.resolve("minecraft-shaped")
val reportsDir: Path = resultsRoot.resolve("reports")
val htmlReportsDir: Path = Path.of("build", "reports", "benchmarks")
val currentDir: Path = Path.of("build", "results", "current")
