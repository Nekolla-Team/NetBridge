# ADR-0010: Native Event Delivery and Backpressure Model

Status: Accepted · Date: 2026-09-04 · Supersedes: ADR-0006 (data-plane threading model — adaptive
EventLoop polling)

## Context

In the ADR-0006 era, the Java side drove the data plane through polling: NativeChannel poll tasks
started at 5ms and backed off through 20/50/100ms, the server used a `net-bridge-accept` thread that
called `acceptConnections` every 5ms to adopt connections, and a full outbound queue likewise relied
on polling retries. Polling creates fixed wakeups, idle CPU cost, and shutdown races that are
difficult to prove correct.

After the FFM cutover (ADR-0009), Rust upcalls can reach Java at low cost, so event-driven delivery
becomes the default path.

## Decision

1. **Events replace polling**. Rust emits fixed-width primitive events through `EventSink::on_event`
   on state transitions, data enqueue, write-queue recovery, and server accept:
   CONNECTION_STATE=1, DATA_AVAILABLE=2, WRITABLE=3, ACCEPTED=4, SERVER_STATE=5. Java removes all
   polling tasks (channel poll/backoff, the 5ms accept thread, and `ADOPT_EXECUTOR` polling
   adoption).

2. **Callback-thread discipline**. Upcalls run on Rust Tokio worker threads. The callback may only
   decode the event, route by object ID to a typed wrapper, and marshal onto the target EventLoop /
   adoption executor. It must not block, execute Netty/Minecraft business logic, or retain temporary
   FFM pointers.

3. **DATA_AVAILABLE coalescing**. Rust emits an event after each successful enqueue of a new read
   block. Java uses a per-channel AtomicBoolean to debounce callbacks, so multiple callbacks
   schedule at most one EventLoop drain runnable. Each drain is capped at 16 reads × 64KiB, yielding
   back to the EventLoop under burst load. V1 does not add an empty→nonempty edge-trigger
   acknowledgement protocol.

4. **WRITABLE backpressure**. When the `connection_write` queue is full, return `NB_WOULD_BLOCK`
   with all-or-nothing semantics; partial writes do not exist. NativeChannel retains the message and
   calls `setUserDefinedWritability(false)`, then waits for a WRITABLE event before restoring
   writability and flushing. There is no 50ms timer polling. Upstream observes backpressure through
   the Netty outbound buffer; silently dropping bytes or busy-spinning is forbidden.

5. **ACCEPTED drives adoption**. After both endpoints have their stream/data plane ready, Rust
   registers the connection and emits `ACCEPTED(server_id, conn_id)`. Semantics are identical across
   QUIC/KCP: ACCEPTED occurs only after the data plane is ready; stopping a server prevents new
   accepts but does not kill connections already handed off.
   `ServerTransportManager` passes the connection through the adoption executor to
   `NativeConnectionAdopter`; the final handoff runs on the Minecraft server thread to perform
   registration and connection-list mutation. If the manager is closed, newly arrived or queued
   adoptions are rejected and the connection is closed. Adoption failure closes the connection, so
   raw IDs cannot leak.

6. **Connection-state events carry ABI values** (internal+1, matching the `connection_state`
   downcall). Connection-establishment failures also emit a terminal event; the connect promise is
   completed from events, with timeout only as a safety net in `ConnectionExecutor`.

## Consequences

- Idle connections have zero polling wakeups; backpressure is propagated to the Netty outbound
  buffer.
- Correctness is covered by fake-backend tests (would-block→writable, accept, state transitions) and
  FFM integration tests (QUIC/KCP loopback, callback storms, repeated backend lifecycles).
- Only if benchmarks show upcall frequency itself becoming a bottleneck should Rust-side edge
  triggering be considered; no acknowledgement protocol is introduced preemptively.

## Addendum (Final Audit)

- **Separate event commit from delivery (INV-4)**: while holding locks, Rust event handling performs
  only state validation and immutable event-descriptor construction. All locks (Mutex, commit_lock,
  DashMap guard) are released before calling the FFI sink. Java listeners likewise run only after
  internal monitors are released, eliminating FFI/user-code reentrancy deadlocks.
- **Async panic guard and task supervision**: tasks are uniformly owned by context-level JoinHandle
  collections through
  `NativeContext::spawn_connection_task / spawn_server_task`. Panics trigger corresponding
  connection/server terminal cleanup. QUIC and KCP readers are managed child tasks that are
  explicitly aborted and reaped when a connection closes.
- **Early-event race and adoption state machine**: early server-side ACCEPTED events are tracked by
  a single `PendingAcceptedConnection` state machine, including early FAILED/CLOSED detection and
  resource release. `FfmNativeServer` strictly separates active ownership from the undelivered
  queue, ensuring each connection is delivered to the server listener exactly once.
- **Client orchestration and ChannelFuture contract**: `DelegatingChannelFuture` implements the
  complete Netty Future contract using monitor-based conditional waiting and an atomic state
  machine, eliminating polling sleeps. `ClientRuntime` owns every in-flight connection attempt,
  native and TCP fallback alike; close cancels all in-flight work, and operations fail fast after
  closure.
