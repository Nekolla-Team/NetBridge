# ADR-0004: JNI Data-Plane Boundary Strategy

Status: Superseded by ADR-0009 · Date: 2026-08-25 · Supersedes: zero-copy expectations from ADR-0001
in pre-refactor comments

> **This ADR documents the JNI-era boundary strategy. After the Java 25 + FFM refactor it was fully
superseded by ADR-0009
> (Java 25 / FFM / C ABI) and is retained only as historical context.**

## Context

The data plane requires low overhead, but direct-memory access across the JNI boundary introduces
pointer-lifetime and safety complexity. The project is in Alpha, so correctness and safety take
priority.

## Decision

- **Zero-copy applies only within each language**: Rust can transfer `Bytes` at zero cost; Java
  avoids intermediate allocations internally.
- **Copying is explicitly allowed at the JNI boundary** in exchange for memory safety;
  cross-language direct-memory access is not added at this stage.
- Keep a bulk-byte bridge API: `writeChunk(byte[], length)` / `readChunk(maxBytes)` /
  `connectionState` / handle-based registries. Writes continue copying from `byte[]`; no new
  direct-buffer write variant is added.
- Panics never cross the native boundary (`catch_unwind` wraps calls); errors are written to stderr
  and default values are returned, preserving the existing convention.
- After transport generalization, JNI surface names must be protocol-neutral (handles are
  protocol-independent connection IDs), removing QUIC-specific naming.

## Consequences

- Exactly one boundary copy per chunk per direction; future performance work is driven by profiling
  data.
- Existing `readChunkInto(direct ByteBuffer)` is the only exempt direct-memory path because it has a
  complete SAFETY argument; no additional direct-memory variants are introduced.
