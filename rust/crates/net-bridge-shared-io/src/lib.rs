//! `net-bridge-shared-io`: a Rust-owned shared-memory SPSC byte-ring primitive.
//!
//! This crate implements only the memory layout, atomic protocol, and copy helpers for the
//! NetBridge data plane. It deliberately does **not** depend on Tokio, QUIC/KCP, or the C ABI, and
//! it is not wired into the production transport yet. The goal of this stage is to prove the
//! memory-ordering and wakeup protocol in isolation before any transport migration.
//!
//! # Invariants
//!
//! - One producer and one consumer per ring, each owning exactly one cursor.
//! - Cursors are monotonically increasing `u64` values; indexing masks with `capacity - 1`, so
//!   wraparound is handled by two's-complement subtraction.
//! - `used = tail - head` is always `<= capacity`.
//! - Data bytes are published with a release store of the cursor and read after an acquire load.
//! - Sleeping uses a shared wait state with a `SeqCst` store-load fence on both sides, making lost
//!   wakeups impossible (see [`wait`]).
//!
//! # Allocation
//!
//! The region is allocated with [`std::alloc`] and freed by [`SharedRegion`]'s `Drop`. Rust is the
//! sole owner: a foreign peer must only borrow a view and must never free the memory.

#![deny(unsafe_op_in_unsafe_fn)]
#![warn(missing_docs)]

mod atomic;
mod consumer;
mod error;
mod layout;
mod producer;
mod region;
mod ring;
mod wait;

pub use consumer::{ReadGrant, RingConsumer};
pub use error::SharedIoError;
pub use layout::{
    OFF_CAPACITY, OFF_CONSUMER_WAIT, OFF_DATA, OFF_HEAD, OFF_LAYOUT_VERSION, OFF_MAGIC,
    OFF_PRODUCER_WAIT, OFF_RESERVED0, OFF_RESERVED1, OFF_RESERVED2, OFF_TAIL, RING_ALIGNMENT,
    RING_HEADER_BYTES, RING_LAYOUT_MAJOR, RING_LAYOUT_MINOR, RING_MAGIC, RING_MAX_CAPACITY,
    RING_MIN_CAPACITY, is_supported_capacity, layout_version, validate_capacity,
};
pub use producer::{RingProducer, WriteGrant};
pub use ring::SharedRing;
pub use wait::{WAIT_ACTIVE, WAIT_NOTIFIED, WAIT_PARKED, WaitState};
