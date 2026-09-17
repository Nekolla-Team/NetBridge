//! Shared SPSC ring: cursor math, publication, and the split into producer/consumer ends.
//!
//! Cursors are monotonically increasing `u64` values wrapped by `capacity - 1` for indexing, so
//! even after wraparound the difference `tail - head` remains correct through two's-complement
//! wrapping. Both `tail` and `head` are written by exactly one side.

use crate::atomic::{AtomicU64, Ordering};
use crate::consumer::RingConsumer;
use crate::error::SharedIoError;
use crate::layout::{
    OFF_CONSUMER_WAIT, OFF_HEAD, OFF_PRODUCER_WAIT, OFF_TAIL, RING_MAX_CAPACITY, validate_capacity,
};
use crate::producer::RingProducer;
use crate::region::SharedRegion;
use crate::wait::{WAIT_ACTIVE, WAIT_PARKED, WaitState};
use std::sync::Arc;

/// A single Rust-owned bidirectional ring endpoint pair.
///
/// A `SharedRing` holds one direction of the byte stream; the duplex configuration is two rings.
/// Allocation is owned by Rust and shared through an `Arc` between the two ends.
pub struct SharedRing {
    region: SharedRegion,
}

impl SharedRing {
    /// Allocates a ring with the production capacity constraints (power of two, `64 KiB ..= 1 TiB`).
    pub fn allocate(capacity: usize) -> Result<Arc<Self>, SharedIoError> {
        validate_capacity(capacity)?;
        Ok(Arc::new(Self {
            region: SharedRegion::allocate(capacity)?,
        }))
    }

    /// Allocates a relaxed ring used by in-crate tests, allowing small power-of-two capacities.
    #[cfg(test)]
    pub(crate) fn allocate_raw(capacity: usize) -> Result<Arc<Self>, SharedIoError> {
        crate::layout::validate_raw_capacity(capacity)?;
        Ok(Arc::new(Self {
            region: SharedRegion::allocate(capacity)?,
        }))
    }

    /// Returns the largest capacity this primitive accepts.
    #[must_use]
    pub const fn max_capacity() -> usize {
        RING_MAX_CAPACITY
    }

    /// Returns the ring capacity in data bytes.
    #[must_use]
    pub fn capacity(&self) -> usize {
        self.region.capacity()
    }

    /// Returns the total mapped size in bytes (`header + capacity`).
    #[must_use]
    pub fn total_bytes(&self) -> usize {
        self.region.total_bytes()
    }

    /// Returns the base address of the region.
    #[must_use]
    pub fn base_address(&self) -> usize {
        self.region.base_address()
    }

    /// Splits the ring into its single producer and single consumer.
    #[must_use]
    pub fn split(self: &Arc<Self>) -> (RingProducer, RingConsumer) {
        let tail = self.load_tail_acquire();
        let head = self.load_head_acquire();
        (
            RingProducer::new(Arc::clone(self), tail),
            RingConsumer::new(Arc::clone(self), head),
        )
    }

    /// Validates the region header against this build's layout.
    pub fn validate(&self, expected_capacity: usize) -> Result<(), SharedIoError> {
        self.region.validate(expected_capacity)
    }

    /// Publishes a new tail value and returns whether the consumer must be woken.
    ///
    /// The release store makes the data bytes visible; [`WaitState::notify_if_parked`] inserts the
    /// `SeqCst` fence that closes the lost-wakeup race.
    pub(crate) fn commit_tail(&self, value: u64) -> bool {
        self.atomic(OFF_TAIL).store(value, Ordering::Release);
        self.consumer_wait().notify_if_parked()
    }

    /// Publishes a new head value and returns whether the producer must be woken.
    pub(crate) fn commit_head(&self, value: u64) -> bool {
        self.atomic(OFF_HEAD).store(value, Ordering::Release);
        self.producer_wait().notify_if_parked()
    }

    pub(crate) fn load_tail_acquire(&self) -> u64 {
        self.atomic(OFF_TAIL).load(Ordering::Acquire)
    }

    pub(crate) fn load_head_acquire(&self) -> u64 {
        self.atomic(OFF_HEAD).load(Ordering::Acquire)
    }

    pub(crate) fn producer_wait(&self) -> &WaitState {
        // SAFETY: the slot is a live header atomic.
        unsafe { WaitState::from_atomic(self.atomic(OFF_PRODUCER_WAIT)) }
    }

    pub(crate) fn consumer_wait(&self) -> &WaitState {
        // SAFETY: the slot is a live header atomic.
        unsafe { WaitState::from_atomic(self.atomic(OFF_CONSUMER_WAIT)) }
    }

    pub(crate) fn data_ptr_at(&self, offset: usize) -> *mut u8 {
        self.region.data_ptr_at(offset)
    }

