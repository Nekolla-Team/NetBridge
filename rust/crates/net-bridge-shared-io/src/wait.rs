//! Shared wait-state protocol used to make sleep/wake edge-triggered and lost-wakeup free.
//!
//! Each ring direction has one wait state owned by the side that can sleep:
//!
//! - `producer_wait_state`: the producer parks here when the ring is full and a free slot is
//!   published by the consumer.
//! - `consumer_wait_state`: the consumer parks here when the ring is empty and data is published
//!   by the producer.
//!
//! The state machine is single-waiter: only the parking side writes `PARKED`/`ACTIVE`, while the
//! peer performs the `PARKED -> NOTIFIED` transition with a CAS. The parking side stores `PARKED`
//! *before* re-checking the ring, and both sides place a `SeqCst` fence between their data store and
//! their wait-state read. This is the classic store-load (Dekker) pattern: the two sides can never
//! both miss each other, so a peer that observes a non-parked waiter is guaranteed that the waiter's
//! re-check will observe the published data. Wakeups therefore never disappear.

use crate::atomic::{AtomicU64, Ordering, fence};

/// The owning side is awake and may publish/consume without a wakeup.
pub const WAIT_ACTIVE: u64 = 0;
/// The owning side armed a park and is about to sleep.
pub const WAIT_PARKED: u64 = 1;
/// The peer observed a parked owner and requested a wakeup.
pub const WAIT_NOTIFIED: u64 = 2;

/// A single wait-state slot. `#[repr(transparent)]` so a header `AtomicU64` can be viewed as a
/// `WaitState` without moving the value.
#[repr(transparent)]
#[derive(Debug)]
pub struct WaitState(AtomicU64);

impl WaitState {
    /// Creates a standalone wait state (used by tests and by the region initializer).
    #[must_use]
    pub fn new(initial: u64) -> Self {
        Self(AtomicU64::new(initial))
    }

    /// Views a raw atomic as a wait state.
    ///
    /// # Safety
    ///
    /// `atomic` must point at a live `AtomicU64` that is never accessed through any other type
    /// while the returned reference is alive.
    pub(crate) unsafe fn from_atomic(atomic: &AtomicU64) -> &Self {
        // SAFETY: `WaitState` is `#[repr(transparent)]` over `AtomicU64`.
        unsafe { &*(atomic as *const AtomicU64).cast::<Self>() }
    }

    /// Returns the currently encoded wait state.
    #[must_use]
    pub fn load(&self) -> u64 {
        self.0.load(Ordering::Acquire)
    }

    /// Publishes `PARKED` and re-checks whether the owner still needs to sleep.
    ///
    /// `still_waiting` must perform an acquire load of the peer cursor. Returns `true` when the
    /// caller must actually sleep; `false` when the condition already cleared and the park was
    /// cancelled.
    ///
    /// The `SeqCst` fence after the `PARKED` store pairs with the fence in
    /// [`WaitState::notify_if_parked`] to prevent a lost wakeup (see module docs).
    pub fn park<F>(&self, still_waiting: F) -> bool
    where
        F: FnOnce() -> bool,
    {
        self.0.store(WAIT_PARKED, Ordering::Release);
        fence(Ordering::SeqCst);
        if still_waiting() {
            true
        } else {
            self.0.store(WAIT_ACTIVE, Ordering::Release);
            false
        }
    }

    /// Returns a parked waiter to the active state after it wakes up.
    pub fn resume(&self) {
        let _ = self.0.compare_exchange(
            WAIT_NOTIFIED,
            WAIT_ACTIVE,
            Ordering::AcqRel,
            Ordering::Relaxed,
        );
    }

    /// Requests exactly one wakeup if the owner is parked.
    ///
    /// Returns `true` when this call observed `PARKED` and claimed the wakeup; the caller must then
    /// issue the actual event (a `Notify`, `unpark`, or equivalent). The `SeqCst` fence orders the
    /// caller's prior data publication before the read of the wait state, which is what closes the
    /// lost-wakeup race.
    pub fn notify_if_parked(&self) -> bool {
        fence(Ordering::SeqCst);
        self.0
            .compare_exchange(
                WAIT_PARKED,
                WAIT_NOTIFIED,
                Ordering::AcqRel,
                Ordering::Relaxed,
            )
            .is_ok()
    }
}

#[cfg(all(test, not(loom)))]
mod tests {
    use super::*;
    use std::cell::Cell;

    #[test]
    fn initial_states_are_active() {
        assert_eq!(WaitState::new(WAIT_ACTIVE).load(), WAIT_ACTIVE);
        assert_eq!(WaitState::new(WAIT_PARKED).load(), WAIT_PARKED);
    }

    #[test]
    fn notify_without_parked_waiter_is_rejected() {
        let wait = WaitState::new(WAIT_ACTIVE);
        assert!(!wait.notify_if_parked());
        assert_eq!(wait.load(), WAIT_ACTIVE);
    }

    #[test]
    fn parked_waiter_is_notified_once() {
        let wait = WaitState::new(WAIT_ACTIVE);
        assert!(wait.park(|| true));
        assert_eq!(wait.load(), WAIT_PARKED);

        assert!(wait.notify_if_parked());
        assert_eq!(wait.load(), WAIT_NOTIFIED);
        // A second commit must not re-signal the same sleep cycle.
        assert!(!wait.notify_if_parked());
        assert_eq!(wait.load(), WAIT_NOTIFIED);
    }

    #[test]
    fn park_cancels_when_condition_already_cleared() {
        let wait = WaitState::new(WAIT_ACTIVE);
        assert!(!wait.park(|| false));
        assert_eq!(wait.load(), WAIT_ACTIVE);
        // With no parked waiter there is nothing to notify.
        assert!(!wait.notify_if_parked());
    }

