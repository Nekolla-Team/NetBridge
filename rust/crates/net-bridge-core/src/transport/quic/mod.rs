//! QUIC transport implementation using quinn-plaintext: client, server acceptor, and single-connection
//! data plane.

mod client;
pub(crate) mod connection;
pub(crate) mod server;

pub use client::connect_in_context;
pub use server::start_server_in_context;

#[cfg(test)]
mod tests;
