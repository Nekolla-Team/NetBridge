# 代码写法规范

重构全程适用；触及的文件一律按本规范清理存量注释。

## 注释

- **函数级**：每个函数/方法/公开常量用文档注释（Rust `///`、Java Javadoc）说明用途与参数。 返回值契约（如
  `0 = 队列满须重试`、`-1 = 非法`）属于接口承诺，必须写在这里。
- **函数体内**：非必要不加注释。代码自解释；仅在意图无法从代码读出时注释，
  且只写"为什么"，不复述"做了什么"。
- **禁止随改动累积注释**：修 bug、重构时不得顺手加入描述本次改动的解释性注释。 设计依据沉淀到
  `docs/adr` 与 `docs/glossary`，不进函数体。
- **书面语**：注释禁口语化措辞；内容必须与实际行为一致——行为变更时同步修改或删除， 宁缺勿错。

## 文档引用

- **源码内禁止出现 ADR / 术语表索引**（如"见 ADR-0007""（ADR-0002）"）。 编号会漂移，索引必然失真；代码不维护指向文档的链接。
- 跨层语义约定写在函数文档注释中，不借文档编号转述。

## 存量问题

现仓库大量违反本规范：函数体内长段解释、全库散布 ADR 编号引用。
重构任务清单含"按本规范重写触及文件注释"一项。

## Java 域模型约定（本轮现代化新增）

以下约定由 `verifyArchitecture` 的部分守卫强制；`docs/adr/0012` 记录其设计依据。

- **JSON 边界归属**：`common` 的 JSON 读写只发生在 codec 类型内 （`ability/StatusNetworksCodec`、
  `nativebridge/internal/ffm/NativeManifestCodec`）， 使用 Jackson Core 3 streaming API。域/公共类型不得
  import
  `tools.jackson.*` 或 `com.google.gson`；守卫按文件白名单放行。
- **域 API 不暴露 JSON 类型**：公共/域方法签名中不得出现 Jackson/Gson 类型；模型是自有 record，与 wire
  形态解耦。Gson 只允许在共享 Minecraft 层（Mojang codec/DFU 边界）使用。
- **语义枚举不存 ABI 数值**：`NativeTransportKind`、`NativeConnectionState`、
  `NativeFailureReason`、`NativeKcpProfile` 是纯语义枚举；所有 C ABI 整数映射集中在
  `internal/ffm/FfmAbiCodec`（或 `FfmApiLayouts`/`FfmStatus`）。
- **Java 超时 API 用 `Duration`**：对外超时/退避参数与常量用 `java.time.Duration`， 仅在 Netty
  调度等叶子处换算为毫秒。
- **纯计时用单调时钟**：过期判断/超时预算等“经过时长”用 `System.nanoTime`（或可注入
  `LongSupplier`）；墙钟只用于展示。
- **线程局部上下文优先 ScopedValue**：调用栈内一次性标志（如连接拦截/bypass）用
  `ScopedValue` 绑定，随栈展开、不泄漏。静态 `ThreadLocal` 仅限架构守卫白名单的 临时捕获槽（
  `StatusNetworksCapture`）。
- **配置提取逐字段类型化**：NightConfig 读取经类型检查 helper；单字段缺失/错型/越界只
  回退该字段并告警，禁止整文件回退默认。配置写入使用原子保存（临时文件 + force + ATOMIC_MOVE 回退）。
- **record 构造器显式不变式**：record compact 构造器负责非空/非空白/范围校验，把
  “永远成立”的约束写进类型本身，而非散落在调用处。

## 生成物（generated artifact）

`rust/crates/net-bridge-native/include/netbridge.h` 是由固定版本 cbindgen 从 Rust ABI 源真相 （
`rust/crates/net-bridge-native/src/abi/`）生成的产物，并随仓库提交：

- **禁止手工编辑 `netbridge.h`**；手工改动由 CI drift gate 拒绝。
- 需要改 ABI 时只改 Rust ABI 源文件。
- 更新：`./gradlew updateNativeHeader`（或 `cd rust && cargo xtask abi-header update`）。
- 提交前若 ABI 有变更，先跑 `./gradlew verifyNativeHeader`（或 `cargo xtask abi-header check`）。
