# ADR-0012: Java 域模型现代化（JSON 边界、传输域、FFM ABI 隔离、并发与时间原语）

状态：已接受 · 日期：2026-09-07

## 背景

在 ADR-0009/0010/0011 之后，Java 侧仍残留较多历史形态：网络状态 `networks` JSON 由 Gson `JsonObject`
自由拼接/解析、string 类型的传输键（`"quic"`/`"kcp"`）散落各层、 语义枚举内部保存 C ABI 整数、原生事件以裸
`(int, long, long, long)` 下传后再在各处手写 switch、`ThreadLocal` 布尔守卫与
`System.currentTimeMillis` 时钟、~470 行手写
`DelegatingChannelFuture`，以及配置层整文件读取失败即整体回退默认值等问题。

本次改动坚持：行为、wire 格式（status JSON 属性名与 native manifest 字段拼写 camelCase）、ABI 数值一律不变；不引入
Jackson Databind/ObjectMapper、不引入 DI/缓存框架；仅使用 Java 25 稳定 API（ScopedValue
final、HexFormat、
`Thread.ofPlatform`、record/sealed/pattern switch），不使用 preview。

## 决策

### JSON：Jackson Core 3 流式解析，仅限 codec 边界

- `common` 的 JSON 读写统一走 `tools.jackson.core`（3.1.6，streaming）手工编写约束受限的 codec：
  `StatusNetworksCodec`（能力广播 `networks`）与
  `NativeManifestCodec`（native manifest）。二者各自持有配置 `StreamReadConstraints`
  的 `JsonFactory`（嵌套/文档/令牌/名字/字符串/数字长度上限），对畸形远端/打包输入降级为 “空能力”或“无效
  manifest”，并防御深度/令牌爆炸。
- **不引入 Jackson Databind / ObjectMapper**：域对象是自有 record，与 JSON 形态解耦，
  反射式绑定只会在边界外偷偷生长出“JSON 类型暴露进域 API”的问题，故同时被架构守卫禁止。
- **Gson 仅保留在 Minecraft/DFU 边界**：status packet 的 Mojang codec （`JsonParser`/`JsonElement`）与
  mixin 抓取层仍用 Minecraft 自带的 Gson；
  `common` 生产代码零 Gson import（守卫校验）。Gson 从项目 catalog 依赖中移除。
- JSON 边界守卫：`tools.jackson` import 仅允许 `ability/StatusNetworksCodec` 与
  `internal/ffm/NativeManifestCodec`；域/公共 API 不暴露任何 Jackson/Gson 类型。

### 传输域：`AcceleratedTransport` 单一事实源 + 类型化能力表

- 新增 `transport.AcceleratedTransport { QUIC("quic","net-bri-quic/1"), KCP(...) }`， 同一枚举同时持有
  status wire key 与应用层协议串，取代散落的
  `TransportProtocol` 常量类与各处 `"quic"`/`"kcp"` 字面量。JSON 边界用
  `fromKey` 丢弃未知键；`fromMode` 供 planner/cache 使用。
- `NetworksEntry` 收敛为纯 record（`enabled/host/port`，compact ctor 保证 port 1..65535、 blank
  host→null）；`NetworksAbility` 以不可变
  `EnumMap<AcceleratedTransport, NetworksEntry>` 为存储，提供按枚举的
  `entry/usable/hasUsableAccelerated`。wire 属性名成为 codec 私有常量。
- 由此消灭“TCP 也能进加速目标/能力表”的非法形态（旧 `TransportTarget(mode,…)` 可含 TCP）。

### 计划/请求/事件：sealed 类型 + 显式不变式

- `ConnectionPlan` sealed（`TcpPlan` / `AcceleratedPlan(tcpAddress, NativeAttempt)`），
  `NativeAttempt` sealed（`QuicAttempt(endpoint)` / `KcpAttempt(endpoint, profile)`）， planner 只用
  `instanceof` 化分支构建，杜绝 `if (mode==TCP)` 遗漏。
- `NativeConnectRequest` / `NativeServerRequest` sealed（Quic/Kcp 变体），compact ctor 校验 host
  非空、port 范围（server 允许 0=ephemeral）、`maxConnections>=1`、 Kcp profile 非空；server 端 port 语义
  -1（跟随 MC）仅在 config/解析层出现。
- 原生事件 `NativeEvent` sealed：`ConnectionStateChanged/DataAvailable/Writable/
  Accepted/ServerStateChanged`；`FfmNativeTransportBackend.onEvent` 用 pattern switch 路由。此前
  ACCEPTED 语义以 Rust 实际发出（object_id=server、arg0=connection）为准。

### FFM ABI 数值隔离

- 语义枚举（`NativeTransportKind`、`NativeConnectionState`、`NativeFailureReason`、
  `NativeKcpProfile`） **不保存任何 ABI 整数**。
- 唯一 ABI 数值住所：`internal/ffm/FfmAbiCodec`（int 常量 + `*ToAbi`/`*FromAbi`/
  `decodeEvent`/`decodeSocketAddress`）与 `FfmApiLayouts`/`FfmStatus`。未知 state/family/event kind
  一律显式拒绝（fail-closed），failure reason 未知→GENERIC 为有意策略。
- 每次降级/事件解码只在 `FfmNativeEventDispatcher` upcall 入口经 codec 一次完成； 任何 `NativeEvent`
  都不能在 codec 之外被构造。

