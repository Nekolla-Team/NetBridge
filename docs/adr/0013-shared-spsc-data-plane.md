# ADR-0013: Rust-Owned Shared SPSC Ring Data Plane (ABI v1.1 Direct Mode)

Status: Accepted · Date: 2026-09-17 · Supersedes: ADR-0009 Decision 7 (partial) and ADR-0010
Decisions 3 and 4 (partial)

## Context

Until this change the connection data plane was a pair of bounded `mpsc` queues holding
`Bytes`: Java pushed a chunk through a `connection_write` downcall, Rust copied it into the outbound
queue, and transport tasks drained the queue; the read side mirrored this with an inbound queue
drained by `connection_read`. Every chunk therefore crossed the FFI boundary in both directions, the
hard 4 MiB logical byte budget was decoupled from the real socket pacing, and
`DATA_AVAILABLE`/`WRITABLE` were emitted per enqueue/recovery and coalesced on Java.

The transport core has since been migrated so QUIC and KCP move bytes through Rust-owned
single-producer/single-consumer (SPSC) rings, and ABI v1.1 exposes those rings to Java so steady
traffic does not need a per-chunk downcall.

## Decision

1. **Ring primitive is its own crate.** `net-bridge-shared-io` owns the SPSC ring: the 192-byte
   region header, power-of-two data area (64 KiB minimum, 128-byte aligned), Acquire/Release
   cursors, and the `ACTIVE/PARKED/NOTIFIED` wait-state protocol. All `unsafe` lives there;
   `net-bridge-core` stays `#![forbid(unsafe_code)]`. A `loom` feature models the interleavings, and
   the PARKED→recheck sequence with SeqCst store/load fences is required to prevent lost wakeups.

2. **Both transports use rings.** Each connection owns a TX ring (Java → transport) and an RX ring
   (transport → Java). QUIC and KCP supervisors drive cursors directly; the `Bytes` data-plane
   `mpsc` channels, byte budgets, and `write_blocked`/`read_waker` machinery are gone.

3. **ABI v1.1 exposes the regions.** `netbridge_get_api` advertises
   `NB_FEATURE_SHARED_RING_IO`, `connection_io_region` (descriptor `nb_shared_io_region_v1_t`,
   layout version `1.0`), and `connection_io_kick`. Capacity requests travel through
   `NbContextOptionsV1` without changing the struct size, so an old native library never sees
   non-zero reserved fields.

4. **Java direct mode is stable, with explicit modes.** `netbridge.native.sharedIo` selects
   `auto` (default: direct when the backend advertises the feature, otherwise legacy), `on` (fail
   fast when unsupported), or `off` (legacy shim; the instant rollback switch). Capacities come from
   `netbridge.native.sharedIo.txCapacity`/`.rxCapacity` (0 selects the 128 KiB native default).

5. **Legacy shim is retained for ABI major 1.** `connection_write`/`connection_read` remain and copy
   into/out of the same rings. They are not removed during the ABI major 1 lifetime. Per connection,
   legacy and direct access are mutually exclusive; mixing fails fast with
   `NB_INVALID_STATE`.

6. **Write ownership and lifecycle.** Rust owns ring memory. Java maps it with `Arena.ofShared`
   and guards access with `SharedIoLeaseGate`, which closes the views and drains in-flight
   operations before the native connection is released. Every connection is single-writer and
   single-reader.

7. **Edge-triggered events.** `DATA_AVAILABLE` and `WRITABLE` are emitted only when the
   `PARKED→NOTIFIED` CAS succeeds. Idle connections generate no wakeups; there is no polling,
   periodic timer, or busy-spin in the data plane.

## Superseded Clauses

- **ADR-0009 Decision 7 (partial).** Old: "cross-language zero-copy is not a goal; every I/O passes
  through one downcall and one boundary copy." New: the data plane uses Rust-owned shared memory;
  the Netty boundary may still copy once, but steady-state traffic does not require a per-chunk FFM
  downcall.
- **ADR-0010 Decision 3 (partial).** Old: "Rust emits `DATA_AVAILABLE` after each successful read
  enqueue; Java debounces." New: `DATA_AVAILABLE` fires from the shared wait-state empty→sleep edge.
- **ADR-0010 Decision 4 (partial).** Old: `connection_write` full is all-or-nothing. New: the shared
  ring and the compatibility shim allow partial progress; `NB_WOULD_BLOCK` is returned only when
  zero free space remains.

## Consequences

- Quick-profile benchmarks show direct mode is substantially cheaper than legacy for both writes and
  reads, and the 64–512 KiB capacity range is roughly flat; see `:benchmark:jmhSharedIo`. AUTO
  remains the default and the initial capacity stays 128 KiB.
- Rollback is a single property (`-Dnetbridge.native.sharedIo=off`) and is exercised by the native
  integration tests; a full native rollback is possible because the shared-ring data plane was
  introduced as an isolated change boundary.
- Language drift between the Rust layout and the Java `MemoryLayout` is covered by cbindgen
  verification plus layout/roundtrip tests, and the lost-wakeup protocol has a deterministic
  loom/model test alongside the close-vs-IO stress test.
