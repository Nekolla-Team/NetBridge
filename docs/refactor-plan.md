# net-bridge Refactor Task Breakdown (Historical Record)

> This file documents the old JNI-era refactor milestones. It has been **superseded by
`NETBRIDGE_JAVA25_FFM_FULL_REFACTOR_PLAN.md`
> (the complete Java 25 + FFM refactor)** and is retained only for historical reference. For the
> current architecture, see `docs/glossary.md` and
> `docs/adr/0009~0011`.
>
> A later 2026-09 round of **Java domain-model modernization** (JSON boundaries, typed transport
> domain, sealed plans/requests/events,
> FFM ABI numeric isolation, ScopedValue/Duration/monotonic clocks, controlled configuration/errors,
> and `FfmCallGate` extraction) is described in
> `NETBRIDGE_JAVA_MODERNIZATION_IMPLEMENTATION_PLAN.md`, with the decision recorded in
> `docs/adr/0012`. The controlled
> `DelegatingChannelFuture` POC concluded **REJECT (keep the project-owned implementation)**.

Basis: docs/adr/0001~0007 · docs/glossary.md · docs/conventions.md. The order is the dependency
order; each milestone has acceptance criteria and must be fully green before moving on.

## M1 Rust Transport Abstraction (ADR-0007)

| Task               | Content                                                                                                                                                                      |
|--------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `src/transport.rs` | `trait Transport { connect / accept / state / read / write / close }`; error types carry a Transport tag.                                                                    |
| `bridge/quic/`     | Move current `bridge/{client,server,connection}.rs` here and implement the trait.                                                                                            |
| `bridge/kcp/`      | Attach the current KCP module to the same trait (leave fec_stream/frame unchanged); **set CONNECTED when the first valid data frame enters the `to_java` queue** (ADR-0008). |
| Registry fix       | Replace global `ACTIVE_SERVER_CONNS` with **independent counters per server instance** (consequence of ADR-0003).                                                            |
| Facade             | Dispatch `bridge::start_server/connect/...` through `dyn Transport`.                                                                                                         |

Acceptance: `cargo test` is green; quic and kcp can each complete connect/read-write/close round
trips through the facade.

## M2 Rename JNI Surface + ABI 0.2.0 (ADR-0004/0007)

| Task            | Content                                                                                                      |
|-----------------|--------------------------------------------------------------------------------------------------------------|
| Java            | `jni/QuicNative` → `jni/NativeBridge`; `QuicConnectionState` → `NativeConnState`.                            |
| Rust            | Rename exports to `Java_top_tangge233_netbridge_jni_NativeBridge_*`; set `NET_BRIDGE_ABI_VERSION = "0.2.0"`. |
| Load validation | `NativeLoader` rejects version mismatches and logs a clear error.                                            |

Acceptance: both sides compile; smoke-test round trip succeeds; ABI-mismatch behavior has test
coverage.

## M3 Capability Discovery Wire v2 (ADR-0001)

