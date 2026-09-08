# ADR-0003: Server Port Semantics and Connection Limits

Status: Accepted · Date: 2026-08-25

## Context

The server `[quic]` / `[kcp]` configuration sections use the same fields: `enable` / `bind` /
`host` / `port` / `max_connection`
(nightconfig TOML). Port and capacity semantics must be unambiguous, and UDP services must also
defend against reflection abuse.

## Decision

### enable

- quic defaults to `true`; kcp defaults to `false` and is enabled on demand.

### port

- `-1` (default): follow the server port. **Exception: kcp follows the MC port +1** to avoid
  colliding with possible UDP use on the same port.
- `0`: let the system allocate a random port; log the actual port after startup.
- Other values must be in 1..=65535.
- **Bind failures (for example, an occupied port) follow the same path as out-of-range values**: log
  an error and disable that transport without affecting the other transport or the main TCP server.
- **Announcement rule**: `-1`/`0` are binding conveniences only; the ping entry always contains the
  resolved actual listening port (ADR-0001, so -1/0 never appears on the wire).

### max_connection (default 256)

- Once the active connection limit is reached, **new clients are silently dropped**: no rejection
  response and no error frame is sent.
- Rationale: UDP reflection/amplification defense — any predictable response can be abused as a
  reflection source with spoofed addresses.
- quic and kcp limits are **counted independently** for their respective sections.

### bind / host

- `bind`: literal listener interface IP, default `0.0.0.0`.
- `host`: address advertised in ping; null/missing means follow the server address.

### Migration

- No migration from old configuration is provided (Alpha hard cutover); fields that cannot be parsed
  fall back individually to defaults and are logged.

## Consequences

- Dropped clients observe a handshake timeout and follow the existing TCP fallback path; no
  dedicated error UI is needed.
- The current implementation uses a global `ACTIVE_SERVER_CONNS` counter across all server
  instances; the refactor must change this to **per-server-instance counters**, which is required
  for independent quic/kcp limits.
