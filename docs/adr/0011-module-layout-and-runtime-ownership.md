# ADR-0011: Module Layout and Java Runtime Ownership

Status: Accepted · Date: 2026-09-04 · Supersedes: ADR-0007 (module split and protocol-neutral JNI
naming)

## Context

In the ADR-0007-era layout, common plus duplicated loader sources were the main structure:
`NativeClientTransport`,
`NativeServerTransport`, and seven mixins were maintained separately under fabric/neoforge and kept
synchronized by
`checkSyncedCopies`; `ConnectStatus`/`ConnectionDisplay`/`TransportSelector`/`FallbackTracker`/
`NativeAcceptor`
used mutable static state; and `NetBridgeServices` was a "static service bag" containing three
independent static services plus implicit default bootstrapping.

## Decision

### Module Layout

```text
common      Pure Java domain/runtime/native abstractions/config (imports of net.minecraft and loader APIs are forbidden)
minecraft   Shared Minecraft-aware layer: NativeClientTransport, NativeServerTransport,
            seven shared mixins (top.tangge233.netbridge.mc / .mixin; loader API imports are forbidden)
fabric      Fabric bootstrap / lifecycle glue (MinecraftServerLifecycleMixin) / metadata only
neoforge    NeoForge bootstrap / event glue / metadata only
rust        workspace: net-bridge-core (pure transport core, forbid unsafe) + net-bridge-native (C ABI shell)
```

The shared layer is compiled into both loaders as a source set. Both use Mojmap; Fabric is remapped
by Loom to intermediary, while NeoForge 1.21+ uses Mojmap at runtime. `checkSyncedCopies` is
therefore removed — synchronization is guaranteed by architecture rather than process. Loader
modules retain only bootstrap/lifecycle glue.

### Java Runtime Ownership

- **Single composition root**: `NetBridgeServices` holds only one
  `static @Nullable NetBridgeRuntime runtime`. Any use before bootstrap throws
  `IllegalStateException`; implicit `config/net-bridge` default bootstrapping is removed.
- **`NetBridgeRuntime` (`AutoCloseable`)** owns configuration services, the native backend
  (available or
  `UnavailableNativeTransportBackend`), `ClientRuntime`, and `ServerRuntime`. Native loading failure
  does not prevent the runtime from existing:
  the client planner automatically selects TCP, and the server publishes no accelerated transports.
- **Instance-owned state**: client `ClientRuntime` owns capabilities/success caches, planner,
  executor, and state store; server `ServerRuntime`/`ServerTransportManager` owns session-scoped
  transactional start/stop state and supports integrated-server restarts. Mutable static state in
  production code is allowed only for the root locator (allowlist: `NetBridgeServices`, mixin-layer
  ThreadLocal guards, and the `StatusNetworksCapture` capture slot).
- **Architecture guards**: the Gradle `verifyArchitecture` task enforces these rules (FFM import
  allowlist, zero JNI, layer purity, package-level `@NullMarked` coverage, absence of
  duplicate-source directories, and a Rust core with no FFI/global registries).
  `verifyNativeSymbols` ensures the only business export from the cdylib is `netbridge_get_api`.

## Consequences

- There is one shared Minecraft source set, and both loader smoke tests pass; new platform features
  no longer require duplicate implementation.
- Every stateful component has an instance owner, bootstrap order is explicit, and an unavailable
  native backend still yields a complete runtime with TCP.
- Guard tasks are part of the `assembleAll` dependency chain, so architectural regressions fail the
  build immediately.
