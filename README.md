<div align="center">

# net-bridge

<p align="center">
  <strong>A Minecraft mod that transports your game traffic over better network protocol</strong>
</p>

[![License](https://img.shields.io/badge/license-LGPL--v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-linux%20%7C%20windows%20%7C%20macos-lightgrey.svg)]()

</div>

English | [简体中文](README.zh-CN.md)

---

## How it works?

The mod took over Minecraft's server connection stream and used QUIC-Plaintext (or KCP with FEC) to
transport data streams. The transport core is written in Rust🦀 and exposed to Java 25 through a C
ABI (`netbridge_get_api`) called via the FFM API.

The server announces its accelerated transports in the ping response under a top-level `networks`
object, one entry per transport:

```json
"networks": {
  "quic": {
    "enable": true,
    "port": 25565,
    "protocol": "net-bri-quic/1"
  },
  "kcp": {
    "enable": true,
    "port": 25566,
    "protocol": "net-bri-kcp/1"
  }
}
```

You will see `[QUIC/KCP]` at the end of its description, if server also installs the mod. The client
picks QUIC or KCP from the transport button in the multiplayer screen.

> If two handshake attempts fail, it falls back to TCP automatically for that connection. Successful
> accelerated endpoints are remembered for 5 minutes to skip re-negotiation on reconnect.

### Why disable QUIC's default encryption?

Minecraft already has its own encrypted streams, and this mod just wants to take advantage of QUIC's
reliable transmission features. Additional cryptographic handshakes cause additional overhead, which
is unnecessary.

## Configuration

Server: `config/net-bridge/server.toml`

```toml
[quic]
enable = true
# -1 = follow the Minecraft TCP port; 0 = random; otherwise a fixed port
port = -1
bind = ""            # empty = follow server.properties server-ip
host = ""            # advertised address; empty = follow the server address
max_connection = 256 # excess connections are silently dropped

[kcp]
enable = true        # Disable by defualt
port = -1            # follows Minecraft TCP port + 1
bind = ""            # empty = follow server.properties server-ip
host = ""            # advertised address; empty = follow the server address
max_connection = 256
profile = "balance"  # or "aggressive"
```

Client: `config/net-bridge/client.toml`

```toml
mode = "tcp"        # tcp / quic / kcp

[kcp]
profile = "balance" # balance / aggressive
```

System property `-Dnetbridge.transport=tcp|quic|kcp` overrides `mode`.

## Building

Requirements:

| Dependency | Notes                                                                                                                     |
|------------|---------------------------------------------------------------------------------------------------------------------------|
| Rust       | via [rustup](https://rustup.rs/)                                                                                          |
| JDK 25     | Hard requirement: the native transport uses the Java FFM API. Gradle runs through the `./gradlew` wrapper (JDK from PATH) |

Build in release mode:

```bash
make build
```

### Runtime requirement

The game (and any dedicated server) must run on Java 25 with FFM enabled:

```text
--enable-native-access=ALL-UNNAMED
```

### Verification & development

```bash
./gradlew test                                  # pure Java tests (no native required)
cargo test --workspace                          # Rust core/native tests
./gradlew :common:nativeIntegrationTest         # FFM integration tests (QUIC/KCP loopback)
./gradlew :common:ffmBenchmark                  # FFM data-plane benchmark
./gradlew verifyArchitecture verifyNativeSymbols generateNativeManifest
```

The public C ABI header (`rust/crates/net-bridge-native/include/netbridge.h`) is **generated** from
the Rust ABI definitions with pinned cbindgen and checked in. It must never be hand-edited:

```bash
./gradlew updateNativeHeader                     # regenerate after Rust ABI changes
./gradlew verifyNativeHeader                     # fail if the checked-in header is stale
```

or, from `rust/`: `cargo xtask abi-header update` / `cargo xtask abi-header check`.

For development against a locally built cdylib, point the loader at it explicitly (production never
falls back to `java.library.path` / `System.load`):

```text
-Dnetbridge.native.path=/absolute/path/to/libnet_bridge_native.so
```

See `docs/troubleshooting.md` for diagnostics and `docs/adr/0009` for the Java 25 / FFM / C ABI
architecture.

Or debug mode:

```bash
make debug
```

Clean everything:

```bash
make clean
```

## License

This project is licensed under the [LGPL-v3](LICENSE).

> v0.0.5 and before are licensed under the AGPL-v3
