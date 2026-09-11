# NetBridge benchmark suite

The benchmark suite is a **standalone analysis toolbox**, invoked on demand and never part of
`check` / `test` / `verifyArchitecture` / CI. It replaces the old hand-written`:common:ffmBenchmark`
(which is removed).

The suite is organised in layers, mirroring how a real Minecraft packet travels:

| Layer | What it measures                              | Entry point                     |
|-------|-----------------------------------------------|---------------------------------|
| L0    | raw Java↔native boundary (FFM)                | `:benchmark:jmhFfm`             |
| L1    | production Java↔native bridge (control + I/O) | `:benchmark:jmhBridge`          |
| L2    | transports end to end (TCP/QUIC/KCP)          | `:benchmark:transportBenchmark` |
| L3A   | NativeChannel micro over a fake connection    | `:benchmark:jmhChannel`         |
| L3B   | NativeChannel → FFM → QUIC/KCP end to end     | `:benchmark:channelIntegration` |
| L4A   | Minecraft-shaped traffic workloads            | `:benchmark:minecraftTraffic`   |
| L4B   | real-Minecraft session markers                | `-Dnetbridge.benchmark=true`    |

## Tooling architecture (Kotlin-first)

The benchmark tooling is Kotlin-first and never affects the shipped mods:

| Module              | Responsibility                                                            |
|---------------------|---------------------------------------------------------------------------|
| `:benchmark:model`  | Typed persisted schema shared by the harness and the reporting module     |
| `:benchmark`        | Measurement/harness + JMH microbenchmarks + Gradle task wiring            |
| `:benchmark:report` | Standalone reporting: adapters, JTE templates/CSS, catalog, `compare` CLI |

`:benchmark` depends only on `:common` and `:benchmark:model`; `:benchmark:report` depends on
`:benchmark:model`. Neither is reachable from `common`/`fabric`/`neoforge`/`minecraft` (and no
Kotlin/JTE/Clikt/Jackson-databind artifact is bundled into production). The harness is Kotlin (Clikt
CLI, Jackson-databind for atomic raw-JSON writes); the one deliberate Java carve-out is the JMH
source set under `benchmark/src/jmh/java` (L0 FFM bindings/benchmarks, L1 bridge control/I/O
benchmarks, L3A NativeChannel benchmark) because benchmark methods must stay Java and no KAPT/KSP
processing is added to force them into Kotlin.

## Isolation guarantees

* `check`, `test`, `verifyArchitecture` and `nativeIntegrationTest` do **not**
  depend on any benchmark task.
* The benchmark module depends only on `:common` (never the reverse).
* No benchmark task is wired into the normal build/CI lifecycle.

## Requirements

* JDK 25 (forked JVMs run with `--enable-native-access=ALL-UNNAMED` and
  `--illegal-native-access=deny`).
* Native tasks need a Rust toolchain (`cargo`/`rustc`) to build
  `net-bridge-benchmark-native` and `net-bridge-native` (`--release`).

## JMH microbenchmarks

```bash
./gradlew :benchmark:jmh            # all microbenchmarks (forks, JSON output)
./gradlew :benchmark:jmhQuick       # fast pass (1 fork, short iterations)
./gradlew :benchmark:jmhFfm         # L0 probe (benchmark-only Rust crate)
./gradlew :benchmark:jmhBridge      # L1 production bridge over QUIC loopback
./gradlew :benchmark:jmhChannel     # L3A over FakeNativeConnection
./gradlew :benchmark:jmhArena       # pure-Java FFM allocation baseline
```

JMH JSON: `benchmark/build/results/jmh/<task>.json`. HTML report:
`benchmark/build/reports/benchmarks/<task>/index.html`.

Notes on methodology (§27 of the implementation plan):

* No manual timing inside `@Benchmark`; state is built in `@Setup(Level.Trial)`; results are
  consumed via `Blackhole`.
* Buffers are created in setup and reused (allocation is measured separately by the arena
  benchmarks).
* Every microbenchmark forks its own JVM with native-access flags.
* The control-plane benchmark deliberately distinguishes a *real* downcall
  (`FfmNativeContext.connectionState`) from the Java-cached `state()` wrapper.

## Transport harness (L2)

No JMH; a single config drives local (same-process) and remote mode:

```bash
./gradlew :benchmark:transportBenchmark                # local default
./gradlew :benchmark:transportServer -PbenchmarkTransport=quic -PbenchmarkPort=5000
./gradlew :benchmark:transportClient -PbenchmarkTransport=quic \
         -PbenchmarkHost=10.0.0.5 -PbenchmarkPort=5000 -PbenchmarkCase=connect,rtt
```

