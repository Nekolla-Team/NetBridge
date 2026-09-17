//! Two-thread SPSC correctness: a real producer/consumer pair exchanging a framed byte stream.
//!
//! The ring is deliberately small (the 64 KiB production minimum) so the producers and consumers
//! repeatedly hit the full/empty edges and exercise the wait protocol, while the payload carries a
//! sequence number and checksum so any loss, duplicate, reorder, or corruption fails the test.

#![cfg(not(loom))]

use std::sync::{Arc, OnceLock};
use std::thread::{self, Thread};
use std::time::{Duration, Instant};

use net_bridge_shared_io::{RingConsumer, RingProducer, SharedRing};

const CAPACITY: usize = 64 * 1024;
#[cfg(not(miri))]
const MESSAGES: usize = 20_000;
#[cfg(miri)]
const MESSAGES: usize = 64;
#[cfg(not(miri))]
const MAX_PAYLOAD: usize = 256;
#[cfg(miri)]
const MAX_PAYLOAD: usize = 2048;
#[cfg(not(miri))]
const DEADLINE: Duration = Duration::from_secs(60);
#[cfg(miri)]
const DEADLINE: Duration = Duration::from_secs(900);

fn checksum(seq: u64, payload: &[u8]) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325u64 ^ seq;
    for &b in payload {
        hash ^= u64::from(b);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

fn payload_for(seq: u64, len: usize) -> Vec<u8> {
    (0..len)
        .map(|i| (seq as u8).wrapping_mul(31).wrapping_add(i as u8))
        .collect()
}

fn build_wire() -> Vec<u8> {
    let mut wire = Vec::new();
    for seq in 0..MESSAGES as u64 {
        let len = (seq as usize) % (MAX_PAYLOAD + 1);
        let payload = payload_for(seq, len);
        wire.extend_from_slice(&(len as u32).to_be_bytes());
        wire.extend_from_slice(&seq.to_be_bytes());
        wire.extend_from_slice(&payload);
        wire.extend_from_slice(&checksum(seq, &payload).to_be_bytes());
    }
    wire
}

fn wake(peer: &OnceLock<Thread>) {
    if let Some(thread) = peer.get() {
        thread.unpark();
    }
}

fn write_all(producer: &mut RingProducer, data: &[u8], consumer: &OnceLock<Thread>) {
    let deadline = Instant::now() + DEADLINE;
    let mut offset = 0;
    while offset < data.len() {
        assert!(Instant::now() < deadline, "producer timed out");
        let (n, wake_consumer) = producer.copy_from(&data[offset..]);
        offset += n;
        if wake_consumer {
            wake(consumer);
        }
        if n != 0 {
            if offset % 4096 < n {
                thread::yield_now();
            }
            continue;
        }
        if producer.park_if_full() {
            thread::park_timeout(Duration::from_millis(5));
            producer.resume_after_notification();
        }
    }
}

fn read_exact(consumer: &mut RingConsumer, dst: &mut [u8], producer: &OnceLock<Thread>) {
    let deadline = Instant::now() + DEADLINE;
    let mut offset = 0;
    while offset < dst.len() {
        assert!(Instant::now() < deadline, "consumer timed out");
        let (n, wake_producer) = consumer.copy_into(&mut dst[offset..]);
        offset += n;
        if wake_producer {
            wake(producer);
        }
        if n != 0 {
            continue;
        }
        if consumer.park_if_empty() {
            thread::park_timeout(Duration::from_millis(5));
            consumer.resume_after_notification();
        }
    }
}

fn verify_stream(consumer: &mut RingConsumer, producer: &OnceLock<Thread>) {
    for seq in 0..MESSAGES as u64 {
        let mut header = [0u8; 12];
        read_exact(consumer, &mut header, producer);
        let len = u32::from_be_bytes(header[0..4].try_into().unwrap()) as usize;
        let got_seq = u64::from_be_bytes(header[4..12].try_into().unwrap());
        assert_eq!(got_seq, seq, "sequence number mismatch");
        assert_eq!(len, (seq as usize) % (MAX_PAYLOAD + 1), "length mismatch");

        let mut payload = vec![0u8; len];
        read_exact(consumer, &mut payload, producer);
        assert_eq!(
            payload,
            payload_for(seq, len),
            "payload corruption at seq {seq}"
        );

        let mut trailer = [0u8; 8];
        read_exact(consumer, &mut trailer, producer);
        let got = u64::from_be_bytes(trailer);
        assert_eq!(
            got,
            checksum(seq, &payload),
            "checksum mismatch at seq {seq}"
        );
    }
}

fn run_pair(
    ring: Arc<SharedRing>,
    wire: Vec<u8>,
) -> (thread::JoinHandle<()>, thread::JoinHandle<()>) {
    let (mut producer, mut consumer) = ring.split();
    let producer_thread = Arc::new(OnceLock::<Thread>::new());
    let consumer_thread = Arc::new(OnceLock::<Thread>::new());

    let pt = Arc::clone(&producer_thread);
    let ct = Arc::clone(&consumer_thread);
    let producer_handle = thread::spawn(move || {
        pt.set(thread::current()).ok();
        while ct.get().is_none() {
            thread::yield_now();
        }
        write_all(&mut producer, &wire, &ct);
    });

    let pt = Arc::clone(&producer_thread);
    let ct = Arc::clone(&consumer_thread);
    let consumer_handle = thread::spawn(move || {
        ct.set(thread::current()).ok();
        while pt.get().is_none() {
            thread::yield_now();
        }
        verify_stream(&mut consumer, &pt);
    });

    (producer_handle, consumer_handle)
}

#[test]
fn single_pair_streams_variable_sized_frames() {
    let ring = SharedRing::allocate(CAPACITY).unwrap();
    let wire = build_wire();
    let (producer, consumer) = run_pair(ring, wire);
    producer.join().expect("producer panicked");
    consumer.join().expect("consumer panicked");
}

#[test]
fn repeated_pairs_are_independent() {
    for _ in 0..4 {
        let ring = SharedRing::allocate(CAPACITY).unwrap();
        let wire = build_wire();
        let (producer, consumer) = run_pair(ring, wire);
        producer.join().expect("producer panicked");
        consumer.join().expect("consumer panicked");
    }
}

#[test]
fn duplex_pairs_do_not_interfere() {
    let forward = SharedRing::allocate(CAPACITY).unwrap();
    let reverse = SharedRing::allocate(CAPACITY).unwrap();
    let wire = build_wire();

    let (f_producer, f_consumer) = run_pair(forward, wire.clone());
    let (r_producer, r_consumer) = run_pair(reverse, wire);

    f_producer.join().expect("forward producer panicked");
    f_consumer.join().expect("forward consumer panicked");
    r_producer.join().expect("reverse producer panicked");
    r_consumer.join().expect("reverse consumer panicked");
}
