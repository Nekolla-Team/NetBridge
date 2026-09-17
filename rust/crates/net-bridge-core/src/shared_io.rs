//! Per-connection shared-memory data plane.
//!
//! A connection owns two SPSC rings: `tx` carries Java -> transport bytes and `rx` carries
//! transport -> Java bytes. The transport driver owns the consuming half of `tx` and the producing
//! half of `rx`; the legacy C ABI shim owns the remaining halves behind a mutex so the polled
//! `connection_write`/`connection_read` entry points remain multi-thread safe.
//!
//! All raw memory access is confined to `net-bridge-shared-io`; this module only drives the safe
//! ring API. `net-bridge-core` therefore keeps `#![forbid(unsafe_code)]`.

use std::sync::{Arc, Mutex};

use net_bridge_shared_io::{RingConsumer, RingProducer, SharedRing};
use tokio::sync::Notify;

use crate::error::BridgeError;

/// Default capacity of the Java -> transport ring (128 KiB).
pub const DEFAULT_SHARED_IO_TX_CAPACITY: usize = 128 * 1024;
/// Default capacity of the transport -> Java ring (128 KiB).
pub const DEFAULT_SHARED_IO_RX_CAPACITY: usize = 128 * 1024;

/// Shared data-plane state for one connection.
pub struct SharedConnectionIo {
    /// Java -> transport ring. The published region is handed to Java in direct mode.
    pub tx: Arc<SharedRing>,
    /// Transport -> Java ring. The published region is handed to Java in direct mode.
    pub rx: Arc<SharedRing>,
    /// Legacy ABI producer half of `tx`.
    tx_producer: Mutex<RingProducer>,
    /// Legacy ABI consumer half of `rx`.
    rx_consumer: Mutex<RingConsumer>,
    /// Java -> Rust wakeup for the `tx` transport consumer.
    pub tx_data_notify: Notify,
    /// Java -> Rust wakeup for the `rx` transport producer.
    pub rx_space_notify: Notify,
}

/// The transport-owned halves of a connection's rings.
pub struct SharedIoDriver {
    /// Transport consumer half of `tx`.
    pub tx_consumer: RingConsumer,
    /// Transport producer half of `rx`.
    pub rx_producer: RingProducer,
}

impl SharedConnectionIo {
    /// Allocates both rings and splits each into its legacy and transport halves.
    pub fn create(
        tx_capacity: usize,
        rx_capacity: usize,
    ) -> Result<(Arc<Self>, SharedIoDriver), BridgeError> {
        let tx = SharedRing::allocate(tx_capacity).map_err(map_shared_io_error)?;
        let rx = SharedRing::allocate(rx_capacity).map_err(map_shared_io_error)?;
        let (tx_producer, tx_consumer) = tx.split();
        let (rx_producer, rx_consumer) = rx.split();
        let io = Arc::new(Self {
            tx,
            rx,
            tx_producer: Mutex::new(tx_producer),
            rx_consumer: Mutex::new(rx_consumer),
            tx_data_notify: Notify::new(),
            rx_space_notify: Notify::new(),
        });
        Ok((
            io,
            SharedIoDriver {
                tx_consumer,
                rx_producer,
            },
        ))
    }

    /// Legacy ABI write shim: copies as much of `data` as fits into the `tx` ring.
    ///
    /// Returns the number of bytes published. A zero return with non-empty input means the ring is
    /// full (WOULD_BLOCK). Arms the producer wait state so the transport consumer can edge-trigger
    /// a WRITABLE event once space is freed.
    pub fn write_legacy(&self, data: &[u8]) -> usize {
        if data.is_empty() {
            return 0;
        }
        let mut producer = self
            .tx_producer
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let (written, wake_consumer) = producer.copy_from(data);
        if written > 0 {
            producer.resume_after_notification();
        } else {
            let _ = producer.park_if_full();
        }
        drop(producer);
        if wake_consumer {
            self.tx_data_notify.notify_one();
        }
        written
    }

    /// Legacy ABI read shim: copies as many bytes as fit into `dst`.
    ///
    /// Returns the number of bytes consumed. A zero return with non-empty `dst` means the ring is
    /// empty (WOULD_BLOCK). Arms the consumer wait state so the transport producer can edge-trigger
    /// a DATA_AVAILABLE event once data arrives.
    pub fn read_legacy(&self, dst: &mut [u8]) -> usize {
        if dst.is_empty() {
            return 0;
        }
        let mut consumer = self
            .rx_consumer
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        loop {
            let mut total = 0usize;
            let mut wake_producer = false;
            while total < dst.len() {
                let (n, wake) = consumer.copy_into(&mut dst[total..]);
                if n == 0 {
                    break;
                }
                total += n;
                wake_producer |= wake;
            }
            if total > 0 {
                consumer.resume_after_notification();
                drop(consumer);
                if wake_producer {
                    self.rx_space_notify.notify_one();
                }
                return total;
            }
            // Empty: arm PARKED. A `false` return means data appeared after arming, so retry.
            if consumer.park_if_empty() {
                return 0;
            }
        }
    }
}

impl std::fmt::Debug for SharedConnectionIo {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SharedConnectionIo")
            .field("tx_capacity", &self.tx.capacity())
            .field("rx_capacity", &self.rx.capacity())
            .finish()
    }
}

fn map_shared_io_error(err: net_bridge_shared_io::SharedIoError) -> BridgeError {
    BridgeError::Internal(format!("shared io ring allocation failed: {err}"))
}
