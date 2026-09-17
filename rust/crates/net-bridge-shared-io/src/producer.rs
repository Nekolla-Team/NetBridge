//! Producer end of a [`crate::ring::SharedRing`].
//!
//! Exactly one producer exists per ring and owns the `tail` cursor. Cursors are cached locally and
//! published with a release store; the peer cursor is read with an acquire load.

use std::sync::Arc;

use crate::ring::SharedRing;

/// Write half of the ring. The producer is the only writer of the data area and `tail`.
pub struct RingProducer {
    ring: Arc<SharedRing>,
    tail: u64,
}

impl RingProducer {
    pub(crate) fn new(ring: Arc<SharedRing>, tail: u64) -> Self {
        Self { ring, tail }
    }

    /// Returns the ring capacity in bytes.
    #[must_use]
    pub fn capacity(&self) -> usize {
        self.ring.capacity()
    }

    /// Returns the number of bytes currently free for writing.
    #[must_use]
    pub fn writable_len(&self) -> usize {
        let head = self.ring.load_head_acquire();
        let used = self.tail.wrapping_sub(head);
        (self.ring.capacity() as u64).saturating_sub(used) as usize
    }

    /// Acquires a contiguous writable window of at most `max` bytes.
    ///
    /// Returns `None` when the ring is full or `max == 0`. The window never wraps: if the free
    /// space is split across the end of the buffer, the caller acquires the first segment and then
    /// calls this again for the remainder.
    pub fn try_acquire(&mut self, max: usize) -> Option<WriteGrant<'_>> {
        let start = self.tail;
        let ring: &SharedRing = &self.ring;
        let capacity = ring.capacity();
        let head = ring.load_head_acquire();
        let used = start.wrapping_sub(head);
        let free = (capacity as u64).saturating_sub(used) as usize;
        let offset = (start as usize) & (capacity - 1);
        let contiguous = capacity - offset;
        let len = max.min(free).min(contiguous);
        if len == 0 {
            return None;
        }
        Some(WriteGrant {
            ring,
            tail: &mut self.tail,
            start,
            offset,
            len,
        })
    }

    /// Copies as much of `src` as fits into the ring and publishes it.
    ///
    /// Returns the number of bytes copied and whether the consumer must be woken. Partial progress
    /// is normal; callers loop until `src` is drained.
    pub fn copy_from(&mut self, src: &[u8]) -> (usize, bool) {
        if src.is_empty() {
            return (0, false);
        }
        let Some(mut grant) = self.try_acquire(src.len()) else {
            return (0, false);
        };
        let n = grant.len().min(src.len());
        grant.as_mut()[..n].copy_from_slice(&src[..n]);
        let wake = grant.commit(n);
        (n, wake)
    }

    /// Arms the producer wait state and re-checks for free space.
    ///
    /// Returns `true` when the caller must actually sleep, `false` when space appeared and no sleep
    /// is needed. On a `true` return the caller must eventually call
    /// [`RingProducer::resume_after_notification`].
    pub fn park_if_full(&self) -> bool {
        self.ring.producer_wait().park(|| self.writable_len() == 0)
    }

    /// Clears a pending `NOTIFIED` state after waking.
    pub fn resume_after_notification(&self) {
        self.ring.producer_wait().resume();
    }

    /// Requests a wakeup of a parked consumer. Returns `true` when the caller must signal.
    pub fn signal_consumer_if_parked(&self) -> bool {
        self.ring.consumer_wait().notify_if_parked()
    }
}

/// A contiguous writable window into the ring.
///
/// The grant borrows the producer mutably, so nothing else can publish while it is alive. Call
/// [`WriteGrant::commit`] (or drop without committing) to end the grant.
pub struct WriteGrant<'a> {
    ring: &'a SharedRing,
    tail: &'a mut u64,
    start: u64,
    offset: usize,
    len: usize,
}

impl WriteGrant<'_> {
    /// Returns the number of bytes this grant can accept.
    #[must_use]
    pub fn len(&self) -> usize {
        self.len
    }

    /// Returns `true` when the grant covers no bytes.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Publishes the first `written` bytes of the grant.
    ///
    /// Returns whether the consumer must be woken. Committing `0` publishes nothing and never
    /// signals.
    ///
    /// # Panics
    ///
    /// Panics in debug builds when `written` exceeds the grant.
    pub fn commit(self, written: usize) -> bool {
        assert!(
            written <= self.len,
            "commit {written} exceeds grant of {}",
            self.len
        );
        let new_tail = self.start.wrapping_add(written as u64);
        *self.tail = new_tail;
        if written == 0 {
            return false;
        }
        self.ring.commit_tail(new_tail)
    }
}

impl AsMut<[u8]> for WriteGrant<'_> {
    /// Returns the writable slice.
    fn as_mut(&mut self) -> &mut [u8] {
        // SAFETY: `WriteGrant` holds the producer's exclusive borrow for its lifetime, so no other
        // writer exists. The consumer only reads bytes before `head`, which is at or behind
        // `start`, so the granted window is not concurrently readable.
        unsafe { core::slice::from_raw_parts_mut(self.ring.data_ptr_at(self.offset), self.len) }
    }
}

impl std::fmt::Debug for RingProducer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RingProducer")
            .field("capacity", &self.capacity())
            .field("writable_len", &self.writable_len())
            .finish()
    }
}

impl std::fmt::Debug for WriteGrant<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("WriteGrant")
            .field("offset", &self.offset)
            .field("len", &self.len)
            .finish()
    }
}
