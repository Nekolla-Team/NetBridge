# ADR-0009: Java 25 / FFM / C ABI v1 Native Interop

Status: Accepted · Date: 2026-09-02 · Supersedes: ADR-0004 (JNI data-plane boundary strategy)

## Context

The old architecture bridged Java and Rust through JNI: `byte[]` copies across the boundary, a JNI
direct-buffer read special case, Tokio threads attaching to the JVM and calling back into a static
Java registry, ABI versions matched only by exact version strings, and Java-side polling for
connection state. These mechanisms spread native lifecycle, error semantics, and thread-boundary
rules across multiple modules and prevented ownership from converging into a coherent object model.

Java 25 makes the Foreign Function & Memory API (FFM) a final API. Because the ABI must be rebuilt
for this transition anyway, the project uses this window to refactor Java, Rust, the native ABI,
state ownership, and callbacks into their intended final form, removing JNI completely.

## Decision

1. **Java 25 + FFM is the only primary path**. There is no JNI fallback, no Java 21 artifact, and no
   multi-release jar. Mod metadata declares `java >= 25`, with a clear bootstrap guard retained.

2. **FFM is a leaf dependency**. `java.lang.foreign` is allowed only in
   `top.tangge233.netbridge.nativebridge.internal.ffm`; it must not appear in channel / client /
   server / config / minecraft / fabric / neoforge layers. Upper layers see only typed abstractions
   such as
   `NativeTransportBackend` / `NativeConnection` / `NativeServer`.

3. Use a **pure C ABI (`extern "C"` + `#[repr(C)]`)**. Do not pass Rust `bool`/`enum`/`String`/
   `Vec`/references/slices/trait objects/`Result` across the ABI. Use fixed-width integers
   (`uint8_t/uint16_t/uint32_t/uint64_t/int32_t`) and no C `long`.

4. Provide a **single bootstrap export: `netbridge_get_api(requested_major, minimum_minor, &api)`**.
   It returns a read-only process-lifetime `nb_api_v1_t` function table. ABI discovery and runtime
   creation are separate steps. The version model is ABI major / minor + function-table
   `struct_size` + `feature_bits`, not exact-match version strings. V1 is fixed at
   `major=1, minor=0`.

5. **`NativeContext` exists starting with ABI v1**: the context is an opaque pointer
   (`nb_context_t*`), while connections and servers are context-scoped monotonic `uint64_t` IDs that
   are never reused; overflow is fatal. Every resource operation belongs explicitly to a context.
   Java normally creates one context, but the ABI does not depend on a singleton. Rust
   `net-bridge-core::NativeContext` owns the Tokio runtime, connection/server registries, ID
   allocator, and EventSink; there is no process-global registry.

6. Use a **unified error model**: raw functions return `nb_status_t` (`NB_OK`/`NB_WOULD_BLOCK` plus
   defined positive/negative error codes), while data is returned through typed out-parameters.
   State lookup is separate from function errors: an unknown ID returns `NB_NOT_FOUND`, not
   `UNKNOWN=-1`. Sentinel semantics live only in ABI numeric definitions and never leak into Java
   domain APIs.

7. **Data-plane memory boundary**: cross-language zero-copy is not a goal. On writes, a borrowed
   Java `MemorySegment` is valid only for the current downcall, and Rust takes ownership of the
   bytes before returning. On reads, Rust copies queued `Bytes` directly into caller-provided direct
   memory in one pass. This gives one boundary copy, no intermediate arrays, and an explicit borrow
   lifetime. Each I/O call is capped at 64 KiB (`MAX_IO_CHUNK`).

8. **Callbacks are fully event-driven, instance-bound, and mandatory**: Rust
   `NativeContext::EventSink` → C function pointer → FFM upcall stub → a specific bound
   `NativeEventDispatcher` instance (`MethodHandle.bindTo` + upcall). No static Java callback
   registry, GlobalRef, or method ID is required. Upcalls carry only fixed-width primitives
   (`event_kind, object_id, arg0, arg1`). Events arrive on Rust Tokio worker threads; Java may only
   decode the primitive event, locate the instance listener, marshal via `eventLoop.execute(...)` or
   enqueue work, and return immediately. Blocking, waiting, or firing the Netty pipeline directly
   from the callback is forbidden. There is no polling fallback: if the upcall stub/callback cannot
   be established, the native backend is considered unavailable.

9. **Critical native lifecycles use explicit `AutoCloseable` + `Arena.ofShared()`**, never
   `Arena.ofAuto()`/finalization as the correctness path.
   `FfmNativeLibrary` owns the shared Arena, SymbolLookup, upcall stub, and NativeContext lifecycle.
   Shutdown order is:
   stop accepting new upper-layer requests → close servers/connections → `context_shutdown` →
   `context_destroy` → confirm no further upcalls → close shared Arena.