Cases: `connect`, `rtt`, `throughput`, `bidirectional`, `loaded-latency`.

* connect: p50/p95/p99 + min/max over a warmup + measured window.
* rtt: request/response correlation by sequence number over 64 B … 4 KiB.
* throughput / bidirectional: app MiB/s with server-confirmed receipt, sequence/checksum integrity,
  process CPU and CPU s/GiB.
* loaded-latency: 64 KiB stream + a 64 B ping every 100 ms exposes bufferbloat (`idle` vs `loaded`
  RTT, p50/p99 deltas).

## NativeChannel (L3)

`NativeChannelBenchmark` measures the production channel over an in-memory
`FakeNativeConnection` (write direct/composite buffers, manual reads, WOULD_BLOCK → WRITABLE
recovery). `channelIntegration` runs the real pipeline → NativeChannel → FFM → QUIC/KCP on
localhost.

## Minecraft-shaped workloads (L4A)

`MinecraftWorkloadRunner` replays LOGIN / MOVEMENT / CHUNK_BURST / ENTITY_BURST / MIXED_PLAY
patterns per transport and reports completion time, MiB/s, small-message latency percentiles, CPU
and byte totals.

```bash
./gradlew :benchmark:minecraftTraffic -PbenchmarkTransport=tcp -PbenchmarkWorkload=mixed_play
```

## Real-Minecraft markers (L4B)

Run the game with `-Dnetbridge.benchmark=true`; a recorder in the shared Minecraft layer emits
`CONNECT_REQUESTED` / `TRANSPORT_CONNECTED` milestones (and chunk milestones when hooked up) into
`benchmark-results/minecraft-<ts>.json`. When the flag is off the recorder is a single boolean
test — no per-packet timing. The recorder stays plain Java (production layer) and uses Jackson-core
streaming only; it writes no HTML. Render the session report externally with:

```bash
./gradlew :benchmark:renderMinecraftSession -Pinput=<path>/minecraft-<ts>.json
```

which writes `benchmark/build/reports/benchmarks/renderMinecraftSession/index.html` through the same
`:benchmark:report` pipeline used by the other suites.

## Output schema

Every document is:

```json
{
  "suite": "...",
  "environment": {
    ...
    self-describing
    ...
  },
  "configuration": {
    ...
  },
  "results": [
    ...
  ]
}
```

`environment` always includes timestamp, git commit + dirty flag, OS/arch/cores, JDK/JVM, max heap,
rustc, and native library SHA-256 when a native library was loaded. Latency results use `meanNanos`/
`p50Nanos`…`p999Nanos`; throughput results use `payloadBytesTransferred`, `durationNanos`,
`mibPerSecond`,
`processCpuNanos`. No credentials or player data are ever recorded.

## HTML reports

Every benchmark task automatically produces a standalone, human-readable HTML report for that single
run:

```text
benchmark/build/reports/benchmarks/<taskName>/index.html
```

Report generation is deliberately out-of-band: the benchmark JVM writes only raw JSON plus an
invocation manifest (`benchmark/build/results/current/<taskName>.json`), and a Gradle finalizer
(`<task>Report`) renders the HTML in a separate JVM on the `:benchmark:report` classpath, so no
JTE/Clikt/report code ever runs on a measured runtime. Raw JSON is the authoritative,
machine-readable output and every rendered folder re-embeds it.

Report directories also contain `raw.json`, `report-meta.json` (typed metadata), and
`jmh-output.txt` for JMH tasks, so each report folder is a complete downloadable artifact. The
central catalog is:

```text
benchmark/build/reports/benchmarks/index.html
```

The catalog is refreshed by scanning each `report-meta.json` (never by parsing HTML).

Reports describe the current run only. They do not compare against historical runs, classify
performance as good/bad, or act as a benchmark gate.

## Utilities

```bash
./gradlew :benchmark:benchmarkAll        # run every layer in one invocation + refresh catalog
./gradlew benchmarkAll                   # same, via the root-project convenience alias
./gradlew :benchmark:benchmarkReport     # refresh the top-level HTML catalog
./gradlew :benchmark:compare -Pbefore=a.json -Pafter=b.json   # calculator only
benchmark/scripts/netem.sh               # optional WAN emulation (see below)
```

`netem.sh` creates an isolated network namespace with `veth` + `tc netem`. Profiles: `wan` (60 ms
RTT / 0.5% loss / 5 ms jitter) and `bad-wan`
(120 ms / 2% / 10 ms); customise with `--rtt --loss --jitter`. Idempotent;
`--cleanup` removes the namespace.
