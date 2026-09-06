# ADR-0009: Java 25 / FFM / C ABI v1 native interop

状态：已接受 · 日期：2026-09-02 · 取代：ADR-0004（JNI 数据面边界策略）

## 背景

旧架构通过 JNI 桥接 Java 与 Rust：`byte[]` 边界拷贝、JNI direct-buffer 读特例、Tokio 线程 attach JVM
再回调 Java static registry、ABI 版本仅以字符串精确匹配、连接状态依赖 Java 侧 轮询。这些机制把 native
生命周期、错误语义与线程边界散落在多个模块，无法收敛对象所有权。

Java 25 已把 Foreign Function & Memory API（FFM）作为正式 API。本项目借此 ABI 必须重做的 窗口，把
Java、Rust、native ABI、状态所有权与回调一起重构到最终形态，并彻底删除 JNI。

## 决策

1. **Java 25 + FFM 是唯一主路径**，不保留 JNI fallback，不提供 Java 21 artifact， 不使用 multi-release
   jar。mod metadata 声明 `java >= 25`，并保留清晰 bootstrap guard。

2. **FFM 是叶子依赖**。`java.lang.foreign` 只允许出现在
   `top.tangge233.netbridge.nativebridge.internal.ffm`；禁止进入 channel / client / server / config /
   minecraft / fabric / neoforge 层。上层只接触
   `NativeTransportBackend` / `NativeConnection` / `NativeServer` 等 typed 抽象。

3. **纯 C ABI（`extern "C"` + `#[repr(C)]`）**，不跨 ABI 传 Rust `bool`/`enum`/`String`/
   `Vec`/reference/slice/trait object/`Result`。固定宽度整型 （
   `uint8_t/uint16_t/uint32_t/uint64_t/int32_t`），无 C `long`。

4. **单一 bootstrap export：`netbridge_get_api(requested_major, minimum_minor, &api)`**。 返回进程生命周期内只读的
   `nb_api_v1_t` 函数表。发现 ABI 与创建 runtime 是两个独立步骤。 版本模型为 ABI major / minor +
   function-table `struct_size` + `feature_bits`，不再用 version 字符串 exact-match。V1 固定
   `major=1, minor=0`。

5. **`NativeContext` 从 ABI v1 开始存在**：context 是 opaque pointer （`nb_context_t*`
   ），connection/server 是 context-scoped monotonic `uint64_t` id （不复用，溢出视为 fatal）。所有资源操作都显式属于
   context。Java 通常只建一个 context， 但 ABI 不依赖单例。Rust `net-bridge-core` 的 `NativeContext` 拥有
   Tokio runtime、 连接/服务端 registry、id allocator 与 EventSink，不再使用 process-global registry。

6. **统一错误模型**：raw 函数返回 `nb_status_t`（`NB_OK`/`NB_WOULD_BLOCK` 及一组正值/负值 错误码），数据经
   typed out-param 返回。状态查询与函数错误分离：不存在的 id 返回
   `NB_NOT_FOUND`，而不是 `UNKNOWN=-1`。sentinel 语义只存在于 ABI 数值定义，不进入 Java domain API。

7. **数据面内存边界**：跨语言不追求零拷贝。写方向借入 Java `MemorySegment` 仅在本 downcall 内有效，Rust
   在返回前取得数据所有权；读方向 Rust 一次性把 queued `Bytes` 拼进调用方 direct
   内存。单次边界拷贝、无中间数组、明确借用期。单次 I/O 上限 64 KiB （`MAX_IO_CHUNK`）。

8. **回调完全事件化、实例化、必选**：Rust `NativeContext::EventSink` → C 函数指针 → FFM upcall stub →
   绑定具体 `NativeEventDispatcher` 实例（`MethodHandle.bindTo` + upcall）。 不再需要 static Java
   callback registry / GlobalRef / method-id。Upcall 只传固定宽度 primitive（
   `event_kind, object_id, arg0, arg1`）。事件回调发生在 Rust Tokio worker 线程上： Java 侧只允许
   decode primitive event、找到 instance 监听器、`eventLoop.execute(...)`
   转交或入队、立即返回；禁止阻塞/等待/直接 fire Netty pipeline。无 polling fallback： 若 upcall
   stub/回调无法建立，native backend 视为不可用。

9. **关键 native 生命周期使用显式 `AutoCloseable` + `Arena.ofShared()`**，不依赖
   `Arena.ofAuto()`/finalizer 作为正确性路径。`FfmNativeLibrary` 拥有 shared Arena、
   SymbolLookup、upcall stub 与 NativeContext 生命周期。关闭顺序：停止上层新请求 → close
   servers/connections → `context_shutdown` → `context_destroy` → 确认不再有 upcall → close shared
   Arena。

