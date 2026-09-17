# net-bridge Glossary

This document describes the current architecture after the Java 25 + FFM refactor. Historical
decisions are in `docs/adr/`
(0004/0006/0007 have been superseded by 0009/0010/0011); milestone history is preserved in the old
refactor-plan record.

## Architecture Overview

- **net-bridge**: the overall Minecraft mod. Module layering is `common` (pure Java
  domain/runtime/native abstractions/config with no Minecraft imports)
  → `minecraft` (shared Minecraft-aware layer, one source set compiled into both loaders) →
  `fabric` / `neoforge` (bootstrap and lifecycle glue only).
- **Rust workspace**: `net-bridge-core` (pure transport core: Tokio runtime, QUIC/KCP, connection
  registries, typed errors,
  `#![forbid(unsafe_code)]`) + `net-bridge-native` (C ABI v1 shell where unsafe and FFI are
  concentrated).
- **C ABI v1** (ADR-0009): the only bootstrap export is
  `netbridge_get_api(requested_major, minimum_minor, out_api)`, which returns an `NbApiV1` function
  table (`struct_size`/`feature_bits` reserved fields + 12 function pointers). Connections and
  servers are context-scoped `u64` IDs; `nb_status_t` is the unified error-code type.
- **NativeContext** (Rust): the instance-level runtime ownership root. Tokio runtime,
  connection/server registries, ID allocator, and `EventSink`
  are all context fields; there is no process-global registry.
- **Java FFM boundary**: exists only in `nativebridge.internal.ffm`. `FfmNativeLibrary`
  (shared Arena + `SymbolLookup.libraryLookup` + upcall stub) → `FfmNativeContext` (function-table
  downcalls) →
  `FfmNativeTransportBackend`, which implements the public `NativeTransportBackend` seam. Event
  upcalls are bound to a `NativeEventDispatcher` instance.
- **composition root**: `NetBridgeServices` holds one `NetBridgeRuntime` root (configuration
  services + backend + `ClientRuntime` + `ServerRuntime`). If native is unavailable, it degrades to
  `UnavailableNativeTransportBackend`; the runtime remains complete and TCP stays available.

## Transport Protocols

- **Plaintext QUIC (`quic-plaintext`)**: QUIC transport with TLS encryption disabled. Security is
  provided by Minecraft's own encrypted stream. The motivation is to avoid an additional
  cryptographic handshake.
- **KCP stack**, outermost to innermost: **FEC (RS) → KCP → smux**.
    - **FEC / RS code**: Reed-Solomon forward error correction protecting the outer UDP packets.
      Implemented in
      `net-bridge-core/src/transport/kcp/fec_stream.rs`.
    - **KCP**: reliable low-latency ARQ in stream mode (byte-stream pipe), implemented by kcp-rs
      with a built-in SYN handshake.
    - **smux**: multiplexed flow-control layer with sliding-window token flow control and FIN close
      semantics; one MC byte stream occupies one smux stream.
- **KCP profile**: two preset parameter profiles; custom values are not supported. Canonical
  configuration strings are `balance` / `aggressive`
  (Rust parsing also accepts legacy alias `balanced`):
    - **balance**: nodelay=0/interval=40/resend=0/nc=0, mtu=1300, wnd= (256,256), stream=true.
    - **aggressive**: nodelay=1/interval=10/resend=2/nc=1; all other parameters are the same.

## Data Plane (ADR-0009/0010/0013)

- **Shared SPSC rings**: every connection carries a Rust-owned TX ring (Java → transport) and RX
  ring (transport → Java) defined by `net-bridge-shared-io`. The region header is 192 bytes followed
  by a power-of-two data area (64KiB minimum, 128-byte aligned); the default capacity is 128KiB per
  direction. `net-bridge-core` stays `#![forbid(unsafe_code)]`; ring memory and the wait-state
  atomics are confined to `net-bridge-shared-io`.
- **Direct path**: with `netbridge.native.sharedIo=auto|on` on a backend that advertises the
  shared-ring feature, Java maps both regions (`FfmSharedConnectionIo`) and reads/writes them
  directly. A connection is single-writer/single-reader with Acquire/Release cursors and a
  `ACTIVE`/`PARKED`/`NOTIFIED` wait-state protocol, so steady traffic does not need a per-chunk FFM
  downcall.
- **Legacy path**: `connection_write`/`connection_read` are the compatibility shim and copy into/out
  of the same rings; they are retained for the whole ABI major 1 lifetime. Mixing legacy and direct
  access on one connection fails fast with `NB_INVALID_STATE`.
- **Backpressure**: partial progress is allowed; `NB_WOULD_BLOCK` is returned only when the ring has
  zero free space (write) or is empty (read).
- **Lifecycle**: Rust owns the ring memory. Java views are closed through `SharedIoLeaseGate` before
  the native connection is released, so Rust never frees memory a Java view can still reach.
