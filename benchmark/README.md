# net-bridge `:benchmark` module

On-demand performance analysis toolbox for the NetBridge transport stack. It is **never** part of
`check` / `test` / `verifyArchitecture` / CI: it runs only when you ask for it. Nothing in `common`,
`fabric` or `neoforge` depends on it.

Full guide: [`docs/benchmarks/README.md`](../docs/benchmarks/README.md).

## Module architecture

The benchmark tooling is **Kotlin-first** and split across three Gradle modules plus shared
build-logic. None of them is a dependency of `common`, `fabric`, `neoforge` or `minecraft`
(production never pulls Kotlin/JTE/Clikt/Jackson-databind):

| Module              | Responsibility                                                                  |
|---------------------|---------------------------------------------------------------------------------|
| `:benchmark:model`  | Typed persisted schema (run documents, measurements, invocation manifests)      |
| `:benchmark`        | Measurement/harness (Kotlin main) + JMH microbenchmarks + Gradle task wiring    |
| `:benchmark:report` | Standalone reporting: typed adapters, JTE rendering, catalog, `compare` CLI     |
| build-logic         | `netbridge.benchmark-conventions` orchestrates native staging, tasks, reporting |

## Quick start

```bash
# Everything at once (JMH L0/L1/L3A + L2 transport + L3B channel integration + L4A minecraft-shaped)
./gradlew :benchmark:benchmarkAll   # or simply: ./gradlew benchmarkAll

# Pure-Java microbenchmarks (no native library required)
./gradlew :benchmark:jmhArena

# L0 raw FFM microbenchmarks (builds the benchmark-only Rust probe)
./gradlew :benchmark:jmhFfm

# L1 production Java<->native bridge microbenchmarks (builds release native)
./gradlew :benchmark:jmhBridge

# L3A NativeChannel microbenchmarks over a fake connection (no native)
./gradlew :benchmark:jmhChannel

# The whole JMH suite
./gradlew :benchmark:jmh
# Fast look (1 fork, short iterations)
./gradlew :benchmark:jmhQuick

# L2 transport comparison (TCP/QUIC/KCP loopback on localhost)
./gradlew :benchmark:transportBenchmark
# L2 remote mode
./gradlew :benchmark:transportServer -PbenchmarkTransport=quic -PbenchmarkPort=5000
./gradlew :benchmark:transportClient -PbenchmarkTransport=quic -PbenchmarkHost=host -PbenchmarkPort=5000

# L3B NativeChannel -> FFM -> QUIC/KCP end-to-end
./gradlew :benchmark:channelIntegration

# L4A Minecraft-shaped workloads
./gradlew :benchmark:minecraftTraffic -PbenchmarkTransport=tcp -PbenchmarkWorkload=mixed_play

# Calculators and reports
./gradlew :benchmark:benchmarkReport                              # refresh top-level catalog
./gradlew :benchmark:compare -Pbefore=<before.json> -Pafter=<after.json>

# L4B render a real-Minecraft recorder session file
./gradlew :benchmark:renderMinecraftSession -Pinput=<path>/minecraft-<ts>.json
```

Reports describe the current run. They do not compare against historical runs, classify performance
as good/bad, or act as a benchmark gate.

After running a task, view the standalone HTML report at:

```text
benchmark/build/reports/benchmarks/<taskName>/index.html
```

The central catalog is at:

```text
benchmark/build/reports/benchmarks/index.html
```

## Gradle properties

| Property                              | Meaning                                                          |
|---------------------------------------|------------------------------------------------------------------|
| `-PbenchmarkTransport`                | `tcp\|quic\|kcp-balanced\|kcp-aggressive\|all` (comma separated) |
| `-PbenchmarkCase`                     | `connect\|rtt\|throughput\|bidirectional\|loaded-latency\|all`   |
| `-PbenchmarkHost` / `-PbenchmarkPort` | remote endpoint (client/server mode)                             |
| `-PbenchmarkWorkload`                 | throughput window ms (transport) or workload name (minecraft)    |
| `-PbenchmarkPayload`                  | RTT payload bytes                                                |
| `-PbenchmarkIterations`               | measured iterations                                              |

## Native libraries

Native-requiring tasks build their own copies under
`benchmark/build/native/{probe,runtime}/<os>-<arch>/`:

* `buildProbeNative` — benchmark-only Rust probe (`net-bridge-benchmark-native`).
* `buildRuntimeNative` — production `net-bridge-native` with `--release`.

The production cdylib is forwarded to forked JVMs as
`-Dnetbridge.native.path`; the probe library as `-Dnetbridge.probe.native.path`.

## Output

Raw results are written under `benchmark/build/results/` (`jmh/`, `transport/`,
`channel/`, `minecraft-shaped/`), each document self-describing (environment, git commit, JVM,
native SHA-256, configuration). JSON remains the machine-readable authoritative output.

Standalone HTML reports (current run only) are written under
`benchmark/build/reports/benchmarks/<taskName>/` and include a copy of the raw JSON (`raw.json`).
The top-level entry page is
`benchmark/build/reports/benchmarks/index.html`.

## Reporting architecture

Each benchmark task's JVM writes only the raw JSON plus an invocation manifest under
`benchmark/build/results/current/<taskName>.json`. A Gradle finalizer (`<task>Report`) then runs a
**separate JVM** on the `:benchmark:report` classpath to render the HTML — report/JTE/Clikt code
never sits on a measured runtime. Every report folder also carries `report-meta.json`; the top-level
catalog is built by scanning those metadata files (never by parsing HTML).

## Java in this module

:benchmark is Kotlin-first: its main and test sources are Kotlin. The only Java kept is the JMH
source set under `benchmark/src/jmh/java` (L0 FFM bindings/benchmarks, L1 bridge control/I/O
benchmarks, L3A NativeChannel benchmark). JMH benchmark bodies must remain Java — no KAPT/KSP
annotation processing is introduced just to force Kotlin there.
