# Troubleshooting

A net-bridge diagnostic guide ordered by startup sequence. The launcher redirects stderr to
`logs/latest.log`, and native-layer errors use the `[net-bridge-native]` prefix.

## Native Backend Not Ready (All Accelerated Transports Disabled)

Typical log: `net-bridge native unavailable; accelerated transports disabled (TCP fallback)`.

| Cause                             | Diagnosis and action                                                                                                                                                                            |
|-----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Unsupported platform/architecture | No matching resource exists under `native/<os>-<arch>/`. Confirm the platform is supported (linux/windows x86_64, linux/macos aarch64, macos x86_64). arm64 Windows is not currently supported. |
| native resource missing           | The jar is missing `native/<platform>/<lib>`. Reinstall or redownload the complete mod jar.                                                                                                     |
| checksum mismatch                 | The sha256 in `manifest.json` does not match the actual library, indicating a corrupted or modified jar. Redownload it.                                                                         |
| native access denied              | JVM argument `--enable-native-access=ALL-UNNAMED` is missing. On Java 25, denied access to a restricted method fails immediately. Add the startup argument from the README.                     |
| load failed                       | The library exists but the OS refuses to map it due to permissions or missing dependencies. Check file permissions and system libraries.                                                        |
| missing bootstrap symbol          | The library does not contain `netbridge_get_api`, indicating a version mismatch or corrupted file. Reinstall.                                                                                   |
| ABI incompatible                  | `netbridge_get_api` version negotiation failed: the mod jar and native library are not from the same build. Update them together.                                                               |
| API table invalid                 | Function-table `struct_size` or required function-pointer validation failed. Treat this as ABI incompatibility.                                                                                 |
| context create failed             | Rust `NativeContext`/Tokio runtime creation failed, typically under extreme resource constraints. Check memory and thread limits.                                                               |

## Server Startup

| Symptom                                                     | Diagnosis and action                                                                                                                                                                                                      |
|-------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `quic/kcp transport failed to bind udp/<port>`              | The port is occupied or the bind address is invalid. In `server.toml`, use `port = 0` for a random port; `-1` follows the MC TCP port (KCP uses +1). A bind failure disables only that transport; TCP is unaffected.      |
| `No accelerated transport started; only TCP will be served` | Neither transport started because both are disabled, both failed to bind, or native is unavailable. Check `[quic]`/`[kcp]` `enable` and earlier errors.                                                                   |
| Ping response has no `networks` field                       | The server acceptor is not running (it starts only on a dedicated server; integrated/LAN does not start the acceptor), or injection exceeded the 256KiB status limit and was dropped (log: `networks injection dropped`). |

## Client Connections

| Symptom                                                 | Diagnosis and action                                                                                                                                                                                                                       |
|---------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Connects directly with TCP (no accelerated attempt)     | F3/logs show `Transport for <addr>: TCP (mode=tcp)`: client mode is tcp, or the target server did not announce the selected transport / uses an unsupported protocol version.                                                              |
| `Handshake to ... failed (attempt 1/2)`                 | Accelerated handshake failed due to a black hole, packet loss, or version mismatch. After the second failure it automatically falls back to TCP. Confirm the server UDP port is reachable and both endpoints use compatible mod versions.  |
| Frequent TCP fallback                                   | Investigate UDP path quality because both QUIC and KCP use UDP. For KCP, try `profile = "aggressive"` on lossy paths. Successful endpoints are cached for 5 minutes to skip negotiation.                                                   |
| Server announces acceleration but client still uses TCP | Malformed or oversized remote `networks` JSON safely degrades to empty capability in the client codec without crashing or false positives. Check whether the server truncated the networks block or omitted the `protocol` version string. |
| Connected successfully but no F3 protocol line          | The F3 line is shown only while a net-bridge accelerated connection is active. Direct TCP has no line, which is expected.                                                                                                                  |

## Developers

- Cache-directory corruption/permissions: `NativeResourceException` with error code
  `CACHE_UNWRITABLE`. Use
  `-Dnetbridge.native.cache.dir=<dir>` to redirect the cache root. Corrupt entries are revalidated
  and atomically replaced.
- Unsupported platform: error code `UNSUPPORTED_PLATFORM`, including normalized os/arch.
- Local native debugging: `-Dnetbridge.native.path=/abs/path/libnet_bridge_native.so`
  takes precedence over packaged resources; production has no `java.library.path` fallback.
- System properties (transport/quicPort/native.path/cache.dir) are parsed centrally by
  `NetBridgeProperties` and injected through the composition root. New properties must not be read
  ad hoc with `System.getProperty` in business code.
- Build verification: `./gradlew verifyArchitecture verifyNativeSymbols generateNativeManifest`.
- Packaging verification: `./gradlew fabric:verifyFabricPackaging neoforge:verifyNeoForgePackaging`
  asserts exactly one jackson-core, zero databind, nightconfig presence, complete native
  manifest+libraries, and no leftover signatures in META-INF. Duplicate `tools.jackson.*` or
  `META-INF` conflicts indicate that a dependency was embedded twice or a whole-directory exclusion
  was removed.
- Native integration tests: `./gradlew :common:nativeIntegrationTest`, which includes
  `--enable-native-access=ALL-UNNAMED` and `--illegal-native-access=deny`.
- Benchmark: `./gradlew :common:ffmBenchmark`; see `docs/benchmarks/ffm-baseline.md`.
