# net-bridge 术语表

描述 Java 25 + FFM 重构完成后的现行架构。历史决议见 `docs/adr/`（0004/0006/0007 已被 0009/0010/0011
取代）；里程碑历史见旧 refactor-plan 记录。

## 架构总览

- **net-bridge**：Minecraft mod 总体。模块分层：`common`（纯 Java domain/runtime/native 抽象/config，无
  Minecraft import）→ `minecraft`（共享 Minecraft-aware 层，一份源码编译进 两个 loader）→ `fabric` /
  `neoforge`（仅 bootstrap 与生命周期 glue）。
- **Rust workspace**：`net-bridge-core`（纯传输核心：tokio runtime、QUIC/KCP、连接注册表、 typed error，
  `#![forbid(unsafe_code)]`）+ `net-bridge-native`（C ABI v1 shell，unsafe 与 FFI 集中于此）。
- **C ABI v1**（ADR-0009）：唯一 bootstrap 导出 `netbridge_get_api(requested_major,
  minimum_minor, out_api)`，返回 `NbApiV1` 函数表（struct_size/feature_bits 保留字段 + 12
  个函数指针）；连接/服务端为 context 级 `u64` id；`nb_status_t` 统一错误码。
- **NativeContext**（Rust）：实例级运行时所有权根——tokio runtime、连接/服务端注册表、 id 分配器、
  `EventSink` 均为 context 字段；无进程级全局注册表。
- **Java FFM 边界**：仅存在于 `nativebridge.internal.ffm` 包。`FfmNativeLibrary`
  （shared Arena + `SymbolLookup.libraryLookup` + upcall stub）→ `FfmNativeContext`
  （函数表下调用）→ `FfmNativeTransportBackend` 实现公共 seam
  `NativeTransportBackend`。事件 upcall 绑定 `NativeEventDispatcher` 实例。
- **composition root**：`NetBridgeServices` 仅持一个 `NetBridgeRuntime` root（config 服务 + backend +
  `ClientRuntime` + `ServerRuntime`）。native 不可用时以
  `UnavailableNativeTransportBackend` 降级，runtime 仍完整、TCP 可用。

## 传输协议

- **QUIC 明文（quic-plaintext）**：QUIC 传输但关闭 TLS 加密；安全性由 Minecraft 自带加密流保证。动机：省一次加密握手开销。
- **KCP 栈**（自外向内）： **FEC (RS) → KCP → smux**。
    - **FEC / RS 码**：Reed-Solomon 前向纠错，最外层 UDP 包保护。实现在
      `net-bridge-core/src/transport/kcp/fec_stream.rs`。
    - **KCP**：可靠低延迟 ARQ，stream 模式（字节流管道），kcp-rs 实现（内建 SYN 握手）。
    - **smux**：多路流控层，滑动窗口 token 流控 + FIN 关闭语义；单条 MC 字节流占用一个 smux 流。
- **KCP profile**：预设参数档，仅二档不支持自定义。配置串规范 `balance` / `aggressive`
  （Rust 解析兼容别名 `balanced`）：
    - **balance**：nodelay=0/interval=40/resend=0/nc=0，mtu=1300，wnd= (256,256)，stream=true。
    - **aggressive**：nodelay=1/interval=10/resend=2/nc=1，其余同上。

## 数据面（ADR-0009/0010）

- **写路径**：Netty direct ByteBuf → `nioBuffer` 零拷贝借给 `NativeConnection.write`
  → FFM downcall，Rust 在 downcall 返回前拷入自有 `Bytes` 队列；heap/composite 经一次 池化 direct
  scratch。单 chunk 上限 64KiB，全收或全拒（`NB_WOULD_BLOCK` = 队列满）。
- **读路径**：direct `ByteBuffer` 目标直写；无 Java heap `byte[]` 中转、无 JNI direct 特例。
- **队列上限**：命令通道 4096、数据通道 8192 chunks，背压经 `NB_WOULD_BLOCK` 反馈。
- **事件模型**：Rust 状态迁移/数据入队/写队列恢复/服务端 accept 经 `EventSink::on_event`
  发出 `CONNECTION_STATE(1)/DATA_AVAILABLE(2)/WRITABLE(3)/ACCEPTED(4)/SERVER_STATE(5)`； Java
  侧无任何轮询任务（channel poll、5ms accept 线程、ADOPT_EXECUTOR 均删除）。 DATA_AVAILABLE 在 Java
  侧去抖合并；WRITABLE 解除写背压。
