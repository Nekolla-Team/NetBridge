# ADR-0008: 传输握手超时与存活判定

状态：已接受（Java 25 + FFM Round 4 修正） · 日期：2026-08-26 · 依赖：ADR-0002（watchdog 定义）

> 实现备注：看门狗现在由 client runtime 的 `ConnectionPlanner` / `ConnectionExecutor`
> （`NativeRetryPolicy` 10s/20s）承载，不再是旧 static `HandshakeWatchdog` 工具类。
> 握手与 CONNECTED 存活判定在 Round 4 中统一为数据面就绪契约：KCP 完成 SYN + FEC + smux stream
> 打开，QUIC 完成明文握手 + stream 探针就绪。

## 背景

ADR-0002 规定握手失败判定为 Java 侧 10s/20s 超时。调查三个依赖库能否原生承担该职责 （本地锁定版本：quinn
0.11.11 / quinn-proto 0.11.17 / quinn-plaintext 0.3.0 / kcp-rs 0.2.6）。

### QUIC 调查结论

- quinn-plaintext 仅实现 `crypto::Session`/`ClientConfig`/`ServerConfig` 明文替换， 不涉及任何超时机制；quinn
  全部传输配置可用但与本问题无关。
- quinn-proto 的 `Timer::Idle`（触发 `ConnectionError::TimedOut`）仅在
  `on_packet_authenticated → reset_idle_timeout` 布防—— **只在收到包时重置，连接创建时不设初值**。
- 黑洞场景（零响应）：loss detection 的 PTO 探针无限重传（`pto_count` 无上限、无放弃逻辑）， Idle
  永不触发 ⇒ `connecting.await` **永久悬挂**。
- 有响应后断流：需等 `max(协商 idle timeout 默认 30s, 3×PTO)` 才报 TimedOut，远超目标。

结论：quinn 无法在 10s/20s 内自行终结黑洞握手。

### KCP 调查结论（kcp-rs 更新）

- kcp-rs 内建握手：客户端 `KcpStream::connect` 发 SYN（含随机会话 id），等待服务端确认后 返回——
  `connect_timeout`（原生默认 15s，本栈设为 8s）内无应答即 `TimedOut`，native 层可 判定失败，不再依赖首帧猜测。
- `session_expire`（默认 90s）仍为服务端闲置会话回收，与建连无关。
- 历史阶段曾将 KCP CONNECTED 暂定为「SYN 握手完成」；但在完整数据面中，会话必须完成 FEC 与 smux stream
  就绪才具备数据传输能力。因此自 Round 4 起，最终统一为「KCP 传输握手 + FEC + smux 会话建立并打开 MC
  数据流就绪」才是 STATE_CONNECTED 与 ACCEPTED 的生效时刻。

结论：KCP 握手超时可由 native 层自行终结（8s < Java watchdog 10s），watchdog 退化为兜底。

## 决策

1. **统一由 Java `HandshakeWatchdog` 承担握手超时**（首次 10s、后续 20s，竞速 connect promise）。 KCP 侧
   native `connect_timeout`(8s) 先失败上报，watchdog 主要兜底 QUIC 黑洞。
2. **KCP 存活判定**：STATE_CONNECTED 统一为 KCP SYN + FEC + smux stream 数据面完全就绪（客户端与服务端均在数据流打开后标记
   CONNECTED / ACCEPTED，不提前暴露半建状态）。
3. **QUIC 维持现状**：明文握手双向交换 transport params，CONNECTED 即真实可达证明； watchdog
   超时后关闭连接句柄即可。
4. 两栈其余参数保持默认：`max_idle_timeout`(30s) 管会话期存活，`ReadTimeoutHandler(30)` 管
   应用层空闲，三者正交不混用。

## 后果

- watchdog 超时路径必须主动 `closeConnection(connId)` 清理 native 句柄（QUIC 黑洞下 native 任务仍在
  PTO 空转，不清理则泄漏）。
- KCP 握手共 1 RTT（SYN + 确认）；8s 预算在高延迟链路依然充足。旧「首字节判定」及 FRAME_PROBE/FRAME_PONG
  探测帧随 kcp-rs 切换一并移除。
