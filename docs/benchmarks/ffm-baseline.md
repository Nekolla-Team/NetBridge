# FFM Data-Plane Benchmark Baseline

This repeatable benchmark was established as required by plan §24. No separate JNI-era benchmark
baseline was archived before cutover (Phase 0 froze the test baseline), so this is the **first
quantitative record** of the FFM data plane. Performance acceptance is not defined as
"X% faster than JNI"; it follows plan §24.5 instead:

- no fixed polling wakeups, so idle CPU does not regress because of the event model;
- no Java heap `byte[]` intermediate on the write path;
- no unexplained material throughput regression;
- no material p99 degradation under callback storms;
- bounded memory growth, with repeated backend/context lifecycle tests asserting that registries
  return to zero.

## How to Run

```bash
./gradlew :common:ffmBenchmark                    # default 5-second throughput window
./gradlew :common:ffmBenchmark --args="<lib> 10"  # specify cdylib and duration in seconds
```

The harness is in `common/src/benchmark/java/.../FfmBenchmark.java` and is not included in the mod
jar. Cases include an empty downcall (`connection_state`), 1KiB/64KiB write+read round trips, QUIC
loopback throughput, and per-chunk latency percentiles.

## Baseline Sample

Environment: WSL2 (linux-x86_64), debug cdylib, Java 25, 2026-09-04.

```text
empty downcall (connection_state)  ops=20000  avg=    0.03 us/op
write+read 1KiB                   ops=20000  avg=  379.17 us/op
write+read 1KiB                   p50=  300.98 us  p95=  297.90 us  p99=  267.51 us  (n=20000)
write+read 64KiB                  ops=2500  avg= 1606.95 us/op
write+read 64KiB                  p50= 1434.39 us  p95= 1972.19 us  p99= 1286.71 us  (n=2500)
QUIC loopback throughput          throughput       60.3 MiB/s  (301 MiB sent in 5.00s)
QUIC loopback throughput          p50=   40.59 us  p95=  610.63 us  p99= 1971.24 us  (n=27939)
```

When rerunning, update this file and record the environment (OS/arch, profile, date). Values are for
regression comparison only and are not absolute guarantees. If throughput or latency regresses by
more than roughly 5–10% without an explanation, profile it as required by the plan and record the
cause.
