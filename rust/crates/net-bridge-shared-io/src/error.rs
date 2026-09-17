//! Error type for the shared-io ring primitive.

/// Errors produced while validating, allocating, or operating a shared ring.
#[derive(Debug, thiserror::Error)]
pub enum SharedIoError {
    /// The requested capacity is not a power of two.
    #[error("shared ring capacity {0} is not a power of two")]
    CapacityNotPowerOfTwo(usize),
    /// The requested capacity is below the supported minimum.
    #[error("shared ring capacity {requested} is below the minimum {minimum}")]
    CapacityTooSmall {
        /// Requested capacity in bytes.
        requested: usize,
        /// Smallest accepted capacity in bytes.
        minimum: usize,
    },
    /// The requested capacity is above the supported maximum.
    #[error("shared ring capacity {0} is above the supported maximum")]
    CapacityTooLarge(usize),
    /// `header + capacity` overflowed `usize`.
    #[error("shared ring region size overflows the address space")]
    SizeOverflow,
    /// The backing allocation failed.
    #[error("shared ring backing allocation failed")]
    AllocationFailed,
    /// The region header does not carry the expected magic.
    #[error("shared ring magic mismatch: found {found:#018x}")]
    InvalidMagic {
        /// Magic value read from the header.
        found: u64,
    },
    /// The region header carries an unsupported layout version.
    #[error("unsupported shared ring layout version {found:#018x} (expected {expected:#018x})")]
    UnsupportedLayoutVersion {
        /// Layout version read from the header.
        found: u64,
        /// Layout version compiled into this build.
        expected: u64,
    },
    /// The header capacity disagrees with the expected capacity.
    #[error("shared ring capacity mismatch: header {found}, expected {expected}")]
    CapacityMismatch {
        /// Capacity recorded in the header.
        found: usize,
        /// Capacity the caller expected.
        expected: usize,
    },
    /// The region base address is not aligned to the ring alignment.
    #[error("shared ring region is not {0}-byte aligned")]
    Misaligned(usize),
    /// The region is smaller than the header requires.
    #[error("shared ring region is truncated: {found} bytes, need at least {minimum}")]
    Truncated {
        /// Reported region size in bytes.
        found: usize,
        /// Smallest valid region size in bytes.
        minimum: usize,
    },
    /// A commit was asked to publish more bytes than the grant covers.
    #[error("write/read grant of {granted} bytes cannot commit {committed} bytes")]
    CommitOutOfRange {
        /// Bytes covered by the grant.
        granted: usize,
        /// Bytes the caller tried to commit.
        committed: usize,
    },
    /// The ring is already full and cannot accept more data right now.
    #[error("shared ring is full")]
    Full,
    /// The ring is already empty and has no data to read right now.
    #[error("shared ring is empty")]
    Empty,
}
