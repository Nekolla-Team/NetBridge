//! C callback adapter forwarding net-bridge-core EventSink events to a C function pointer.

use super::types::NbEventCallbackV1;
use net_bridge_core::EventSink;

pub struct CAbiEventSink {
    callback: NbEventCallbackV1,
}

impl CAbiEventSink {
    pub fn new(callback: NbEventCallbackV1) -> Self {
        Self { callback }
    }
}

impl EventSink for CAbiEventSink {
    fn on_event(&self, event_kind: u32, object_id: u64, arg0: i64, arg1: i64) {
        if let Some(cb) = self.callback {
            unsafe {
                cb(event_kind, object_id, arg0, arg1);
            }
        }
    }
}