- **事件线程纪律**：upcall 在 Rust Tokio worker；Java 回调只路由/marshal，不执行 Minecraft 业务、不阻塞。
- **连接状态 ABI 值**：CONNECTING=1 / CONNECTED=2 / CLOSED=3 / FAILED=4（core 内部值+1）。

## 能力发现

- **networks 能力**：服务端在列表 ping 响应 JSON 注入的顶层 `networks` 对象，每传输一个条目
  `{enable, host, port, protocol}`。`enable` 缺失 = false；`host` 缺失/null = 跟随服务器地址。 服务端由
  `ServerTransportManager` 事务式启动后发布不可变 `NetworksAbility` 快照。
- **protocol 版本串**：`net-bri-quic/1`、`net-bri-kcp/1`。客户端精确比对自身支持集， 不支持的协议 →
  该传输本地禁用。版本演进只看 protocol 串。

## 客户端行为

- **TransportMode**：三档 `tcp` / `quic` / `kcp`（配置串），用户选择的“意图”。quic/kcp 内置 TCP 降级，tcp
  无降级概念。加速意图经 `AcceleratedTransport.fromMode` 映射为传输域对象。
- **ConnectionPlanner**：纯决策对象，输入 mode × 能力宣告 × 最近成功缓存 × native 可用性，输出不可变
  `ConnectionPlan`：`TcpPlan`（直接 TCP）或 `AcceleratedPlan(tcpAddress, NativeAttempt)`；
  `NativeAttempt` 为 `QuicAttempt(endpoint)` / `KcpAttempt(endpoint, profile)`。所选传输未宣告/
  不可用 → 直接 TCP，不尝试其他加速传输。
- **ConnectionExecutor**：执行 plan——按 `NativeRetryPolicy`（至多 2 次尝试，10s/20s 看门狗，
  `Duration` 语义）驱动 native 尝试、关闭失败尝试、记录成功端点、发布状态快照、最终经
  `ConnectionExecutorAdapter.openTcp` 回落原版 TCP。成功按 `TransportTarget(AcceleratedTransport,
  address)` 记忆。
- **SuccessfulEndpointCache**：以 `EndpointKey(host,port)` 为键，TTL（`Duration`，默认 5 分钟， 单调
  `nanoTime` ticker 注入）内记录 **成功**加速端点；TTL 内同传输重连跳过宣告协商；换传输立即 失效；有界（默认
  256）按最早过期淘汰；失败不写记忆。
- **ServerCapabilityCache**：实例级 LRU（256，`EndpointKey` 键），按地址缓存 ping 解析出的 networks 能力。
- **连接状态快照**：`ConnectionStateStore` 发布不可变 `ConnectionSnapshot`
  （CONNECTING/CONNECTED/FALLING_BACK/IDLE），ConnectScreen 与 F3 行只读快照，
  `ConnectStatus`/`ConnectionDisplay` 静态类已删除。
- **握手存活判定**：QUIC 的 CONNECTED = 明文握手与双向数据流就绪； **KCP 的 CONNECTED = KCP 传输握手 +
  FEC + smux 会话建立并打开 MC 数据流**（
  `connect_timeout` 8s 内无应答直接 FAILED）。看门狗由
  `ConnectionExecutor` 承载，超时 abort 连接并计入当次尝试失败。
- **连接提示 / F3 行**：语义不变（ADR-0005），数据源改为 runtime 快照。

## 服务端行为

- **ServerRuntime / ServerTransportManager**：session-scoped、AutoCloseable；事务式
  start（解析配置快照 → 逐传输 `backend.startServer` → 查询实际端口 → 原子发布 announcement；失败
  reverse-close）。单传输 bind 失败不阻塞另一传输；两个都失败时 vanilla TCP 继续。server stop
  仅停止新连接接收，不主动杀死已交付的连接。
- **ACCEPTED 驱动收养**：QUIC/KCP 双端数据流完全建立且注册完成之后才触发 `ACCEPTED`；新连接经
  `NativeConnectionAdopter` 在 adopt 执行器上带 generation 校验收养进 MC 管线；adopt 失败关闭连接。无裸
  long handle、无 5ms accept 轮询线程。
