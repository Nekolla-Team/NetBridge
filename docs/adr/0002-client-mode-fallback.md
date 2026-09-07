# ADR-0002: Client Transport Modes and TCP Fallback

Status: Accepted · Date: 2026-08-25

## Context

The client must choose among tcp / quic / kcp. Non-TCP transports may have UDP blocked by firewalls
or ISPs (a black-hole path), so predictable fallback behavior is required; otherwise players can be
left with indefinite connection failures.

## Decision

- **Three modes**: `tcp` / `quic` / `kcp`. There is no separate *-fallback-tcp mode:
  quic/kcp include TCP fallback by design; tcp is plain TCP and has no fallback concept.
- **Attempt sequence** within one connection flow: the selected transport is attempted at most **2
  times** — the first handshake times out after **10 s**
  (cold start), and the second after **20 s**; if both fail, that connection switches to TCP. Native
  FAILED/CLOSED counts as an immediate failure of that attempt rather than waiting for the timeout.
  Direct tcp connections do not use this sequence.
- **Timeout implementation**: a Java `HandshakeWatchdog` scheduled task races the connect promise.
  Neither transport stack provides a usable native handshake timeout for the required behavior, so
  this is the only viable solution (see ADR-0008 for evidence and constraints).
- **Fallback memory takes precedence over the attempt sequence**: if `FallbackTracker` hits for a
  server that fell back within the previous 5 min, use TCP directly without any accelerated attempt;
  after the TTL expires, run the full sequence again.
- The GUI tooltip consistently states that the client "automatically falls back to TCP after 2
  failed handshakes."

## Consequences

- `TransportMode` is reduced to the three values TCP/QUIC/KCP.
- Java needs handshake timers (10s/20s); it cannot wait indefinitely on native `connectionState`.

## Supplemental Decision (Finalized After Second Review)

- **Mode/announcement mismatch**: if the selected transport is not announced by the server (or its
  protocol is unsupported), **use TCP directly**. Do not try a different accelerated transport;
  there is no priority negotiation.
