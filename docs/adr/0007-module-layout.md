# ADR-0007: Module Layout and Protocol-Neutral JNI Naming

Status: Superseded by ADR-0011 · Date: 2026-08-25 · Supersedes: the dual-copy convention wording
from ADR-0006 in pre-refactor comments (the copy strategy itself remains)

## Context

After adding KCP, QUIC-specific names such as `QuicNative` / `QuicChannel` no longer describe their
role accurately; capability parsing, mode decisions, and fallback state logic are mixed together in
a flat `net` package. The refactor reorganizes responsibilities into layers.

## Decision

### Java (common)

```
top.tangge233.netbridge
├── jni/          NativeBridge (formerly QuicNative), NativeLoader, NativeConnState
├── ability/      NetworksAbility, NetworksEntry(enable/host/port/protocol),
│                 StatusNetworksCodec(wire v2 injection/parsing), TransportProtocol(version constants + supported-set matching)
├── transport/    TransportMode(tcp/quic/kcp), ClientConfig(client.toml),
│                 KcpProfile(balance/aggressive), HandshakeWatchdog(10s/20s),
│                 FallbackTracker(TTL 5min), TransportSelector(decision entry point)
├── channel/      NativeChannel (formerly QuicChannel, protocol-neutral)
└── server/       ServerConfig(server.toml [quic]/[kcp]), NativeAcceptor
```

### Rust (net-bridge-native)

```
src/
├── transport.rs      trait Transport { connect/accept/state/read/write/close }
├── bridge/mod.rs     handle registry (protocol-independent IDs), server/client facade
├── bridge/quic/      quinn-plaintext implementation
└── bridge/kcp/       fec_stream + kcp-rs + smux (existing structure retained)
```

The JNI export layer performs only argument conversion; business logic lives behind the `bridge`
facade, and both protocols dispatch through `dyn Transport`.

### ABI

- Class rename ⇒ exported symbols become `Java_top_tangge233_netbridge_jni_NativeBridge_*`.
- ABI version `0.1.0` → `0.2.0`; reject loading on mismatch (Alpha has no compatibility burden).

### Platform Layer (fabric/neoforge)

Keep the duplicated-source strategy; mixins provide only attachment points, with all logic pushed
down into common.

## Consequences

- Adding a transport means implementing the `Transport` trait plus adding an ability entry and a
  TransportMode enum value, without changing the framework.
