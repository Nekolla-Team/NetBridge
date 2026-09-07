# ADR-0012: Java Domain Modernization (JSON Boundaries, Transport Domain, FFM ABI Isolation, Concurrency, and Time Primitives)

Status: Accepted · Date: 2026-09-07

## Context

After ADR-0009/0010/0011, the Java side still retained several historical patterns: server-status
`networks` JSON was assembled and parsed freely with Gson `JsonObject`; string transport keys
(`"quic"`/`"kcp"`) were scattered across layers; semantic enums stored C ABI integers internally;
native events were passed as raw
`(int, long, long, long)` tuples and decoded with hand-written switches in multiple places; boolean
`ThreadLocal` guards and
`System.currentTimeMillis` clocks remained; `DelegatingChannelFuture` was roughly 470 lines of
handwritten code; and configuration loading could fall back an entire file to defaults when one read
failed.

This modernization keeps behavior, wire format (including status JSON property names and
native-manifest camelCase field spelling), and ABI numeric values unchanged. It does not add Jackson
Databind/ObjectMapper, dependency-injection frameworks, or caching frameworks. It uses only stable
Java 25 APIs (final ScopedValue, HexFormat, `Thread.ofPlatform`, records/sealed types/pattern
switch) and no preview features.

## Decision

### JSON: Jackson Core 3 Streaming Parsing at Codec Boundaries Only

- All JSON read/write operations in `common` use hand-written constrained codecs based on
  `tools.jackson.core` 3.1.6 streaming APIs:
  `StatusNetworksCodec` for the advertised `networks` capability and `NativeManifestCodec` for the
  native manifest. Each owns a `JsonFactory` configured with `StreamReadConstraints` that limit
  nesting, document size, token count, name length, string length, and numeric length. Malformed
  remote or packaged input degrades to "empty capability" or "invalid manifest" and is protected
  against depth/token explosions.
- **Do not introduce Jackson Databind / ObjectMapper**. Domain objects are project-owned records
  decoupled from JSON shape. Reflective binding would encourage JSON types to leak beyond the
  boundary, so architecture guards forbid it as well.
- **Gson remains only at Minecraft/DFU boundaries**: Mojang's status-packet codec (`JsonParser`/
  `JsonElement`) and mixin capture layer continue using Minecraft's bundled Gson. Production code in
  `common` has zero Gson imports, enforced by a guard. Gson is removed from the project's dependency
  catalog.
- JSON boundary guard: `tools.jackson` imports are allowed only in `ability/StatusNetworksCodec` and
  `internal/ffm/NativeManifestCodec`; domain/public APIs expose no Jackson or Gson types.

### Transport Domain: `AcceleratedTransport` as the Single Source of Truth + Typed Capability Table

- Add `transport.AcceleratedTransport { QUIC("quic","net-bri-quic/1"), KCP(...) }`. The same enum
  owns both the status-wire key and application protocol string, replacing the scattered
  `TransportProtocol` constant class and
  `"quic"`/`"kcp"` literals. The JSON boundary uses `fromKey` to discard unknown keys; planner/cache
  logic uses `fromMode`.
- Reduce `NetworksEntry` to a pure record (`enabled/host/port`) whose compact constructor enforces
  port 1..65535 and normalizes blank host to null.
  `NetworksAbility` stores an immutable `EnumMap<AcceleratedTransport, NetworksEntry>` and exposes
  enum-keyed
  `entry/usable/hasUsableAccelerated`. Wire property names become private codec constants.
- This eliminates the invalid state where TCP could enter an accelerated target/capability table
  (the old `TransportTarget(mode,…)` could represent TCP).

### Plans, Requests, and Events: Sealed Types + Explicit Invariants

- `ConnectionPlan` is sealed (`TcpPlan` / `AcceleratedPlan(tcpAddress, NativeAttempt)`), and
  `NativeAttempt` is sealed (`QuicAttempt(endpoint)` / `KcpAttempt(endpoint, profile)`). The planner
  constructs branches through `instanceof`, eliminating missed `if (mode==TCP)` cases.
- `NativeConnectRequest` / `NativeServerRequest` are sealed with Quic/Kcp variants. Compact
  constructors validate nonblank host, port ranges (server allows 0 for ephemeral),
  `maxConnections>=1`, and non-null Kcp profile. The server-side -1 "follow MC" port meaning exists
  only in config/parsing.
- Native events become sealed `NativeEvent` variants: `ConnectionStateChanged`, `DataAvailable`,
  `Writable`, `Accepted`, and `ServerStateChanged`.
  `FfmNativeTransportBackend.onEvent` routes them with pattern switch. ACCEPTED follows what Rust
  actually emits: object_id=server, arg0=connection.

### FFM ABI Numeric Isolation

- Semantic enums (`NativeTransportKind`, `NativeConnectionState`, `NativeFailureReason`,
  `NativeKcpProfile`) **store no ABI integers**.
- The sole home of ABI numeric values is `internal/ffm/FfmAbiCodec` (integer constants plus
  `*ToAbi`/`*FromAbi`/
  `decodeEvent`/`decodeSocketAddress`) together with `FfmApiLayouts`/`FfmStatus`. Unknown
  state/family/event-kind values are explicitly rejected (fail-closed); unknown failure reason →
  GENERIC is the intentional exception.
- Fallback/event decoding happens exactly once at the `FfmNativeEventDispatcher` upcall entry
  through the codec. No `NativeEvent` may be constructed outside the codec.

### Concurrency: ScopedValue Replaces Boolean ThreadLocal

