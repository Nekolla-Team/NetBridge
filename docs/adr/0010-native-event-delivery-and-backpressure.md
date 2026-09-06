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

- **事件提交与外发解耦（INV-4）**：Rust 事件处理在持锁期间仅做状态校验与不可变事件描述符构造，释放所有锁（Mutex、commit_lock、DashMap guard）后方触发 FFI sink；Java listener 同样在释放内部 monitor 后调用，彻底消除 FFI 与用户代码重入死锁。
- **async panic guard 与任务监督**：任务统一经
  `NativeContext::spawn_connection_task / spawn_server_task` 托管到 context 级 JoinHandle 集合；panic
  捕获后触发对应连接/服务端终态清理；QUIC reader 与 KCP reader 均作为受控子任务管理，在连接关闭时显式中止与回收。
- **early-event race 与收养状态机**：服务端早期 ACCEPTED 事件经 `PendingAcceptedConnection`
  统一状态机跟踪（包含早到 FAILED/CLOSED 终态检测与资源释放）；`FfmNativeServer` 严格分离 active 活跃所有权与
  undelivered 待分发队列，确保每个连接对服务端 listener 恰好分发一次。
- **client 编排与 ChannelFuture 契约**：`DelegatingChannelFuture` 拥有完整的基于 monitor 条件等待与原子状态机的
  Netty Future 契约，彻底消除轮询 sleep；`ClientRuntime` 统一管理所有在途连接尝试（包含原生与 TCP
  回退），close 时取消全部在途任务且关闭后立即快速失败。