    pub(crate) fn read_slice(&self, offset: usize, len: usize) -> &[u8] {
        self.region.data_ref(offset, len)
    }

    fn atomic(&self, offset: usize) -> &AtomicU64 {
        self.region.atomic(offset)
    }

    /// Overrides both cursors. Test-only: used to exercise wraparound near `u64::MAX`.
    #[cfg(all(test, not(loom)))]
    pub(crate) fn force_cursors(&self, tail: u64, head: u64) {
        self.atomic(OFF_TAIL).store(tail, Ordering::Relaxed);
        self.atomic(OFF_HEAD).store(head, Ordering::Relaxed);
    }
}

/// Compile-time assertion that the initial wait states match the design.
const _: () = {
    assert!(WAIT_ACTIVE == 0);
    assert!(WAIT_PARKED == 1);
};

#[cfg(all(test, not(loom)))]
mod tests {
    use super::*;
    use crate::error::SharedIoError;
    use crate::layout::{RING_MIN_CAPACITY, is_supported_capacity, layout_version};
    use crate::wait::{WAIT_ACTIVE, WAIT_NOTIFIED, WAIT_PARKED};

    const CAP: usize = 256;

    fn pattern(len: usize, seed: u8) -> Vec<u8> {
        (0..len)
            .map(|i| seed.wrapping_add((i as u8).wrapping_mul(31)))
            .collect()
    }

    #[test]
    fn production_capacity_validation() {
        assert!(is_supported_capacity(RING_MIN_CAPACITY));
        assert!(!is_supported_capacity(RING_MIN_CAPACITY - 1));
        assert!(!is_supported_capacity(3));
        assert!(matches!(
            SharedRing::allocate(1024),
            Err(SharedIoError::CapacityTooSmall { .. })
        ));
        assert!(matches!(
            SharedRing::allocate(3),
            Err(SharedIoError::CapacityNotPowerOfTwo(3))
        ));
        assert!(matches!(
            SharedRing::allocate(1 << 41),
            Err(SharedIoError::CapacityTooLarge(_))
        ));
        assert!(SharedRing::allocate(RING_MIN_CAPACITY).is_ok());
    }

    #[test]
    fn header_layout_constants() {
        assert_eq!(layout_version(), (1 << 32));
        assert_eq!(crate::layout::RING_MAGIC, u64::from_le_bytes(*b"NBRING01"));
    }

    #[test]
    fn empty_ring_reports_zero() {
        let ring = SharedRing::allocate_raw(CAP).unwrap();
        let (mut p, mut c) = ring.split();
        assert_eq!(p.writable_len(), CAP);
        assert_eq!(c.readable_len(), 0);
        assert!(c.try_acquire(1).is_none());
        assert!(p.try_acquire(0).is_none());
    }

    #[test]
    fn copy_roundtrip_preserves_bytes() {
        let ring = SharedRing::allocate_raw(CAP).unwrap();
        let (mut p, mut c) = ring.split();
        let src = pattern(200, 7);
        let (written, _) = p.copy_from(&src);
        assert_eq!(written, 200);
        let mut dst = vec![0u8; 200];
        let (read, _) = c.copy_into(&mut dst);
        assert_eq!(read, 200);
        assert_eq!(dst, src);
    }

    #[test]
    fn ring_reports_full_and_empty() {
        let ring = SharedRing::allocate_raw(CAP).unwrap();
        let (mut p, mut c) = ring.split();
        let src = pattern(CAP, 1);
        let (written, _) = p.copy_from(&src);
        assert_eq!(written, CAP);
        assert_eq!(p.writable_len(), 0);
        assert_eq!(c.readable_len(), CAP);
        assert!(p.try_acquire(1).is_none());
        assert_eq!(p.copy_from(&[0u8]).0, 0);

        let mut sink = vec![0u8; CAP];
        let (read, _) = c.copy_into(&mut sink);
        assert_eq!(read, CAP);
        assert_eq!(c.readable_len(), 0);
        assert_eq!(p.writable_len(), CAP);
    }

    #[test]
    fn wraps_two_segments_across_end() {
        let ring = SharedRing::allocate_raw(128).unwrap();
        let (mut p, mut c) = ring.split();

        // Advance the cursors so the next write starts near the end of the buffer.
        let first = pattern(100, 2);
        p.copy_from(&first);
        let mut drain = vec![0u8; 100];
        c.copy_into(&mut drain);
        assert_eq!(drain, first);

        let second = pattern(80, 9);
        let mut written = 0;
        while written < second.len() {
            let (n, _) = p.copy_from(&second[written..]);
            assert!(n > 0, "producer stalled while space remains");
            written += n;
        }
        assert_eq!(written, second.len());

        let mut out = vec![0u8; second.len()];
        let mut read = 0;
        while read < out.len() {
            let (n, _) = c.copy_into(&mut out[read..]);
            assert!(n > 0, "consumer stalled while data remains");
            read += n;
        }
        assert_eq!(out, second);
    }