10. **Panics/exceptions never cross the boundary**. Every Rust C ABI entrypoint is wrapped with
    `catch_unwind`; panics map to `NB_PANIC` and are logged. The Java upcall target catches
    `Throwable` internally and never throws back across the FFM boundary. Rust must not hold
    registry/queue locks while invoking callbacks.

11. **Rust layering**: `net-bridge-core` is pure Rust with `#![forbid(unsafe_code)]` and no `jni`
    dependency, owning runtime/context/transport logic;
    `net-bridge-native` contains only C ABI glue (ABI structs, pointer validation, panic guards,
    function table, bootstrap export).

12. **Socket addresses use a fixed binary struct** (`nb_socket_address_v1_t`:
    family/port/address[16]/scope_id), replacing formatted strings plus
    `InetAddress.getByName`.

## Consequences

- `net_bridge.h` is the readable ABI contract checked into the repository. Rust structs and Java
  `MemoryLayout` are independently verified by layout tests to prevent drift. The exported symbol
  surface collapses to `netbridge_get_api` (validated in release CI with tools such as `nm -D`), and
  all JNI `Java_*` symbols are removed.
- The initial KCP profile is passed as a numeric enum (0/1 = balanced, 2 = aggressive), while host
  is passed as
  `nb_bytes_view_v1_t` (UTF-8, not NUL-terminated, valid only for the duration of the call).
- Older related ADRs are superseded accordingly: ADR-0006 (adaptive EventLoop polling) is replaced
  by event delivery after polling is removed; the JNI-related part of ADR-0007 (naming/module
  layout) is obsolete. Both are superseded separately.
- Rust `NativeContext`, `netbridge_get_api`, Java `FfmApiV1`, and layout/roundtrip/upcall tests are
  implemented first (Phase 4 POC), providing a verified ABI contract before the later FFM vertical
  slice and JNI cutover.

## Addendum (Final Audit)

- **Callback/context association**: each NativeContext owns its own dispatcher + upcall stub
  allocated from the same shared Arena, so events are naturally isolated by context. Identical
  object IDs in different contexts cannot cross-route, and deterministic tests cover this. If a
  future frozen public ABI needs a stronger multi-context token, a `callback_token` field can be
  added; for the current single-mod/single-backend case, option 2 is sufficient.
- **Lifecycle concurrency gate**: every Java downcall uses beginOp/endOp reference counting;
  close/destroy is CAS-once. Once closing begins, new operations are rejected, in-flight operations
  are drained, and only then are `context_destroy` and `Arena.close` executed. A close-vs-downcall
  stress test is a release gate.
- **Terminal tombstones**: after FAILED/CLOSED, the registry entry remains until Java releases it so
  `connection_state` can still query the terminal state. The terminal event is emitted exactly once;
  release calls `close_connection` and removes the entry. Removing before emitting the event is
  forbidden.
- **ID contract**: IDs start at 1 and are never reused. Allocator wraparound returns
  `BridgeError::IdOverflow` (mapped to `NB_INTERNAL`) and never wraps to 0.
- **Native manifest fails closed**: missing, malformed, or mismatched packaged manifests reject
  loading (`NativeResourceException`). Cached content is revalidated before reuse; concurrent
  extraction uses temp files + atomic move; unsupported platforms produce typed
  `UNSUPPORTED_PLATFORM`.

## Addendum: netbridge.h Generation Strategy (cbindgen Integration)

- **Source of truth**: the Rust C ABI definitions in `rust/crates/net-bridge-native/src/abi/` are
  the sole source of truth for `netbridge.h`, not a manually maintained C header.
- **Generation**: `netbridge.h` is generated deterministically by pinned cbindgen `0.29.2` (locked
  via the `rust/xtask` dependency in `Cargo.lock`)
  and committed to the repository; manual editing is forbidden.
- **Commands**: update with `./gradlew updateNativeHeader` or
  `cd rust && cargo xtask abi-header update`; verify with
  `./gradlew verifyNativeHeader` or `cargo xtask abi-header check` (verification never writes
  files).
- **CI drift gate**: PR CI and release CI both run the real generator check. A stale or manually
  edited header fails, and release packaging is blocked before artifacts are produced.
- **Generation does not replace tests**: Rust `repr(C)` layout tests, Java `MemoryLayout` tests, and
  real FFM integration tests remain required and complement the cbindgen drift gate.
- **jextract** remains an optional future consumer. The generated header stays ordinary C, but
  production builds do not introduce jextract today; Java FFM bindings remain handwritten and
  lifecycle-aware.

## Historical Note (Superseded)

The earlier recommendation to keep a "small, stable, handwritten C header + layout tests" (from an
older refactor-plan revision) has been superseded by the cbindgen decision above.
