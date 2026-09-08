# ADR-0006: Data-Plane Threading Model — Adaptive EventLoop Polling

Status: Superseded by ADR-0010 · Date: 2026-08-25 · Supersedes: the ADR-0001 wording in pre-refactor
comments about QuicChannel fixed 5ms polling

## Context

The native side (tokio) asynchronously produces and consumes bytes, while the Java Netty EventLoop
consumes them. Investigation of candidate approaches concluded:

| Approach                                                                      | Conclusion                                                                                                                                                                                                                                                                 |
|-------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Rust→Java callback (permanently attached AttachCurrentThread dispatch thread) | Rejected. Each attach/detach costs roughly 50–100μs; permanent attachment requires custom thread lifecycle management, DeleteLocalRef handling, bootstrap classloader constraints, and shutdown deadlock risk, while the only benefit is eliminating ≤5ms polling latency. |
| Register a wakeup fd with NioEventLoop                                        | Rejected. Wrapping a native fd as a SelectableChannel depends on internal `sun.nio.ch` APIs and is not portable across launchers/JVM distributions.                                                                                                                        |
| One dedicated blocking-read thread per connection                             | Rejected. Thread count grows linearly with connections, and data still has to be marshaled back onto the EventLoop.                                                                                                                                                        |
| Scheduled EventLoop polling                                                   | **Adopted.** The current loop makes 2–3 JNI calls per iteration (sub-microsecond), and fixed 5ms polling has already proven workable; only adaptive behavior is needed.                                                                                                    |

## Decision

Keep the `AbstractChannel` + `scheduleAtFixedRate` polling architecture, but change it to **stepped
backoff**:

- **Handshake/connection phase**: fixed 5ms polling to detect STATE_CONNECTED/FAILED/CLOSED quickly
  and support 10s/20s timeout decisions.
- **Connected phase**: stay at 5ms while active (data observed in the current iteration or a
  non-empty write queue); consecutive idle iterations back off 5→10→20→40ms, capped at 40ms (one MC
  tick). Any incoming data or queued write immediately resets to 5ms.
- Keep the current read path: pooled direct buffer + `readChunkInto`, at most 16 reads per iteration
  to avoid monopolizing the EventLoop.
- Keep the current write path: FastThreadLocal-reused scratch `byte[]` + `writeChunk`; if the queue
  is full, retry on the next flush cycle.

## Consequences

- Idle-connection CPU overhead approaches zero (worst case 25 JNI calls/second/connection), while
  active latency remains capped at 5ms.
- If profiling shows 5ms polling is a bottleneck, revisit a wakeup-fd design, potentially using
  extension points in Netty's epoll transport; that is outside the current scope.