- `AccelerationInterceptionScope` in common/client uses two `ScopedValue`s to represent "vanilla
  direct-connect bypass" and "accelerated connection in progress". Semantics match the old
  `ConnectionMixin`/`NativeClientTransport` `ThreadLocal<Boolean>` pair, but bindings occur at real
  call sites, unwind automatically with stack nesting, cannot leak, and eliminate manual mixin
  try/finally blocks.
- The `StatusNetworksCapture` ThreadLocal capture slot remains because it is a temporary scratchpad
  on the client decode thread and is the sole allowlisted architecture-guard exception.
- Guard: production code may not use static `ThreadLocal` or `ExecutorService`, except for
  StatusNetworksCapture.

### Time: `Duration` + Monotonic Clocks

- Public/timeout Java APIs use `java.time.Duration` (`NativeRetryPolicy` first/subsequent timeout
  and backoff, plus the `SuccessfulEndpointCache` TTL).
- Pure elapsed-time measurement is monotonic: cache TTL uses an injectable `LongSupplier` (default
  `System::nanoTime`), and the
  `FfmNativeContext` drain deadline changes from wall clock to `System.nanoTime`. Wall clock is for
  presentation only.
- `EndpointKey(host, port)` replaces string keys like `"host:port"`; identity is textual/exact, with
  no case folding and no DNS lookup.

### Configuration and Errors

- `NetBridgeProperties` centrally parses four system properties
  (transport/quicPort/native.path/cache.dir), and all consumers receive values through parameters.
  Production code no longer reads `System.getProperty` ad hoc.
- NightConfig extraction is typed per field: a missing/wrong-type/out-of-range field falls back only
  that field with a warning, never the entire file (the old implementation could reset all
  configuration after one `ClassCastException`).
- Client configuration writes use atomic saving (temporary file + `FileChannel.force` +
  `ATOMIC_MOVE` + fallback).
- Native resource errors converge on a `NativeResourceError` enum plus fully typed constructors.
  Aggregate close failures use ordinary
  `RuntimeException` + `addSuppressed` rather than pretending to be resource errors, and logging
  consistently includes the throwable.

### Complexity: `FfmCallGate`

- Extract `FfmNativeContext` call lifecycle (OPEN check, active-operation accounting, close-time
  drain, state synchronization) into `FfmCallGate`. High-priority context operations use
  `gate.call/execute`, and shutdown uses `gate.close(timeout, teardown)`. The context no longer owns
  a custom lifecycle state machine beyond ABI-level numeric concerns.
- The proposed `NativeChannelIo` extraction is rejected: read/write paths are deeply coupled to
  Netty lifecycle state (outbound buffer, writability/backpressure, pipeline events, EventLoop
  scheduling). Splitting them would reduce line count only superficially while increasing semantic
  risk, which §8.2 explicitly permits as grounds to skip the extraction.

### `DelegatingChannelFuture`: Controlled POC — **REJECT**

- Goal: replace roughly 470 lines of custom Future implementation with Netty Promise/ChannelPromise
  plus explicit orchestration.
- Result: **reject replacement and keep `DelegatingChannelFuture`**; §8.3 explicitly treats a
  justified rejection as successful completion. Evidence:
    1. Minecraft-side contract from decompiled 1.21.1 bytecode: `ConnectScreen$1` only calls
       `syncUninterruptibly()` on the return value, and `ConnectScreen` only calls `cancel(true)`;
       there are no `channel()` or listener calls. However, the returned object must still
       **implement `ChannelFuture`** because dispatch uses invokeinterface.
    2. Netty's only ready-made `ChannelFuture` implementation, `DefaultChannelPromise`, has a
       `private final channel` field. It cannot represent "no channel at creation, channel changes
       across retries, winner becomes the final channel". The external future must exist before the
       first attempt, so a project-owned type implementing `ChannelFuture` remains structurally
       necessary (criterion 7 cannot be bypassed).
    3. `ImmediateEventExecutor.inEventLoop()` is always true, so `DefaultPromise.checkDeadLock()`
       would throw
       `BlockingOperationException` on the Minecraft `syncUninterruptibly()` path when waiting for
       an unfinished operation.
    4. With a real `EventExecutor`, completion from another thread notifies listeners
       asynchronously, breaking the deterministic ordering relied on by
       `DelegatingChannelFutureTest` and ClientRuntime close/cancel race tests (criteria 1/4).

    - Therefore criteria 1/2/4/6/7 cannot all be satisfied with bounded effort. Keep the current
      implementation rather than forcing "modernization" onto this concurrency core.

## Consequences

- Production `common` code no longer depends on Gson. JSON flows only through two constrained
  streaming codecs, and domain APIs stay type-pure.
- Transport keys, capabilities, requests, and events are typed and exhaustive (sealed); C ABI
  numbers exist only inside FFM codec/layout code, and unknown input fails closed.
- Interception/bypass state changes from hand-written ThreadLocal + try/finally to standard
  ScopedValue binding; elapsed-time logic becomes monotonic; configuration failures shrink in blast
  radius from entire files to individual fields.
- `DelegatingChannelFuture` remains project-owned with its test suite. A future replacement must
  first solve the structural "pre-channel multi-attempt ChannelFuture" requirement.
- Static quality: Error Prone 2.50.0 (UnusedMethod/UnusedVariable re-enabled, with class-level
  suppression on mixins) + clean NullAway;
  `verifyArchitecture` (JSON/FFM/static/layer-purity guards) and `verifyNativeHeader` are final
  regression gates.