- **服务端 `[quic]`/`[kcp]` 段**：`enable`（ **quic 默认 true，kcp 默认 false**）/ `bind` /
  `host` / `port`（-1 跟随 MC 端口， **kcp 为 MC 端口+1**；0 随机；越界或 bind 失败→ 日志报错并禁用该传输）/
  `max_connection`（默认 256，达限静默丢弃新客户端——防 UDP 反射，quic/kcp 独立计数）。ping 条目恒下发
  **解析后的具体端口**。

## 配置

- **nightconfig**：MC 生态 TOML 配置库。服务端 `config/net-bridge/server.toml`；客户端同目录
  `config/net-bridge/client.toml`。
- **游戏内切换按钮**：保留（多人游戏屏幕底部），与配置文件双向同步（读文件初始值、切换即写回）。
- **客户端 `mode`**：tcp（默认）/ quic / kcp；`[kcp] profile` = balance（默认）/ aggressive。系统属性
  `netbridge.transport` 同名覆盖配置文件；旧取值（`quic-fallback` 等）废弃。

## 打包与加载

- **内容寻址缓存**：打包资源经 sha256 内容寻址落盘 `~/.netbridge/native/<sha256>/<lib>`， 原子写入、跨启动复用；
  `native/<platform>/manifest.json`（sha256/ABI/包版本）随 jar 分发， 抽取时校验。开发可用
  `-Dnetbridge.native.path=<绝对路径>` 显式覆盖；生产不再回退
  `java.library.path` / `System.load`。
- **构建守卫**：`verifyArchitecture`（层纯净性/FFM 白名单/JNI 归零/@NullMarked 覆盖/ 副本不存在/Rust
  core 纯净）、`verifyNativeSymbols`（业务导出仅
  `netbridge_get_api`）、`generateNativeManifest`（sha256 + 头文件 ABI 常量）。
- **CI**：java-unit（无 native）/ rust-unit（fmt+clippy -D warnings+test）/ abi-check（符号+布局+
  `--illegal-native-access=deny` 下 FFM 集成）/ 全平台 native matrix → package（Java 25）→
  release；publish 依赖全部前置 job。

## Java 域模型补充术语（ADR-0012 现代化后）

- **accelerated transport（加速传输）**：`transport.AcceleratedTransport` 枚举
  `QUIC`/`KCP`，是传输域（planner/能力表/成功缓存）与 status JSON 键的唯一事实源： 每项携带 wire status
  `key()`（`"quic"`/`"kcp"`）与应用层 `protocol()` 版本串。语义枚举，不存 ABI 整数。
- **transport mode（传输模式）**：`transport.TransportMode` 三档（tcp/quic/kcp）——用户配置层 “意图”枚举（
  `parse/configValue` 处理配置串）；与 `AcceleratedTransport` 经 `fromMode`
  互转，TCP 意图没有加速形态。
- **native transport kind（原生传输类型）**：`nativebridge.NativeTransportKind`（QUIC/KCP）， native
  会话面对的类型；与 C ABI 数字的映射只在 FFM codec 内（`FfmAbiCodec`）， QUIC=1/KCP=2 不进语义枚举。
- **status networks ability（状态网络能力）**：`ability.NetworksAbility`——不可变
  `EnumMap<AcceleratedTransport, NetworksEntry>` 快照；wire 形态为列表 ping 的顶层
  `networks` 对象，仅由 `ability.StatusNetworksCodec` 编解码（Jackson Core streaming， 约束长度/深度）。
  `NetworksEntry` 为纯 record（enabled/host/port），protocol 不再驻留域对象。
- **FFM ABI codec**：`nativebridge.internal.ffm.FfmAbiCodec`——C ABI 数值（传输/连接状态/ 事件
  kind/服务端状态/kcp profile/failure reason/事件解码/socket 地址）唯一 Java 映射点；
  未知数值显式拒绝（fail-closed，failure reason 未知→GENERIC 为有意例外）。 配套 `FfmCallGate`
  承载调用生命周期（记账/drain/关闭同步）。
- **interception/bypass scope（拦截与直连作用域）**：`client.AccelerationInterceptionScope`， 以两个
  `ScopedValue` 表达“加速连接进行中”与“vanilla 直连 bypass”：`ConnectionMixin`
  在真实 `Connection.connect` 前进入拦截作用域，`MinecraftAdapter.openTcp` 在 vanilla 回落调用点 绑定
  bypass，随调用栈自动展开、嵌套不泄漏（取代旧 `ThreadLocal<Boolean>` 布尔守卫）。
