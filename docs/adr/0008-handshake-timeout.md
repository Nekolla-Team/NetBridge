# ADR-0008: Transport Handshake Timeout and Liveness Criteria

Status: Accepted (Java 25 + FFM Round 4 revision) · Date: 2026-08-26 · Depends on: ADR-0002
(watchdog definition)

> Implementation note: the watchdog is now owned by the client runtime's `ConnectionPlanner` /
> `ConnectionExecutor`
> (`NativeRetryPolicy` 10s/20s), not the old static `HandshakeWatchdog` utility.
> In Round 4, handshake and CONNECTED liveness criteria were unified around data-plane readiness:
> KCP completes SYN + FEC + smux stream
> opening; QUIC completes the plaintext handshake + stream-probe readiness.

## Context

ADR-0002 specifies Java-side 10s/20s timeouts to decide handshake failure. We investigated whether
the three dependency libraries could natively own that responsibility (using locally pinned
versions: quinn 0.11.11 / quinn-proto 0.11.17 / quinn-plaintext 0.3.0 / kcp-rs 0.2.6).

### QUIC Findings

- quinn-plaintext only replaces `crypto::Session`/`ClientConfig`/`ServerConfig` with plaintext
  implementations and does not provide timeout behavior; the rest of quinn transport configuration
  is available but does not solve this problem.
- quinn-proto's `Timer::Idle` (which produces `ConnectionError::TimedOut`) is armed only through
  `on_packet_authenticated → reset_idle_timeout` — **it is reset only after receiving a packet and
  has no initial value at connection creation**.
- In a black-hole scenario (zero response), loss detection sends PTO probes forever (`pto_count` has
  no upper bound and no give-up logic), so Idle never fires and `connecting.await` **hangs
  indefinitely**.
- After communication has begun and then stalls, timeout is
  `max(negotiated idle timeout, default 30s, 3×PTO)`, far above the target.

Conclusion: quinn cannot terminate a black-hole handshake by itself within 10s/20s.

### KCP Findings (After kcp-rs Update)

- kcp-rs has a built-in handshake: client `KcpStream::connect` sends SYN (with a random session ID),
  waits for server confirmation, and then returns. If no response arrives within `connect_timeout`
  (native default 15s; this stack uses 8s), it returns `TimedOut`, so the native layer can detect
  failure without first-payload heuristics.
- `session_expire` (default 90s) still governs idle server-session reclamation and is unrelated to
  connection establishment.
- An earlier phase tentatively treated KCP CONNECTED as "SYN handshake complete". In the full data
  plane, however, the session is not usable until FEC and the smux stream are ready. Starting in
  Round 4, the final rule is therefore: **KCP transport handshake + FEC + smux session
  establishment + opened MC data stream**
  is the point at which STATE_CONNECTED and ACCEPTED become valid.

Conclusion: the KCP handshake can terminate itself in native code (8s < the 10s Java watchdog),
while the watchdog remains a safety net.

## Decision

1. **Java owns the unified handshake timeout policy** (first attempt 10s, subsequent attempt 20s,
   racing the connect promise). On KCP, native `connect_timeout` (8s) normally reports first; the
   watchdog mainly protects against QUIC black holes.
2. **KCP liveness criterion**: STATE_CONNECTED means the complete KCP SYN + FEC + smux stream data
   plane is ready; both client and server mark CONNECTED / ACCEPTED only after the data stream is
   open, never exposing a half-established state.
3. **QUIC remains unchanged**: the plaintext handshake exchanges transport parameters
   bidirectionally, so CONNECTED is real reachability proof; when the watchdog expires, closing the
   connection handle is sufficient.
4. Keep the remaining stack defaults: `max_idle_timeout` (30s) governs session liveness,
   `ReadTimeoutHandler(30)` governs application-layer idleness, and the three mechanisms remain
   orthogonal.

## Consequences

- The watchdog timeout path must actively call `closeConnection(connId)` to clean up the native
  handle. On a QUIC black hole the native task remains in the PTO loop, so failing to close it would
  leak work and state.
- The KCP handshake is 1 RTT (SYN + confirmation); an 8s budget is still ample on high-latency
  paths. The old "first-byte" criterion and FRAME_PROBE/FRAME_PONG probe frames were removed as part
  of the kcp-rs transition.