    #[test]
    fn try_acquire_is_contiguous_and_commits_partial() {
        let ring = SharedRing::allocate_raw(128).unwrap();
        let (mut p, _c) = ring.split();
        let filler = pattern(120, 5);
        p.copy_from(&filler);
        assert_eq!(p.writable_len(), 8);

        // The first free window ends at the buffer boundary.
        let mut grant = p.try_acquire(64).unwrap();
        assert_eq!(grant.len(), 8);
        grant.as_mut()[..3].copy_from_slice(&[1, 2, 3]);
        let _ = grant.commit(3);
        assert_eq!(p.writable_len(), 5);

        // Zero commit must not publish or signal.
        let grant = p.try_acquire(5).unwrap();
        assert_eq!(grant.len(), 5);
        assert!(!grant.commit(0));
    }

    #[test]
    fn used_plus_writable_is_invariant() {
        let ring = SharedRing::allocate_raw(CAP).unwrap();
        let (mut p, mut c) = ring.split();
        for step in 0..2000usize {
            let len = (step * 7) % 97 + 1;
            let src = pattern(len, step as u8);
            let (n, _) = p.copy_from(&src);
            assert_eq!(c.readable_len() + p.writable_len(), CAP);
            if n > 0 {
                let mut dst = vec![0u8; n];
                let (m, _) = c.copy_into(&mut dst);
                assert_eq!(m, n);
                assert_eq!(dst, src[..n]);
            }
        }
    }

    #[test]
    fn cursor_wraparound_is_transparent() {
        let ring = SharedRing::allocate_raw(CAP).unwrap();
        // Force both cursors just below u64::MAX so a write wraps the cursor.
        ring.force_cursors(u64::MAX - 100, u64::MAX - 100);
        let (mut p, mut c) = ring.split();

        let src = pattern(150, 11);
        let mut written = 0;
        while written < src.len() {
            let (n, _) = p.copy_from(&src[written..]);
            assert!(n > 0);
            written += n;
        }
        assert_eq!(written, src.len());

        let mut out = vec![0u8; src.len()];
        let mut read = 0;
        while read < out.len() {
            let (n, _) = c.copy_into(&mut out[read..]);
            assert!(n > 0);
            read += n;
        }
        assert_eq!(out, src);
    }

    #[test]
    fn wait_state_edge_triggers_release_exactly_once() {
        let ring = SharedRing::allocate_raw(CAP).unwrap();
        let (mut p, mut c) = ring.split();

        // The consumer starts parked, so the first commit claims the wakeup.
        assert_eq!(ring.consumer_wait().load(), WAIT_PARKED);
        let (_, woke) = p.copy_from(&pattern(16, 1));
        assert!(woke);
        assert_eq!(ring.consumer_wait().load(), WAIT_NOTIFIED);

        // Draining the ring does not re-signal.
        let mut sink = vec![0u8; 16];
        let (_, woke) = c.copy_into(&mut sink);
        assert!(!woke);

        // An empty consumer can park; a later commit claims exactly one wakeup.
        c.resume_after_notification();
        assert!(c.park_if_empty());
        assert_eq!(ring.consumer_wait().load(), WAIT_PARKED);
        let (_, woke) = p.copy_from(&pattern(8, 2));
        assert!(woke);
        assert!(!p.signal_consumer_if_parked());
    }

    #[test]
    fn producer_park_cancels_once_space_appears() {
        let ring = SharedRing::allocate_raw(CAP).unwrap();
        let (mut p, mut c) = ring.split();
        assert_eq!(p.copy_from(&pattern(CAP, 3)).0, CAP);

        // Still full: the producer must sleep.
        assert!(p.park_if_full());
        assert_eq!(ring.producer_wait().load(), WAIT_PARKED);

        // The consumer frees space and claims the wakeup.
        let mut sink = vec![0u8; 64];
        let (_, woke) = c.copy_into(&mut sink);
        assert!(woke);
        assert_eq!(ring.producer_wait().load(), WAIT_NOTIFIED);

        // After resuming, the producer observes space and does not sleep.
        p.resume_after_notification();
        assert_eq!(ring.producer_wait().load(), WAIT_ACTIVE);
        assert!(!p.park_if_full());
        assert_eq!(ring.producer_wait().load(), WAIT_ACTIVE);
    }

    #[test]
    fn validate_exposes_region_contract() {
        let ring = SharedRing::allocate_raw(CAP).unwrap();
        ring.validate(CAP).unwrap();
        assert_eq!(ring.capacity(), CAP);
        assert_eq!(ring.total_bytes(), crate::layout::RING_HEADER_BYTES + CAP);
    }
}
