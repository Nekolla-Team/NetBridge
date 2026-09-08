//! KCP transport using kcp-rs + FECStream + smux.

pub mod client;
pub mod config;
pub mod connection;
pub mod fec_stream;
pub mod server;

pub use client::connect_in_context;
pub use config::{KcpProfile, build_config};
pub use server::start_server_in_context;

#[cfg(test)]
mod tests;