- **Configuration**: `netbridge.native.sharedIo` (`auto`/`on`/`off`, default `auto`) plus
  `netbridge.native.sharedIo.txCapacity`/`.rxCapacity` (0 selects the native default; 64KiB–1MiB,
  power of two). `off` is the documented instant rollback to the legacy shim.
- **Event model**: Rust emits
  `CONNECTION_STATE(1)/DATA_AVAILABLE(2)/WRITABLE(3)/ACCEPTED(4)/SERVER_STATE(5)` through
  `EventSink::on_event`. `DATA_AVAILABLE` and `WRITABLE` are edge-triggered: they fire only when the
  wait-state `PARKED→NOTIFIED` transition succeeds, so idle connections generate no wakeups and the
  data plane has no polling, periodic timer, or busy-spin. Java has no polling tasks (channel
  polling, the 5ms accept thread, and `ADOPT_EXECUTOR` polling are removed).
- **Event-thread discipline**: upcalls run on Rust Tokio workers; Java callbacks only route/marshal
  work, never execute Minecraft business logic or block.
- **Connection-state ABI values**: CONNECTING=1 / CONNECTED=2 / CLOSED=3 / FAILED=4 (core internal
  value +1).

## Capability Discovery

- **`networks` capability**: the server injects a top-level `networks` object into server-list ping
  JSON, with one entry per transport:
  `{enable, host, port, protocol}`. Missing `enable` = false; missing/null `host` = follow the
  server address.
  `ServerTransportManager` publishes an immutable `NetworksAbility` snapshot after transactional
  startup.
- **protocol version string**: `net-bri-quic/1` and `net-bri-kcp/1`. The client compares against its
  supported set exactly; an unsupported protocol disables that transport locally. Protocol evolution
  is determined solely by the protocol string.

## Client Behavior

- **TransportMode**: three configuration values, `tcp` / `quic` / `kcp`, representing user intent.
  quic/kcp include TCP fallback; tcp has no fallback concept. Accelerated intent maps to a
  transport-domain object through `AcceleratedTransport.fromMode`.
- **ConnectionPlanner**: a pure decision object. Input is mode × advertised capability ×
  recent-success cache × native availability; output is an immutable `ConnectionPlan`: `TcpPlan` for
  direct TCP or `AcceleratedPlan(tcpAddress, NativeAttempt)`.
  `NativeAttempt` is `QuicAttempt(endpoint)` / `KcpAttempt(endpoint, profile)`. If the selected
  transport is not announced or unavailable, use TCP directly and do not try another accelerated
  transport.
- **ConnectionExecutor**: executes the plan using `NativeRetryPolicy` (at most two attempts with
  10s/20s watchdogs expressed as `Duration`), closes failed attempts, records successful endpoints,
  publishes state snapshots, and finally falls back to vanilla TCP through
  `ConnectionExecutorAdapter.openTcp`. Successful endpoints are remembered as
  `TransportTarget(AcceleratedTransport, address)`.
- **SuccessfulEndpointCache**: keyed by `EndpointKey(host,port)`. It remembers **successful**
  accelerated endpoints for a TTL (`Duration`, default 5 minutes, with injectable monotonic
  `nanoTime` ticker). Reconnecting with the same transport during the TTL skips announcement
  negotiation; changing transport invalidates immediately. The cache is bounded (default 256) and
  evicts the earliest-expiring entries; failures are not remembered.
- **ServerCapabilityCache**: instance-level LRU of 256 entries keyed by `EndpointKey`, caching
  `networks` capabilities parsed from ping responses by address.
- **Connection-state snapshot**: `ConnectionStateStore` publishes immutable `ConnectionSnapshot`
  values (CONNECTING/CONNECTED/FALLING_BACK/IDLE). ConnectScreen and the F3 line only read the
  snapshot. Static `ConnectStatus`/`ConnectionDisplay` classes are removed.
- **Handshake liveness criterion**: QUIC CONNECTED means the plaintext handshake and bidirectional
  data stream are ready. **KCP CONNECTED means the KCP transport handshake + FEC + smux session
  establishment + opened MC data stream are ready**. If no response arrives within the native
  `connect_timeout` of 8s, it fails directly. `ConnectionExecutor` provides the outer watchdog;
  timeout aborts the connection and counts as a failed attempt.
- **Connection copy / F3 line**: semantics remain as defined in ADR-0005; the data source is now the
  runtime snapshot.

## Server Behavior

- **ServerRuntime / ServerTransportManager**: session-scoped and AutoCloseable, with transactional
  startup:
  parse configuration snapshot → call `backend.startServer` per transport → query actual port →
  atomically publish announcement; failures reverse-close prior starts. One transport failing to
  bind does not prevent the other. If both fail, vanilla TCP continues. Server stop only stops
  accepting new connections; it does not kill already delivered connections.
