//! Consumer end of a [`crate::ring::SharedRing`].
//!
//! Exactly one consumer exists per ring and owns the `head` cursor. Cursors are cached locally and
//! published with a release store; the peer cursor is read with an acquire load.

use std::sync::Arc;

use crate::ring::SharedRing;

/// Read half of the ring. The consumer is the only reader of the data area and `head`.
pub struct RingConsumer {
    ring: Arc<SharedRing>,
    head: u64,
}

impl RingConsumer {
    pub(crate) fn new(ring: Arc<SharedRing>, head: u64) -> Self {
        Self { ring, head }
    }

    /// Returns the ring capacity in bytes.
    #[must_use]
    pub fn capacity(&self) -> usize {
        self.ring.capacity()
    }

    /// Returns the number of bytes currently available to read.
    #[must_use]
    pub fn readable_len(&self) -> usize {
        let tail = self.ring.load_tail_acquire();
        tail.wrapping_sub(self.head) as usize
    }

    /// Acquires a contiguous readable window of at most `max` bytes.
    ///
    /// Returns `None` when the ring is empty or `max == 0`. The window never wraps; call again to
    /// read the segment at the start of the buffer.
    pub fn try_acquire(&mut self, max: usize) -> Option<ReadGrant<'_>> {
        let start = self.head;
        let ring: &SharedRing = &self.ring;
        let capacity = ring.capacity();
        let tail = ring.load_tail_acquire();
        let readable = tail.wrapping_sub(start) as usize;
        let offset = (start as usize) & (capacity - 1);
        let contiguous = capacity - offset;
        let len = max.min(readable).min(contiguous);
        if len == 0 {
            return None;
        }
        Some(ReadGrant {
            ring,
            head: &mut self.head,
            start,
            offset,
            len,
        })
    }

    /// Copies as much readable data as fits into `dst` and publishes the consumption.
    ///
    /// Returns the number of bytes copied and whether the producer must be woken. Partial progress
    /// is normal; callers loop until `dst` is filled or the ring is empty.
    pub fn copy_into(&mut self, dst: &mut [u8]) -> (usize, bool) {
        if dst.is_empty() {
            return (0, false);
        }
        let Some(grant) = self.try_acquire(dst.len()) else {
            return (0, false);
        };
        let n = grant.len().min(dst.len());
        dst[..n].copy_from_slice(&grant.as_ref()[..n]);
        let wake = grant.commit(n);
        (n, wake)
    }

    /// Arms the consumer wait state and re-checks for readable data.
    ///
    /// Returns `true` when the caller must actually sleep, `false` when data appeared. On a `true`
    /// return the caller must eventually call [`RingConsumer::resume_after_notification`].
    pub fn park_if_empty(&self) -> bool {
        self.ring.consumer_wait().park(|| self.readable_len() == 0)
    }

    /// Clears a pending `NOTIFIED` state after waking.
    pub fn resume_after_notification(&self) {
        self.ring.consumer_wait().resume();
    }

    /// Requests a wakeup of a parked producer. Returns `true` when the caller must signal.
    pub fn signal_producer_if_parked(&self) -> bool {
        self.ring.producer_wait().notify_if_parked()
    }
}

/// A contiguous readable window into the ring.
pub struct ReadGrant<'a> {
    ring: &'a SharedRing,
    head: &'a mut u64,
    start: u64,
    offset: usize,
    len: usize,
}

impl ReadGrant<'_> {
    /// Returns the number of bytes this grant can read.
    #[must_use]
    pub fn len(&self) -> usize {
        self.len
    }

    /// Returns `true` when the grant covers no bytes.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Publishes the consumption of the first `consumed` bytes.
    ///
    /// Returns whether the producer must be woken. Consuming `0` publishes nothing and never
    /// signals.
    ///
    /// # Panics
    ///
    /// Panics in debug builds when `consumed` exceeds the grant.
    pub fn commit(self, consumed: usize) -> bool {
        assert!(
            consumed <= self.len,
            "commit {consumed} exceeds grant of {}",
            self.len
        );
        let new_head = self.start.wrapping_add(consumed as u64);
        *self.head = new_head;
        if consumed == 0 {
            return false;
        }
        self.ring.commit_head(new_head)
    }
}

impl AsRef<[u8]> for ReadGrant<'_> {
    /// Returns the readable slice.
    fn as_ref(&self) -> &[u8] {
        self.ring.read_slice(self.offset, self.len)
    }
}

impl std::fmt::Debug for RingConsumer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RingConsumer")
            .field("capacity", &self.capacity())
            .field("readable_len", &self.readable_len())
            .finish()
    }
}

impl std::fmt::Debug for ReadGrant<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ReadGrant")
            .field("offset", &self.offset)
            .field("len", &self.len)
            .finish()
    }
}
