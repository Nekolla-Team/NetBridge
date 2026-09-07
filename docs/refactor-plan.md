# net-bridge 重构任务拆分（历史记录）

> 本文件描述 JNI 时代的旧重构里程碑， **已被 `NETBRIDGE_JAVA25_FFM_FULL_REFACTOR_PLAN.md`
> （Java 25 + FFM 全量重构）取代**，仅作历史记录保留；现行架构见 `docs/glossary.md` 与
> `docs/adr/0009~0011`。
>
> 2026-09 后续一轮 **Java 域模型现代化**（JSON 边界、传输域类型化、sealed 计划/请求/事件、
> FFM ABI 数值隔离、ScopedValue/Duration/单调时钟、受控配置与错误、`FfmCallGate` 抽取）见
> `NETBRIDGE_JAVA_MODERNIZATION_IMPLEMENTATION_PLAN.md`，决议见 `docs/adr/0012`；其中
> `DelegatingChannelFuture` 受控 POC 结论为 **REJECT（保留自研实现）**。


依据：docs/adr/0001~0007 · docs/glossary.md · docs/conventions.md。 顺序即依赖序；每里程碑含验收标准，全绿才进下一个。

## M1 Rust 传输抽象（ADR-0007）

| 任务               | 内容                                                                                                                    |
|--------------------|-------------------------------------------------------------------------------------------------------------------------|
| `src/transport.rs` | `trait Transport { connect / accept / state / read / write / close }`；错误类型带 Transport 标签                        |
| `bridge/quic/`     | 现 `bridge/{client,server,connection}.rs` 迁入并实现 trait                                                              |
| `bridge/kcp/`      | 现 kcp 模块接入同一 trait（fec_stream/frame 不动）；**CONNECTED 置位改为首个有效数据帧入 `to_java` 队列时**（ADR-0008） |
| registry 修正      | `ACTIVE_SERVER_CONNS` 全局计数器 → **每 server 实例独立计数**（ADR-0003 后果项）                                        |
| 门面               | `bridge::start_server/connect/...` 经 `dyn Transport` 分派                                                              |

验收：`cargo test` 全绿；quic/kcp 各自可经门面完成连接-读写-关闭往返测试。

## M2 JNI 表面更名 + ABI 0.2.0（ADR-0004/0007）

| 任务     | 内容                                                                                                 |
|----------|------------------------------------------------------------------------------------------------------|
| Java     | `jni/QuicNative` → `jni/NativeBridge`；`QuicConnectionState` → `NativeConnState`                     |
| Rust     | 导出符号同步改 `Java_top_tangge233_netbridge_jni_NativeBridge_*`；`NET_BRIDGE_ABI_VERSION = "0.2.0"` |
| 加载校验 | `NativeLoader` 版本不匹配即拒绝加载并给出明确日志                                                    |

验收：双端编译通过；smoke test 连接往返正常；ABI 不匹配路径有测试。

## M3 能力发现 wire v2（ADR-0001）

| 任务          | 内容                                                                                                                                                    |
|---------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ability/` 包 | `NetworksEntry(enable/host/port/protocol)`、`NetworksAbility` 按传输名取条目、`StatusNetworksCodec` 注入/解析、`TransportProtocol` 常量与支持集精确比对 |
| 删除          | features 字段全部逻辑、`supportsQuicRaw()`、`net-bridge/1` 常量                                                                                         |
| 注入端        | 双 loader 的 ping mixin 改发 v2 JSON（quic+kcp 条目；**wire 恒填解析后具体端口**，-1/0 不上 wire，ADR-0001/0003）                                       |

验收：解析/注入单测覆盖 缺 enable=缺省 false、host null=跟随、未知 protocol=本地禁用。

## M4 客户端传输状态机（ADR-0002/0005/0006）

| 任务                | 内容                                                                                                                                                                                        |
|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `transport/` 包     | `TransportMode(tcp/quic/kcp)`、`ClientConfig`(client.toml 读写)、`KcpProfile` 解析（配置串规范 `balance`/`aggressive`，解析兼容别名 `balanced`）                                            |
| `TransportSelector` | mode × 能力缓存 → 目标传输；未宣告/协议不支持 → 直接 TCP                                                                                                                                    |
| `HandshakeWatchdog` | 定时任务与 connect promise 竞速：首次 10s、后续 20s；失败计 1 次，满 2 次降级；超时路径主动 closeConnection 清理 native 句柄（ADR-0008：两栈均无可用原生握手超时，watchdog 为唯一可行方案） |
| `FallbackTracker`   | 按服务器地址记忆降级，TTL 5min                                                                                                                                                              |
| `NativeChannel`     | 原 QuicChannel 更名泛化；轮询改自适应步进 5→10→20→40ms（ADR-0006）                                                                                                                          |
| 清理                | QUIC_ONLY 档删除；客户端 transport 类接入 kcp 目标                                                                                                                                          |

验收：状态机单测覆盖 超时×2 降级 / TTL 过期重试 / 未宣告直降 TCP；模拟黑洞测试握手在 10s 内终结。

## M5 服务端配置与 acceptor（ADR-0003）

| 任务                  | 内容                                                                                                                                     |
|-----------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `server/ServerConfig` | `[quic]`/`[kcp]` 全字段：enable/bind/host/port/max_connection，nightconfig 读写                                                          |
| port 语义             | -1 跟随 MC 端口（**kcp 为 +1**）、0 随机（启动后日志输出实际端口）、越界报错禁用该传输；**bind 失败同禁用路径**；ping 下发解析后具体端口 |
| `NativeAcceptor`      | 每 server 独立 max_connection；超限静默丢弃（无响应）；adopt 流程复用现有 acceptConnections 排水模式                                     |

验收：配置边界值单测（-1/0/65535/65536/null bind/host）；超限连接表现为对端超时。

## M6 GUI / F3 / i18n（ADR-0005）

| 任务          | 内容                                                                             |
|---------------|----------------------------------------------------------------------------------|
| ConnectScreen | 另起一行状态机文案：正在建立 QUIC/KCP/TCP 连接 → 正在回退 TCP 连接               |
| F3            | 单行 `[net-bridge] <PROTOCOL> <addr>`；TCP 直连同样显示                          |
| 设置按钮      | 保留多人屏幕底部入口，与 client.toml 双向同步，tooltip 说明降级                  |
| i18n          | zh_cn + en_us（Connecting via QUIC… / Falling back to TCP… 等），key 见 ADR-0005 |

验收：人工过三场景（quic 成功 / 两次失败降级 / tcp 直连）文案与 F3 正确联动。

## M7 收尾清理

| 任务           | 内容                                                                                  |
|----------------|---------------------------------------------------------------------------------------|
| 双 loader 同步 | fabric/neoforge 副本逐一核对一致                                                      |
| 注释规范       | 触及文件按 conventions.md 重写：函数级文档注释、删体内冗注、**清除全部 ADR 编号索引** |
| 死代码         | 旧 wire 解析、QUIC_ONLY、features 相关测试一并移除                                    |
| README         | 配置示例、协议说明更新到 v2                                                           |

验收：grep 无 `ADR-` 于 src；两 loader diff 仅包名差异；全量测试绿。

## 风险备忘

- ~~quinn_plaintext 内部 idle timeout 默认值未审计~~ 已审计（ADR-0008）：黑洞下 quinn 永不自行 失败、KCP
  无握手概念——watchdog + KCP 首字节判定为定案，无残留风险。
- KCP `connect()` 现无异步握手任务封装（对照 quic client.rs 模式补齐），M1 重点。
- 双 loader 副本漂移是历史事故源，M7 必须逐文件 diff。
