# Troubleshooting

按启动顺序排列的 net-bridge 诊断手册。日志通道：启动器把 stderr 重定向进
`logs/latest.log`，native 层错误均带 `[net-bridge-native]` 前缀。

## Native backend 未就绪（加速传输全部禁用）

日志特征：`net-bridge native unavailable; accelerated transports disabled (TCP fallback)`。

| 原因                     | 诊断与处置                                                                                                                                    |
|--------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| 不支持的平台/架构        | `native/<os>-<arch>/` 目录无匹配资源。确认平台在支持列表（linux/windows x86_64、linux/macos aarch64、macos x86_64）；arm64 Windows 暂不支持。 |
| native resource missing  | jar 内缺 `native/<platform>/<lib>`——重装/重新下载完整 mod jar。                                                                               |
| checksum mismatch        | `manifest.json` 的 sha256 与实际库不一致（jar 损坏或被改动）。重新下载。                                                                      |
| native access denied     | 未加 JVM 参数 `--enable-native-access=ALL-UNNAMED`（Java 25 下被拒绝访问的 restricted method 会直接失败）。按 README 的启动参数补齐。         |
| load failed              | 库文件存在但 OS 拒绝映射（权限/缺依赖）。检查文件权限与系统库。                                                                               |
| missing bootstrap symbol | 库内无 `netbridge_get_api`——版本错配或文件损坏，重装。                                                                                        |
| ABI incompatible         | `netbridge_get_api` 版本协商失败：mod jar 与 native 库不是同一次构建产物，整体更新。                                                          |
| API table invalid        | 函数表 struct_size/必需函数指针校验失败——同样按 ABI 不兼容处理。                                                                              |
| context create failed    | Rust `NativeContext`/tokio runtime 创建失败（资源极端受限）。检查内存/线程数限制。                                                            |

## 服务端启动

| 现象                                                        | 诊断与处置                                                                                                                                                    |
|-------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `quic/kcp transport failed to bind udp/<port>`              | 端口被占用或 bind 地址不合法。`server.toml` 的 `port = 0` 可改随机；`-1` 跟随 MC TCP 端口（kcp 为 +1）。bind 失败只禁用该传输，TCP 不受影响。                 |
| `No accelerated transport started; only TCP will be served` | 两个传输都未启动（都禁用/都 bind 失败/native 不可用）。核对 `[quic]`/`[kcp]` 的 `enable` 与上游错误。                                                         |
| ping 无 `networks` 字段                                     | 服务端 acceptor 未运行（dedicated server 才启动；integrated/LAN 不启动 acceptor），或注入超出 256KiB status 上限被放弃（日志 `networks injection dropped`）。 |

## 客户端连接

| 现象                                    | 诊断与处置                                                                                                                     |
|-----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| 直接连 TCP（无加速尝试）                | F3/日志显示 `Transport for <addr>: TCP (mode=tcp)`：客户端 mode 为 tcp；或目标服务器未宣告所选传输/协议版本不支持。            |
| `Handshake to ... failed (attempt 1/2)` | 加速握手失败（黑洞/丢包/版本不匹配）。第 2 次失败自动回退 TCP；确认服务端端口可达（UDP）且两端 mod 版本一致。                  |
| 频繁回退 TCP                            | 排查 UDP 链路质量（QUIC/KCP 均走 UDP）；KCP 可试 `profile = "aggressive"`（高丢包链路）。成功过的端点会缓存 5 分钟以跳过协商。 |
| 连接成功但无 F3 协议行                  | F3 行仅在 net-bridge 加速连接激活时显示；TCP 直连无该行（正常）。                                                              |

## 开发者

- 缓存目录损坏/权限：错误码 `CACHE_UNWRITABLE`；可用
  `-Dnetbridge.native.cache.dir=<dir>` 重定向缓存根。损坏条目自动复验并原子替换。
- 平台不支持：错误码 `UNSUPPORTED_PLATFORM`（附 normalized os/arch）。
- 本地调试 native：`-Dnetbridge.native.path=/abs/path/libnet_bridge_native.so`
  （优先于打包资源；生产无 `java.library.path` 回退）。
- 构建验证：`./gradlew verifyArchitecture verifyNativeSymbols generateNativeManifest`。
- native 集成测试：`./gradlew :common:nativeIntegrationTest`（自带
  `--enable-native-access=ALL-UNNAMED` 与 `--illegal-native-access=deny`）。
- 基准测试：`./gradlew :common:ffmBenchmark`，说明见 `docs/benchmarks/ffm-baseline.md`。