### 并发：ScopedValue 取代布尔 ThreadLocal

- `AccelerationInterceptionScope`（common/client）以两个 `ScopedValue` 表达 “vanilla 直连
  bypass”与“加速连接进行中”：语义上等价于旧 `ConnectionMixin`/
  `NativeClientTransport` 的一对 `ThreadLocal<Boolean>`，但绑定在真实调用点、 随栈自动展开/嵌套、绝不泄漏，且不再要求
  mixin 手工 try/finally。
- `StatusNetworksCapture` 的捕获槽 ThreadLocal 保留（客户端解码线程上的临时 scratchpad，
  架构守卫白名单唯一允许项）。
- 守卫：生产代码禁止静态 `ThreadLocal`/`ExecutorService`（白名单仅 StatusNetworksCapture）。

### 时间：`Duration` + 单调时钟

- 对外/超时 Java API 使用 `java.time.Duration`（`NativeRetryPolicy` 的 first/subsequent timeout 与
  backoff、`SuccessfulEndpointCache` 的 TTL）。
- 纯计时一律单调：缓存 TTL 用可注入 `LongSupplier`（默认 `System::nanoTime`）；
  `FfmNativeContext` drain deadline 由 wall-clock 改 `System.nanoTime`。墙钟仅用于展示。
- `EndpointKey(host, port)` 取代 `"host:port"` 字符串键；文本/精确身份，不做大小写折叠、 不做 DNS。

### 配置与错误

- `NetBridgeProperties` 集中解析四个系统属性（transport/quicPort/native.path/cache.dir），
  全部经参数注入；生产代码不再散读 `System.getProperty`。
- NightConfig 提取逐字段类型化：单字段缺失/错型/越界只回退该字段并告警，绝不整文件 回退默认（旧实现一次
  `ClassCastException` 重置整个配置）。
- 客户端配置写入改原子保存（临时文件 + `FileChannel.force` + `ATOMIC_MOVE` + 回退）。
- native 资源错误收敛为 `NativeResourceError` 枚举 + 全类型构造器；聚合关闭失败用普通
  `RuntimeException` + `addSuppressed`（不是资源错误）；日志统一传 throwable。

### 复杂度：`FfmCallGate`

- `FfmNativeContext` 的调用生命周期（OPEN 检查/活跃 op 记账/关闭期 drain/状态同步）抽为
  `FfmCallGate`；上下文高优操作经 `gate.call/execute`，关闭经 `gate.close(timeout, teardown)`。 上下文不再知晓
  ABI 数值之外的自定义状态机。
- `NativeChannelIo` 抽取被否决：读写路径与 Netty 生命周期状态（outbound buffer、
  writability/backpressure、pipeline 事件、eventLoop 调度）深度耦合，强行拆分只见表面减行、 实则引入语义风险（§8.2
  允许按此判断跳过）。

### `DelegatingChannelFuture`：受控 POC —— **REJECT**

- 目标：以 Netty Promise/ChannelPromise + 显式 orchestration 替代 ~470 行自定义 Future。
- 结论： **拒绝替换，保留 `DelegatingChannelFuture`**（§8.3 明确拒绝即成功完成）。 证据：
    1. Minecraft 侧契约（反编译 1.21.1 字节码）：`ConnectScreen$1` 对返回值仅
       `syncUninterruptibly()`，`ConnectScreen` 仅 `cancel(true)`，无 `channel()`/监听器调用； 但返回值必须
       **实现 `ChannelFuture`**（invokeinterface 分派）。
    2. Netty 唯一现成 `ChannelFuture` 实现 `DefaultChannelPromise.channel` 为
       `private final`——无法表示“创建时无 channel、重试期 channel 会变化、胜者即最终 channel” 的多尝试
       future；外部对象必须在首个尝试前就存在，故必须自持一个实现 `ChannelFuture`
       的类型（判据 7 无法绕开）。
    3. `ImmediateEventExecutor.inEventLoop()` 恒为 true →
       `DefaultPromise.checkDeadLock()` 会在 MC `syncUninterruptibly()`（未完成即阻塞等待） 路径抛
       `BlockingOperationException`。
    4. 使用真实 `EventExecutor` 时，异线程完成会异步通知监听器，破坏
       `DelegatingChannelFutureTest` 与 ClientRuntime close/cancel 竞态测试依赖的确定性 顺序（判据
       1/4）。

    - 故判据 1/2/4/6/7 无法在有界投入内同时成立；保留现状，不强行“现代化”此并发核心。

## 后果

- `common` 生产代码不再依赖 Gson；JSON 只经两个受约束 streaming codec；域 API 类型纯净。
- 传输键/能力/请求/事件均为类型化、可穷举（sealed）的域对象，C ABI 数值只在 FFM codec/layout 内，未知输入
  fail-closed。
- 捕获绕过逻辑从“手写 ThreadLocal + try/finally”退化为标准 ScopedValue 绑定；纯计时改
  单调时钟；配置失败影响面从整文件缩到单字段。
- `DelegatingChannelFuture` 保持自研（含其测试集），未来如需替换须先解决“pre-channel 多尝试
  ChannelFuture”这一结构性前提。
- 静态质量：Error Prone 2.50.0（UnusedMethod/UnusedVariable 重新启用，mixin 以类级 suppression 标注）+
  NullAway 干净；`verifyArchitecture`（JSON/FFM/static/层纯净守卫）与
  `verifyNativeHeader` 作为最终回归门禁。