- **ACCEPTED-driven adoption**: `ACCEPTED` is emitted only after the QUIC/KCP bidirectional data
  plane is fully established and registered. New connections are adopted into the MC pipeline by
  `NativeConnectionAdopter` on the adoption executor with a generation check; adoption failure
  closes the connection. There are no raw long handles and no 5ms accept polling thread.
- **Server `[quic]`/`[kcp]` sections**: `enable` (**quic defaults true, kcp defaults false**) /
  `bind` / `host` / `port`
  (-1 follows the MC port, **KCP uses MC port +1**; 0 is random; out-of-range or bind failure logs
  an error and disables that transport) /
  `max_connection` (default 256; new clients are silently dropped at the limit to prevent UDP
  reflection, with separate quic/kcp counts). Ping entries always advertise the **resolved concrete
  port**.

## Configuration

- **nightconfig**: TOML configuration library used in the MC ecosystem. Server:
  `config/net-bridge/server.toml`; client:
  `config/net-bridge/client.toml`.
- **In-game toggle button**: retained at the bottom of the multiplayer screen and synchronized
  bidirectionally with the config file (initial value read from disk; toggles write back
  immediately).
- **Client `mode`**: tcp (default) / quic / kcp; `[kcp] profile` = balance (default) / aggressive.
  System property `netbridge.transport` overrides the file using the same names; old values such as
  `quic-fallback` are deprecated.

## Packaging and Loading

- **Content-addressed cache**: packaged native resources are stored by sha256 at
  `~/.netbridge/native/<sha256>/<lib>` with atomic writes and cross-start reuse.
  `native/<platform>/manifest.json` ships in the jar with sha256/ABI/package version and is
  validated during extraction. Development may override explicitly with
  `-Dnetbridge.native.path=<absolute-path>`; production no longer falls back to
  `java.library.path` / `System.load`.
- **Build guards**: `verifyArchitecture` enforces layer purity, FFM allowlists, zero JNI,
  `@NullMarked` coverage, absence of duplicate source copies, and Rust-core purity;
  `verifyNativeSymbols` ensures the sole business export is `netbridge_get_api`;
  `generateNativeManifest` records sha256 + header ABI constants.
- **CI**: java-unit (no native) / rust-unit (fmt + clippy -D warnings + test) / abi-check (symbols +
  layout + FFM integration under
  `--illegal-native-access=deny`) / all-platform native matrix → package (Java 25) → release.
  Publish depends on every preceding job.

## Additional Java Domain Terms (After ADR-0012 Modernization)

- **accelerated transport**: `transport.AcceleratedTransport` enum `QUIC`/`KCP`, the single source
  of truth for both the transport domain (planner/capability table/success cache) and status JSON
  keys. Each item carries wire status `key()` (`"quic"`/`"kcp"`) and the application-layer
  `protocol()` version string. It is a semantic enum and stores no ABI integer.
- **transport mode**: `transport.TransportMode` with tcp/quic/kcp, representing user intent at the
  configuration layer.
  `parse/configValue` handles strings; conversion with `AcceleratedTransport` uses `fromMode`. TCP
  intent has no accelerated form.
- **native transport kind**: `nativebridge.NativeTransportKind` (QUIC/KCP), the type seen by native
  sessions. Mapping to C ABI numbers exists only inside the FFM codec (`FfmAbiCodec`); QUIC=1/KCP=2
  do not live in semantic enums.
- **status networks ability**: `ability.NetworksAbility`, an immutable
  `EnumMap<AcceleratedTransport, NetworksEntry>` snapshot. Its wire form is the top-level
  server-list ping `networks` object, encoded/decoded only by `ability.StatusNetworksCodec`
  using constrained Jackson Core streaming. `NetworksEntry` is a pure record (enabled/host/port);
  protocol no longer lives in the domain object.
- **FFM ABI codec**: `nativebridge.internal.ffm.FfmAbiCodec`, the sole Java mapping point for C ABI
  numbers (transport, connection state, event kind, server state, KCP profile, failure reason, event
  decoding, and socket address). Unknown values are rejected explicitly (fail-closed); unknown
  failure reason → GENERIC is the intentional exception. Companion `FfmCallGate` owns call lifecycle
  accounting, draining, and close synchronization.
- **interception/bypass scope**: `client.AccelerationInterceptionScope`, using two `ScopedValue`s
  for "accelerated connection in progress" and
  "vanilla direct-connect bypass". `ConnectionMixin` enters the interception scope before the real
  `Connection.connect`, while
  `MinecraftAdapter.openTcp` binds bypass at the vanilla fallback call site. Bindings unwind
  automatically with the call stack and nested calls do not leak, replacing the old
  `ThreadLocal<Boolean>` guards.
