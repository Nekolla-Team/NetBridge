//! Byte layout of a NetBridge shared ring region.
//!
//! A region is a single Rust-owned allocation of `RING_HEADER_BYTES + capacity` bytes, aligned to
//! [`RING_ALIGNMENT`]. The header uses `AtomicU64` slots; the immutable fields are written once
//! before the region is published and are only read afterwards. `tail` and `head` live on separate
//! cache lines to avoid producer/consumer false sharing.
//!
//! ```text
//! offset   bytes   field
//! ---------------------------------------------------------------
//! 0        8       magic                 (immutable)
//! 8        8       layout_version        (immutable)
//! 16       8       capacity              (immutable)
//! 24       8       producer_wait_state   AtomicU64
//! 32       8       consumer_wait_state   AtomicU64
//! 40       8       reserved0
//! 48       8       reserved1
//! 56       8       reserved2
//! ---------------------------------------------------------------
//! 64       8       tail                  AtomicU64
//! 72..127  56      padding
//! ---------------------------------------------------------------
//! 128      8       head                  AtomicU64
//! 136..191 56      padding
//! ---------------------------------------------------------------
//! 192..            capacity data bytes
//! ```

use crate::error::SharedIoError;

/// Region magic: little-endian ASCII `NBRING01`.
pub const RING_MAGIC: u64 = u64::from_le_bytes(*b"NBRING01");
/// Layout major version.
pub const RING_LAYOUT_MAJOR: u64 = 1;
/// Layout minor version.
pub const RING_LAYOUT_MINOR: u64 = 0;
/// Header size in bytes; data starts immediately after it.
pub const RING_HEADER_BYTES: usize = 192;
/// Required base alignment for a region.
pub const RING_ALIGNMENT: usize = 128;
/// Smallest capacity the production data plane accepts.
pub const RING_MIN_CAPACITY: usize = 64 * 1024;
/// Largest capacity this primitive will allocate (sanity bound).
pub const RING_MAX_CAPACITY: usize = 1 << 40;

/// Offset of the immutable magic field.
pub const OFF_MAGIC: usize = 0;
/// Offset of the immutable layout version field.
pub const OFF_LAYOUT_VERSION: usize = 8;
/// Offset of the immutable capacity field.
pub const OFF_CAPACITY: usize = 16;
/// Offset of the producer wait state.
pub const OFF_PRODUCER_WAIT: usize = 24;
/// Offset of the consumer wait state.
pub const OFF_CONSUMER_WAIT: usize = 32;
/// Offset of reserved header slot 0.
pub const OFF_RESERVED0: usize = 40;
/// Offset of reserved header slot 1.
pub const OFF_RESERVED1: usize = 48;
/// Offset of reserved header slot 2.
pub const OFF_RESERVED2: usize = 56;
/// Offset of the producer cursor.
pub const OFF_TAIL: usize = 64;
/// Offset of the consumer cursor.
pub const OFF_HEAD: usize = 128;
/// Offset at which the data bytes begin.
pub const OFF_DATA: usize = RING_HEADER_BYTES;

/// Encoded layout version (`major << 32 | minor`).
#[must_use]
pub const fn layout_version() -> u64 {
    (RING_LAYOUT_MAJOR << 32) | RING_LAYOUT_MINOR
}

/// Returns `true` when `capacity` is a power of two within the supported range.
#[must_use]
pub fn is_supported_capacity(capacity: usize) -> bool {
    capacity.is_power_of_two() && (RING_MIN_CAPACITY..=RING_MAX_CAPACITY).contains(&capacity)
}

/// Validates a capacity against the production constraints.
pub fn validate_capacity(capacity: usize) -> Result<(), SharedIoError> {
    if !capacity.is_power_of_two() {
        return Err(SharedIoError::CapacityNotPowerOfTwo(capacity));
    }
    if capacity < RING_MIN_CAPACITY {
        return Err(SharedIoError::CapacityTooSmall {
            requested: capacity,
            minimum: RING_MIN_CAPACITY,
        });
    }
    if capacity > RING_MAX_CAPACITY {
        return Err(SharedIoError::CapacityTooLarge(capacity));
    }
    Ok(())
}

/// Validates a capacity for the raw allocator used by in-crate tests, which tolerates small but
/// still power-of-two rings.
#[cfg(test)]
pub(crate) fn validate_raw_capacity(capacity: usize) -> Result<(), SharedIoError> {
    if !capacity.is_power_of_two() {
        return Err(SharedIoError::CapacityNotPowerOfTwo(capacity));
    }
    if capacity < 64 {
        return Err(SharedIoError::CapacityTooSmall {
            requested: capacity,
            minimum: 64,
        });
    }
    if capacity > RING_MAX_CAPACITY {
        return Err(SharedIoError::CapacityTooLarge(capacity));
    }
    Ok(())
}

#[cfg(all(test, not(loom)))]
mod tests {
    use super::*;

    #[test]
    fn offsets_are_ordered_and_within_header() {
        let offsets = [
            OFF_MAGIC,
            OFF_LAYOUT_VERSION,
            OFF_CAPACITY,
            OFF_PRODUCER_WAIT,
            OFF_CONSUMER_WAIT,
            OFF_RESERVED0,
            OFF_RESERVED1,
            OFF_RESERVED2,
            OFF_TAIL,
            OFF_HEAD,
        ];
        assert!(offsets.windows(2).all(|w| w[0] < w[1]));
        assert!(offsets.iter().all(|&off| off + 8 <= RING_HEADER_BYTES));
        assert_eq!(OFF_DATA, RING_HEADER_BYTES);
        assert_eq!(OFF_TAIL % 64, 0);
        assert_eq!(OFF_HEAD % 64, 0);
        assert_ne!(OFF_TAIL / 64, OFF_HEAD / 64);
    }

    #[test]
    fn version_packs_major_and_minor() {
        assert_eq!(layout_version() >> 32, RING_LAYOUT_MAJOR);
        assert_eq!(layout_version() & 0xFFFF_FFFF, RING_LAYOUT_MINOR);
    }

    #[test]
    fn capacity_predicate_matches_validation() {
        for capacity in [64usize, 1024, RING_MIN_CAPACITY, RING_MIN_CAPACITY * 2] {
            assert_eq!(
                is_supported_capacity(capacity),
                validate_capacity(capacity).is_ok(),
                "capacity {capacity}"
            );
        }
    }

    #[test]
    fn raw_capacity_rejects_tiny_and_non_power_of_two() {
        assert!(validate_raw_capacity(0).is_err());
        assert!(validate_raw_capacity(63).is_err());
        assert!(validate_raw_capacity(96).is_err());
        assert!(validate_raw_capacity(64).is_ok());
    }
}
