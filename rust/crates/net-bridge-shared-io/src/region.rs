//! Rust-owned shared region: allocation, header access, and validation.
//!
//! The region is a single aligned allocation. Ownership stays entirely on the Rust side; Java
//! borrows a read/write view and must never deallocate it. `Drop` frees the allocation, so the
//! owner must keep the last `Arc` alive until every foreign view has been invalidated.

use core::mem::{align_of, size_of};
use std::alloc::{Layout, alloc, dealloc};
use std::ptr::NonNull;
use std::slice;

use crate::atomic::{AtomicU64, Ordering};
use crate::error::SharedIoError;
use crate::layout::{
    OFF_CAPACITY, OFF_CONSUMER_WAIT, OFF_HEAD, OFF_LAYOUT_VERSION, OFF_MAGIC, OFF_PRODUCER_WAIT,
    OFF_RESERVED0, OFF_RESERVED1, OFF_RESERVED2, OFF_TAIL, RING_ALIGNMENT, RING_HEADER_BYTES,
    RING_MAGIC, layout_version,
};
use crate::wait::{WAIT_ACTIVE, WAIT_PARKED};

/// A Rust-owned, 128-byte-aligned region of `RING_HEADER_BYTES + capacity` bytes.
pub(crate) struct SharedRegion {
    ptr: NonNull<u8>,
    layout: Layout,
    capacity: usize,
}

// SAFETY: every mutable header slot is an atomic and the data area is only accessed by the
// single producer and single consumer according to the ring protocol.
unsafe impl Send for SharedRegion {}
// SAFETY: see the `Send` impl; the region is designed to be shared across two threads.
unsafe impl Sync for SharedRegion {}

impl SharedRegion {
    /// Allocates and initializes a region. `validate` for the production minimum is applied by the
    /// caller (`SharedRing::allocate`).
    pub(crate) fn allocate(capacity: usize) -> Result<Self, SharedIoError> {
        let total = RING_HEADER_BYTES
            .checked_add(capacity)
            .ok_or(SharedIoError::SizeOverflow)?;
        let layout = Layout::from_size_align(total, RING_ALIGNMENT)
            .map_err(|_| SharedIoError::AllocationFailed)?;
        // SAFETY: `layout` has a non-zero size (header bytes >= 1).
        let raw = unsafe { alloc(layout) };
        let ptr = NonNull::new(raw).ok_or(SharedIoError::AllocationFailed)?;
        let region = Self {
            ptr,
            layout,
            capacity,
        };
        region.initialize();
        Ok(region)
    }

    /// Writes the immutable header fields and initial wait states. Runs before the region is
    /// published to the peer, so relaxed stores are sufficient.
    fn initialize(&self) {
        self.atomic(OFF_MAGIC).store(RING_MAGIC, Ordering::Relaxed);
        self.atomic(OFF_LAYOUT_VERSION)
            .store(layout_version(), Ordering::Relaxed);
        self.atomic(OFF_CAPACITY)
            .store(self.capacity as u64, Ordering::Relaxed);
        self.atomic(OFF_PRODUCER_WAIT)
            .store(WAIT_ACTIVE, Ordering::Relaxed);
        // The consumer starts parked so the first published byte always produces a wake edge.
        self.atomic(OFF_CONSUMER_WAIT)
            .store(WAIT_PARKED, Ordering::Relaxed);
        self.atomic(OFF_RESERVED0).store(0, Ordering::Relaxed);
        self.atomic(OFF_RESERVED1).store(0, Ordering::Relaxed);
        self.atomic(OFF_RESERVED2).store(0, Ordering::Relaxed);
        self.atomic(OFF_TAIL).store(0, Ordering::Relaxed);
        self.atomic(OFF_HEAD).store(0, Ordering::Relaxed);
    }

    /// Returns a reference to the atomic at `offset`.
    pub(crate) fn atomic(&self, offset: usize) -> &AtomicU64 {
        debug_assert!(offset + size_of::<AtomicU64>() <= self.layout.size());
        debug_assert_eq!(offset % align_of::<AtomicU64>(), 0);
        // SAFETY: the header fields are within the allocation, 8-byte aligned, and are never
        // accessed through a non-atomic type.
        unsafe { &*self.ptr.as_ptr().add(offset).cast::<AtomicU64>() }
    }

    /// Returns a raw pointer to the start of the data area plus `offset`.
    ///
    /// Returns `*mut u8` rather than `&mut [u8]` because the region is shared: the single producer
    /// is responsible for turning it into a slice while it holds the exclusive write grant.
    pub(crate) fn data_ptr_at(&self, offset: usize) -> *mut u8 {
        debug_assert!(offset <= self.capacity);
        // SAFETY: `RING_HEADER_BYTES + offset` stays inside the allocation.
        unsafe { self.ptr.as_ptr().add(RING_HEADER_BYTES + offset) }
    }

