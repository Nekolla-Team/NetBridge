# ADR-0001: networks Capability Discovery Wire Format v2

Status: Accepted · Date: 2026-08-25 · Supersedes: pre-refactor networks format (ADR-0002 in old code
comments)

## Context

The server announces transport capabilities by injecting a top-level `networks` object into the
server-list ping response. The old format was
`quic: {features: ["quic-raw"], port, protocol: "net-bridge/1"}`. After adding KCP, a unified
multi-transport announcement is required, and the project is in Alpha, so breaking changes are
acceptable (hard cutover with no dual-format compatibility period).

## Decision

Wire format v2:

```json
"networks": {
  "quic": {
    "enable": true,
    "host": "1.1.1.1",
    "port": 25565,
    "protocol": "net-bri-quic/1"
  },
  "kcp": {
    "enable": true,
    "host": null,
    "port": 25566,
    "protocol": "net-bri-kcp/1"
  }
}
```

- Each transport has a peer-level entry with the same field set: `enable` / `host` / `port` /
  `protocol`.
- **Only resolved concrete values appear on the wire**: server `[quic]/[kcp] port = -1/0` values are
  binding conveniences only. They are resolved to the actual listening port at startup before the
  entry is written, so wire `port` is always 1..=65535 and never -1/0.
- Missing `enable` is treated as `false`.
- Missing or null `host`: the client uses the server address from the ping target (the port still
  comes from the entry; if the entire entry is missing, that transport does not exist and no
  fallback address is synthesized).
- The **`features` field is removed**. Future transport algorithms are represented by new entries or
  extended fields; version evolution is governed only by `protocol`.
- **Protocol negotiation**: the client compares each entry's `protocol` exactly against its
  supported set (`net-bri-quic/1`, `net-bri-kcp/1`). An unsupported protocol disables that transport
  locally, as though it had not been announced.
- The old format is not parsed. Old clients connecting to new servers will simply see no capability
  and fall back to TCP, which is expected.

## Consequences

- `NetworksAbility` is refactored into a generic model keyed by transport name, and all features
  logic is removed.
- The protocol string is the version gate: later incompatible changes increment strings such as
  `net-bri-quic/2`.
