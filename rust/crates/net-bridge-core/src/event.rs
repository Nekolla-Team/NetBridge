//! Event system and EventSink trait.

pub const NB_EVENT_CONNECTION_STATE: u32 = 1;
pub const NB_EVENT_DATA_AVAILABLE: u32 = 2;
pub const NB_EVENT_WRITABLE: u32 = 3;
pub const NB_EVENT_ACCEPTED: u32 = 4;
pub const NB_EVENT_SERVER_STATE: u32 = 5;

pub const NB_SERVER_STATE_RUNNING: u32 = 1;
pub const NB_SERVER_STATE_STOPPED: u32 = 2;
pub const NB_SERVER_STATE_FAILED: u32 = 3;

pub const NB_REASON_GENERIC: i64 = 0;
pub const NB_REASON_DNS: i64 = 1;
pub const NB_REASON_SETUP: i64 = 2;
pub const NB_REASON_REFUSED: i64 = 3;
pub const NB_REASON_TIMEOUT: i64 = 4;
pub const NB_REASON_PROTOCOL: i64 = 5;
pub const NB_REASON_CANCELLED: i64 = 6;
pub const NB_REASON_INTERNAL: i64 = 7;

/// Trait for event callback receivers.
pub trait EventSink: Send + Sync + 'static {
    fn on_event(&self, event_kind: u32, object_id: u64, arg0: i64, arg1: i64);
}

/// Default no-op EventSink.
pub struct NoopEventSink;

impl EventSink for NoopEventSink {
    fn on_event(&self, _event_kind: u32, _object_id: u64, _arg0: i64, _arg1: i64) {}
}

/// Maps core-internal connection-state constants (0=CONNECTING..3=FAILED) to ABI state values (1..4).
pub fn abi_connection_state(internal: u32) -> u32 {
    internal + 1
}