    #[test]
    fn resume_rearms_after_notification() {
        let wait = WaitState::new(WAIT_ACTIVE);
        assert!(wait.park(|| true));
        assert!(wait.notify_if_parked());
        wait.resume();
        assert_eq!(wait.load(), WAIT_ACTIVE);

        // The next park can be notified again.
        assert!(wait.park(|| true));
        assert!(wait.notify_if_parked());
    }

    #[test]
    fn interleaving_data_committed_before_park_cancels_sleep() {
        // Case A from the design: the peer committed before the owner stored PARKED. The peer's
        // notify fails, but the re-check must observe the data and cancel the park.
        let wait = WaitState::new(WAIT_ACTIVE);
        let committed = Cell::new(true);
        let slept = wait.park(|| !committed.get());
        assert!(!slept);
        assert_eq!(wait.load(), WAIT_ACTIVE);
    }

    #[test]
    fn interleaving_park_then_commit_wakes() {
        // Case B: the owner parked first; the peer must be able to claim the wakeup exactly once.
        let wait = WaitState::new(WAIT_ACTIVE);
        let committed = Cell::new(false);
        let slept = wait.park(|| !committed.get());
        assert!(slept);
        committed.set(true);
        assert!(wait.notify_if_parked());
        wait.resume();
        assert_eq!(wait.load(), WAIT_ACTIVE);
    }

    #[test]
    fn interleaving_recheck_before_await_still_wakes() {
        // Case C: the peer commits and signals before the owner actually blocks. The notification is
        // latched (NOTIFIED) so the owner's eventual await consumes it immediately.
        let wait = WaitState::new(WAIT_ACTIVE);
        assert!(wait.park(|| true));
        assert!(wait.notify_if_parked());
        // Simulate the latched signal being consumed on await.
        wait.resume();
        assert_eq!(wait.load(), WAIT_ACTIVE);

        // Even if the owner had not consumed it yet, a re-park overwrites NOTIFIED with PARKED and a
        // fresh re-check decides again.
        let wait = WaitState::new(WAIT_ACTIVE);
        assert!(wait.park(|| true));
        assert!(wait.notify_if_parked());
        assert_eq!(wait.load(), WAIT_NOTIFIED);
    }
}

#[cfg(all(test, loom))]
mod loom_tests {
    use super::*;
    use loom::sync::Arc;
    use loom::sync::atomic::{AtomicBool, Ordering};
    use loom::thread;

    /// Consumer side of the empty-ring wake protocol.
    ///
    /// The consumer arms a park and re-checks `data_ready`. If it decides to sleep, the producer
    /// must have claimed the wakeup, so a latched `permit` is guaranteed to exist. Any interleaving
    /// where the consumer sleeps without a permit is a lost wakeup.
    #[test]
    fn consumer_sleep_implies_pending_wakeup() {
        loom::model(|| {
            let data_ready = Arc::new(AtomicBool::new(false));
            let consumer_wait = Arc::new(WaitState::new(WAIT_ACTIVE));
            let permit = Arc::new(AtomicBool::new(false));
            let slept = Arc::new(AtomicBool::new(false));

            let producer = {
                let data_ready = Arc::clone(&data_ready);
                let consumer_wait = Arc::clone(&consumer_wait);
                let permit = Arc::clone(&permit);
                thread::spawn(move || {
                    data_ready.store(true, Ordering::Release);
                    if consumer_wait.notify_if_parked() {
                        permit.store(true, Ordering::Release);
                    }
                })
            };

            let consumer = {
                let data_ready = Arc::clone(&data_ready);
                let consumer_wait = Arc::clone(&consumer_wait);
                let permit = Arc::clone(&permit);
                let slept = Arc::clone(&slept);
                thread::spawn(move || {
                    let did_sleep = consumer_wait.park(|| !data_ready.load(Ordering::Acquire));
                    if did_sleep {
                        consumer_wait.resume();
                    }
                    slept.store(did_sleep, Ordering::Release);
                })
            };

            producer.join().unwrap();
            consumer.join().unwrap();

            if slept.load(Ordering::Acquire) {
                assert!(
                    permit.load(Ordering::Acquire),
                    "consumer slept without a pending wakeup (lost wake)"
                );
            }
        });
    }

    /// Producer side of the full-ring wake protocol, symmetric to the consumer side.
    #[test]
    fn producer_sleep_implies_pending_wakeup() {
        loom::model(|| {
            let space_available = Arc::new(AtomicBool::new(false));
            let producer_wait = Arc::new(WaitState::new(WAIT_ACTIVE));
            let permit = Arc::new(AtomicBool::new(false));
            let slept = Arc::new(AtomicBool::new(false));

            let consumer = {
                let space_available = Arc::clone(&space_available);
                let producer_wait = Arc::clone(&producer_wait);
                let permit = Arc::clone(&permit);
                thread::spawn(move || {
                    space_available.store(true, Ordering::Release);
                    if producer_wait.notify_if_parked() {
                        permit.store(true, Ordering::Release);
                    }
                })
            };

            let producer = {
                let space_available = Arc::clone(&space_available);
                let producer_wait = Arc::clone(&producer_wait);
                let permit = Arc::clone(&permit);
                let slept = Arc::clone(&slept);
                thread::spawn(move || {
                    let did_sleep = producer_wait.park(|| !space_available.load(Ordering::Acquire));
                    if did_sleep {
                        producer_wait.resume();
                    }
                    slept.store(did_sleep, Ordering::Release);
                })
            };

            consumer.join().unwrap();
            producer.join().unwrap();

            if slept.load(Ordering::Acquire) {
                assert!(
                    permit.load(Ordering::Acquire),
                    "producer slept without a pending wakeup (lost wake)"
                );
            }
        });
    }
}
