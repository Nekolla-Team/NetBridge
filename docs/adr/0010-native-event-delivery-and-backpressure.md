# ADR-0010: native 事件投递与背压模型

状态：已接受 · 日期：2026-09-04 · 取代：ADR-0006（数据面线程模型——EventLoop 自适应轮询）

## 背景

ADR-0006 时代的数据面以 Java 侧轮询为主体：NativeChannel 按 5ms 起步、20/50/100ms 退避的 poll
任务驱动读写重试，服务端由 `net-bridge-accept` 线程以 5ms 周期调用
`acceptConnections` 收养连接，出站队列满时同样依赖轮询重试。轮询带来固定唤醒、空转 CPU 与难以证明的关闭竞态。

FFM cutover（ADR-0009）后，Rust 侧 upcall 回调可以低开销地到达 Java，事件驱动 成为默认路径。

## 决策

1. **事件替代轮询**。Rust 在状态迁移、数据入队、写队列恢复、服务端 accept 时经
   `EventSink::on_event` 发出固定宽度原语事件（CONNECTION_STATE=1、
   DATA_AVAILABLE=2、WRITABLE=3、ACCEPTED=4、SERVER_STATE=5）。Java 侧删除全部轮询任务（channel
   poll/backoff、5ms accept 线程、`ADOPT_EXECUTOR` 轮询收养）。

2. **回调线程纪律**。upcall 发生在 Rust Tokio worker 线程。回调只做：解析事件 → 按 object id 路由到
   typed wrapper → marshal 到目标 EventLoop / adopt 执行器。 回调内禁止阻塞、禁止 Netty/Minecraft
   业务、禁止持有 FFM 临时指针。

3. **DATA_AVAILABLE 合并**。Rust 每次成功入队新读块发一次事件；Java 侧以 per-channel AtomicBoolean
   去抖，多个回调至多安排一个 EventLoop drain runnable （drain 每轮上限 16 次 × 64KiB，洪峰让出
   EventLoop 重排）。不为 v1 引入 empty→nonempty 边沿触发 ack 协议。

4. **WRITABLE 背压**。`connection_write` 队列满返回 `NB_WOULD_BLOCK`（全收或全拒，
   部分写不存在）；NativeChannel 保留消息并置 `setUserDefinedWritability(false)`，等待 WRITABLE 事件
   触发恢复与 flush（无 50ms timer polling）。上游通过 Netty outbound buffer 感知背压，不允许静默丢字节或
   busy-spin。

5. **ACCEPTED 驱动收养**。Rust accept 在完成双端 stream/dataplane 准备后注册连接并发出 `ACCEPTED(server_id,
   conn_id)`，语义跨 QUIC/KCP 一致（数据面 ready 后触发 ACCEPTED；server stop 仅停止接收新连接，不杀死已接管的连接）。
   `ServerTransportManager` 经 adopt 执行器交给 `NativeConnectionAdopter`；adoption 最终 handoff 到
   Minecraft server 线程执行注册与连接列表修改；manager 关闭后 新到/排队 adoption 拒绝并关闭连接。adopt
   失败关闭连接，无裸 id 泄漏。

6. **连接状态事件携带 ABI 值**（internal+1，与 `connection_state` downcall 一致）；
   建连失败路径同样发出终态事件，connect promise 由事件完成，超时仅由
   `ConnectionExecutor` 看门狗兜底。

## 后果

- 空闲零轮询唤醒；背压真实反馈到 Netty outbound buffer。
- 事件路径的正确性由 fake backend（would-block→writable、accept、状态迁移）与 FFM 集成测试（QUIC/KCP
  loopback、回调风暴、重复 backend 生命周期）共同覆盖。
- 若未来 benchmark 证明 upcall 频率本身成为瓶颈，才考虑 Rust 侧边沿触发优化； 不预先引入 ack 协议。

## 增补（最终审计定稿）

- **async panic guard**：任务统一经 `NativeContext::spawn_connection_task /
  spawn_server_task` spawn，外层 await JoinHandle 捕获 poll 期间 panic（测试以 future 内主动 panic 验证
  cleanup + 恰好一次终态事件）；散落手写 spawn+cleanup 禁止。
- **early-event race**：连接 wrapper 注册前到达的事件由 setListener reconcile + tombstone 查询兜底；服务端
  ACCEPTED 早到由 backend `pendingAccepted` 缓冲并在 startServer / setListener 回放——事件不丢失有确定性测试。
- **client 编排异步化**：`ConnectionExecutor` 为非阻塞状态机（`DelegatingChannelFuture`
  换绑 delegate），同步 `syncUninterruptibly` 重试循环删除；typed retryable 之外 直接回退 TCP。架构守卫禁止
  client 包内 `syncUninterruptibly` 回归。
