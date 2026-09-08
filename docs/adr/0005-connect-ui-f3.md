# ADR-0005: Connection UI Copy and F3 Debug Line

Status: Accepted · Date: 2026-08-25

## Context

Players need to know which transport protocol the current connection uses, and fallback should
provide visible feedback. Requirements: the GUI labels only the protocol, the tooltip explains the
fallback policy, and F3 shows the transport actually in effect.

## Decision

### Connection Screen (ConnectScreen)

- Put the status copy on **a separate line** without changing the vanilla "Connecting to server"
  title line.
- Drive the copy from the **actual state machine**, not from the configured value, and update it
  live during connection:
    - Handshaking: `Connecting via QUIC…` / `Connecting via KCP…` / `Connecting via TCP…`
    - Keep the same message across both attempts; do not show a retry counter.
    - Fallback after two failures: `Falling back to TCP…`
- i18n keys are shaped like `connect.netbridge.quic` / `connect.netbridge.kcp` /
  `connect.netbridge.tcp` /
  `connect.netbridge.fallback`, with both zh_cn and en_us resources.
- The server-list tooltip explains that the client "automatically falls back to TCP after 2 failed
  handshakes."

### F3 Debug Screen

One line containing the currently active protocol and the actual transport endpoint address:

```
[net-bridge] QUIC 1.2.3.4:25565
```

- Use the uppercase enum protocol name (QUIC/KCP/TCP), not the protocol version string, which
  belongs to the wire layer.
- Address: quic/kcp shows the resolved announced `host:port`; TCP shows the original server address.
  Update it when fallback changes the active protocol.
- Inject it into the network debug section through a mixin; show it for direct TCP as well,
  explicitly indicating that TCP is currently in use.

## Consequences

- The client connection state machine needs a queryable handle shared by the ConnectScreen and F3
  mixins.

## Supplement (Finalized After Third Review)

- **Keep the in-game toggle button** at the bottom of the multiplayer screen and synchronize it
  bidirectionally with `client.toml`
  (read the file for the initial value at startup and write back immediately on toggle); the button
  tooltip uses the same fallback explanation.
- **en_us copy**: `Connecting via QUIC…` / `Connecting via KCP…` / `Connecting via TCP…` /
  `Falling back to TCP…` — clarity and brevity take priority.