    /// Returns the shared data window `[offset, offset + len)`.
    pub(crate) fn data_ref(&self, offset: usize, len: usize) -> &[u8] {
        debug_assert!(offset + len <= self.capacity);
        // SAFETY: the window is inside the data area.
        unsafe { slice::from_raw_parts(self.ptr.as_ptr().add(RING_HEADER_BYTES).add(offset), len) }
    }

    pub(crate) fn capacity(&self) -> usize {
        self.capacity
    }

    pub(crate) fn total_bytes(&self) -> usize {
        self.layout.size()
    }

    pub(crate) fn base_address(&self) -> usize {
        self.ptr.as_ptr() as usize
    }

    /// Validates the region against the layout contract.
    pub(crate) fn validate(&self, expected_capacity: usize) -> Result<(), SharedIoError> {
        if !self.base_address().is_multiple_of(RING_ALIGNMENT) {
            return Err(SharedIoError::Misaligned(RING_ALIGNMENT));
        }
        if self.layout.size() < RING_HEADER_BYTES {
            return Err(SharedIoError::Truncated {
                found: self.layout.size(),
                minimum: RING_HEADER_BYTES,
            });
        }
        let magic = self.atomic(OFF_MAGIC).load(Ordering::Relaxed);
        if magic != RING_MAGIC {
            return Err(SharedIoError::InvalidMagic { found: magic });
        }
        let version = self.atomic(OFF_LAYOUT_VERSION).load(Ordering::Relaxed);
        if version != layout_version() {
            return Err(SharedIoError::UnsupportedLayoutVersion {
                found: version,
                expected: layout_version(),
            });
        }
        let capacity = self.atomic(OFF_CAPACITY).load(Ordering::Relaxed) as usize;
        if capacity != expected_capacity {
            return Err(SharedIoError::CapacityMismatch {
                found: capacity,
                expected: expected_capacity,
            });
        }
        Ok(())
    }
}

impl Drop for SharedRegion {
    fn drop(&mut self) {
        // SAFETY: `ptr` came from `alloc` with exactly `layout` and is freed once.
        unsafe { dealloc(self.ptr.as_ptr(), self.layout) };
    }
}

#[cfg(all(test, not(loom)))]
mod tests {
    use super::*;
    use crate::layout::RING_MIN_CAPACITY;

    #[test]
    fn region_is_aligned_and_sized() {
        let region = SharedRegion::allocate(RING_MIN_CAPACITY).unwrap();
        assert_eq!(region.base_address() % RING_ALIGNMENT, 0);
        assert_eq!(region.total_bytes(), RING_HEADER_BYTES + RING_MIN_CAPACITY);
        assert_eq!(region.capacity(), RING_MIN_CAPACITY);
    }

    #[test]
    fn validate_accepts_fresh_region() {
        let region = SharedRegion::allocate(RING_MIN_CAPACITY).unwrap();
        region.validate(RING_MIN_CAPACITY).unwrap();
    }

    #[test]
    fn validate_rejects_wrong_expected_capacity() {
        let region = SharedRegion::allocate(RING_MIN_CAPACITY).unwrap();
        let err = region.validate(RING_MIN_CAPACITY * 2).unwrap_err();
        assert!(matches!(err, SharedIoError::CapacityMismatch { .. }));
    }

    #[test]
    fn validate_rejects_corrupt_magic() {
        let region = SharedRegion::allocate(RING_MIN_CAPACITY).unwrap();
        region
            .atomic(OFF_MAGIC)
            .store(0xDEAD_BEEF, Ordering::Relaxed);
        let err = region.validate(RING_MIN_CAPACITY).unwrap_err();
        assert!(matches!(err, SharedIoError::InvalidMagic { .. }));
    }

    #[test]
    fn validate_rejects_wrong_layout() {
        let region = SharedRegion::allocate(RING_MIN_CAPACITY).unwrap();
        region
            .atomic(OFF_LAYOUT_VERSION)
            .store(layout_version() + 1, Ordering::Relaxed);
        let err = region.validate(RING_MIN_CAPACITY).unwrap_err();
        assert!(matches!(
            err,
            SharedIoError::UnsupportedLayoutVersion { .. }
        ));
    }

    #[test]
    fn data_window_is_inside_allocation() {
        let region = SharedRegion::allocate(64).unwrap();
        // SAFETY: this test has exclusive access to the freshly allocated region.
        let window = unsafe { std::slice::from_raw_parts_mut(region.data_ptr_at(0), 64) };
        window.fill(7);
        assert_eq!(region.data_ref(0, 64), &[7u8; 64]);
    }
}