10. **panic / 异常不跨界**。Rust 每个 C ABI entrypoint 用 `catch_unwind` 包裹，panic 映射为
    `NB_PANIC` 并记录日志。Java upcall target 内部 `try/catch(Throwable)`，绝不把异常抛回 FFM
    boundary。Rust 侧调用 callback 前后不得持有 registry/queue lock。

11. **Rust 分层**：`net-bridge-core`（纯 Rust、`#![forbid(unsafe_code)]`、无 `jni` 依赖） 承担
    runtime/context/transport；`net-bridge-native` 只做 C ABI glue（ABI structs、 pointer 校验、panic
    guard、函数表、bootstrap export）。

12. **socket address 走固定二进制 struct**（`nb_socket_address_v1_t`：family/port/address
    [16]/scope_id），不再用格式化字符串 + `InetAddress.getByName`。

## 后果

- `net_bridge.h` 为 ABI contract 的可读规范（检查进仓库），Rust struct 与 Java
  `MemoryLayout` 各自配合 layout 测试校验，防止两边漂移。symbol surface 收敛为
  `netbridge_get_api`（release CI 以 `nm -D` 等校验），JNI `Java_*` 符号全部移除。
- KCP profile 首版以数值 enum 传入（0/1 = balanced，2 = aggressive），host 以
  `nb_bytes_view_v1_t`（UTF-8，不要求 NUL 终止，仅 call 内有效）传入。
- 相关旧 ADR：ADR-0006（EventLoop 自适应轮询）随 polling 删除而由事件驱动模型取代； ADR-0007（JNI
  命名/模块布局）中 JNI 部分作废。二者后续单独 supersede。
- Rust 侧 `NativeContext`、`netbridge_get_api`、Java 侧 `FfmApiV1` 与 layout/roundtrip/ upcall
  测试先行落地（Phase 4 POC），为后续 FFM vertical slice 与 JNI cutover 提供已验证 ABI contract。

## 增补（最终审计定稿）

- **callback/context 关联**：每个 NativeContext 拥有独立的 dispatcher + upcall stub （在同一 shared
  Arena 内分配），事件天然按 context 隔离；跨 context object id 相同 不会串线（有确定性测试）。若未来公开
  ABI 冻结需要多 context 强 token 关联，可再引入
  `callback_token` 字段（当前单 mod 单 backend 场景下方案 2 已充分）。
- **生命周期并发闸**：Java 侧所有 downcall 经 beginOp/endOp 引用计数；close/destroy CAS 单次；closing
  后拒绝新操作并排空在途操作后才执行 context_destroy 与 Arena.close。 close-vs-downcall stress
  测试为发布门槛。
- **终态 tombstone**：FAILED/CLOSED 后 registry entry 保留至 Java release （`connection_state`
  可查询终态），终态事件恰好一次；release 即
  `close_connection`（移除 entry）。禁止先 remove 再发事件。
- **id 契约**：id 从 1 起、不复用；分配器回绕返回 `BridgeError::IdOverflow`（映射
  `NB_INTERNAL`），绝不 wrap 到 0。
- **native manifest fail-closed**：打包 manifest 缺失/畸形/不匹配一律拒绝加载 （
  `NativeResourceException`），缓存命中前重验内容；并发提取 temp+atomic move 安全； 不支持平台 typed
  `UNSUPPORTED_PLATFORM`。

## 增补：netbridge.h 生成策略（cbindgen 集成）

- **源真相**：Rust C ABI 定义（`rust/crates/net-bridge-native/src/abi/`）是 `netbridge.h` 的唯一源真相，
  不是手工维护的 C 头文件。
- **生成**：`netbridge.h` 由固定版本 cbindgen `0.29.2`（经 `rust/xtask` 依赖锁入 `Cargo.lock`）确定性生成，
  并随仓库提交；手工修改被禁止。
- **命令**：更新用 `./gradlew updateNativeHeader` 或 `cd rust && cargo xtask abi-header update`；校验用
  `./gradlew verifyNativeHeader` 或 `cargo xtask abi-header check`（永不写入）。
- **CI drift gate**：PR CI 与 release CI 都真实运行生成器校验；header 过期或手工改动即失败，release
  在打包前拦截。
- **生成不取代测试**：Rust `repr(C)` 布局测试、Java `MemoryLayout` 测试与真实 FFM 集成测试保持必需，
  它们与 cbindgen drift gate 互补验证。
- **jextract**：保持为未来可选消费者；生成头保持普通 C，但当前生产构建不引入 jextract， Java FFM
  仍为手工、生命周期感知的实现。

## 历史说明（已取代）

早期"手写很小稳定 C header + 布局测试"的建议（见重构计划旧版）已被上述 cbindgen 决策取代。