| Task               | Content                                                                                                                                                                                                 |
|--------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ability/` package | `NetworksEntry(enable/host/port/protocol)`, `NetworksAbility` keyed by transport name, `StatusNetworksCodec` injection/parsing, and exact supported-set matching through `TransportProtocol` constants. |
| Remove             | All `features` logic, `supportsQuicRaw()`, and the `net-bridge/1` constant.                                                                                                                             |
| Injection side     | Both loader ping mixins emit v2 JSON with quic+kcp entries. **The wire always contains the resolved concrete port**; -1/0 never go on the wire (ADR-0001/0003).                                         |

Acceptance: parse/injection unit tests cover missing enable → default false, host null → follow
server, unknown protocol → locally disabled.

## M4 Client Transport State Machine (ADR-0002/0005/0006)

| Task                 | Content                                                                                                                                                                                                                                                                                                                                            |
|----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `transport/` package | `TransportMode(tcp/quic/kcp)`, `ClientConfig` client.toml read/write, and `KcpProfile` parsing with canonical `balance`/`aggressive` plus legacy alias `balanced`.                                                                                                                                                                                 |
| `TransportSelector`  | mode × capability cache → target transport; unannounced/unsupported protocol → direct TCP.                                                                                                                                                                                                                                                         |
| `HandshakeWatchdog`  | Scheduled task races the connect promise: first 10s, subsequent 20s; each failure counts once and two failures trigger fallback. Timeout actively calls closeConnection to clean the native handle (ADR-0008: neither stack has a usable native handshake timeout for the required behavior, so the watchdog is the only viable general solution). |
| `FallbackTracker`    | Remember fallback by server address with TTL 5min.                                                                                                                                                                                                                                                                                                 |
| `NativeChannel`      | Generalize former QuicChannel; change polling to adaptive stepped 5→10→20→40ms backoff (ADR-0006).                                                                                                                                                                                                                                                 |
| Cleanup              | Remove QUIC_ONLY mode; client transport classes support KCP targets.                                                                                                                                                                                                                                                                               |

Acceptance: state-machine tests cover timeout ×2 fallback / TTL-expiry retry / unannounced direct
TCP; a simulated black hole terminates the first handshake within 10s.

## M5 Server Configuration and Acceptor (ADR-0003)

| Task                  | Content                                                                                                                                                                                                                                      |
|-----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `server/ServerConfig` | Full `[quic]`/`[kcp]` fields: enable/bind/host/port/max_connection, read/write with nightconfig.                                                                                                                                             |
| Port semantics        | -1 follows the MC port (**KCP uses +1**), 0 is random with actual port logged after startup, out-of-range disables that transport with an error; **bind failure follows the same disable path**; ping advertises the resolved concrete port. |
| `NativeAcceptor`      | Independent max_connection per server; silently drop excess clients with no response; reuse the existing acceptConnections drain pattern for adoption.                                                                                       |

Acceptance: config-boundary unit tests cover -1/0/65535/65536/null bind/host; excess connections
appear to the peer as timeouts.

## M6 GUI / F3 / i18n (ADR-0005)

| Task            | Content                                                                                                               |
|-----------------|-----------------------------------------------------------------------------------------------------------------------|
| ConnectScreen   | Add a separate state-machine status line: `Connecting via QUIC/KCP/TCP…` → `Falling back to TCP…`.                    |
| F3              | Single line `[net-bridge] <PROTOCOL> <addr>`; direct TCP is shown as well.                                            |
| Settings button | Keep the multiplayer-screen entry, synchronize bidirectionally with client.toml, and explain fallback in the tooltip. |
| i18n            | zh_cn + en_us (`Connecting via QUIC…`, `Falling back to TCP…`, etc.); keys are defined in ADR-0005.                   |

Acceptance: manually verify three scenarios — QUIC success / fallback after two failures / direct
TCP — and confirm copy and F3 update correctly.

## M7 Final Cleanup

| Task                        | Content                                                                                                                                                             |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Dual-loader synchronization | Check fabric/neoforge copies file by file for consistency.                                                                                                          |
| Comment conventions         | Rewrite comments in touched files according to conventions.md: function-level documentation, remove redundant body comments, and **remove all ADR-number indexes**. |
| Dead code                   | Remove old wire parsing, QUIC_ONLY, and features-related tests.                                                                                                     |
| README                      | Update configuration examples and protocol description to v2.                                                                                                       |

Acceptance: grep finds no `ADR-` in src; loader diffs contain only package-name differences; the
full test suite is green.

## Risk Notes

- ~~quinn_plaintext internal idle-timeout default had not been audited~~ audited in ADR-0008: quinn
  never terminates a black-hole handshake on its own, while KCP historically had no handshake
  concept; watchdog + KCP first-byte liveness was the decided interim approach at this milestone,
  with no unresolved risk.
- KCP `connect()` currently has no asynchronous handshake-task wrapper; implement it following the
  QUIC client.rs pattern as a key M1 item.
- Drift between dual-loader copies has caused historical incidents, so M7 requires file-by-file diff
  verification.
