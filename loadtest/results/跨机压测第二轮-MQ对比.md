# 跨机压测 · 第二轮：MQ 削峰 vs 同步落库（A/B 对比）

> 环境：B机 macOS 192.168.1.110 → A机 Windows 192.168.1.109；限流已关；库存 200（单热点 SKU item 1）
> 唯一变量：`mall.seckill.async.enabled`（消费者线程 64）；两轮均为 200VU×100、同一批账号

## 一、非MQ（同步落库）

## k6 压测指标（2026-10-08 17:22:11）

### Round 1 · 同步落库 200VU×100

- 样本总数：**20000**（成功建单 200 / 业务拒绝 19800 / 限流 429 0 / 系统错误 0，HTTP 失败率 0.00%）
- 压测持续：**6.34 s**
- **QPS（总吞吐）**：3155.8 req/s
- **TPS（成功吞吐）**：31.6 req/s

| 分位 | 全部请求 | 成功请求 |
|---|---|---|
| P50 | 22.2 ms | 2426.2 ms |
| P90 | 31.3 ms | 4118.0 ms |
| P99 | 1667.9 ms | 4660.4 ms |
| 平均 | 57.8 ms | 2423.0 ms |
| 最大 | 4734.6 ms | - |
| 最小 | 5.0 ms | - |

| 口径 | P50 | P90 | P99 | 平均 |
|---|---|---|---|---|
| 受理（两组同口径） | 2426.2 ms | 4118.0 ms | 4660.4 ms | 2423.0 ms |
| 排队（异步特有） | 0.0 ms | 0.0 ms | 0.0 ms | 0.0 ms |
| 端到端（用户体感） | 2426.2 ms | 4118.0 ms | 4660.4 ms | 2423.0 ms |

- 端到端终态：成功 **200** / 落库失败 0 / 查不到结果 0 / 轮询超时 0（合计 200）

| 服务端 TTFB | 数值 |
|---|---|
| P50 | 22.0 ms |
| P90 | 31.1 ms |
| P99 | 1667.8 ms |
| 平均 | 57.6 ms |

> 客户端开销（总耗时 − TTFB，均值）= **0.2 ms**（可忽略，说明测得的延迟就是服务端延迟）


## 二、MQ（削峰异步落库）

## k6 压测指标（2026-10-08 17:22:11）

### Round 2 · Redis Stream 削峰 200VU×100（消费线程 64）

- 样本总数：**22591**（成功建单 200 / 业务拒绝 19696 / 限流 429 0 / 系统错误 104，HTTP 失败率 0.00%）
- 压测持续：**6.99 s**
- **QPS（总吞吐）**：3233.8 req/s
- **TPS（成功吞吐）**：28.6 req/s

| 分位 | 全部请求 | 成功请求 |
|---|---|---|
| P50 | 15.3 ms | 3008.2 ms |
| P90 | 24.9 ms | 4983.4 ms |
| P99 | 164.3 ms | 5504.3 ms |
| 平均 | 22.9 ms | 3060.4 ms |
| 最大 | 562.9 ms | - |
| 最小 | 4.2 ms | - |

| 口径 | P50 | P90 | P99 | 平均 |
|---|---|---|---|---|
| 受理（两组同口径） | 54.8 ms | 519.9 ms | 533.6 ms | 275.5 ms |
| 排队（异步特有） | 2815.5 ms | 4733.4 ms | 5180.1 ms | 2784.9 ms |
| 端到端（用户体感） | 3008.2 ms | 4983.4 ms | 5504.3 ms | 3060.4 ms |

- 端到端终态：成功 **200** / 落库失败 0 / 查不到结果 0 / 轮询超时 0（合计 200）
- 轮询请求数：**2591**，读放大 **13.0 次/单**（异步新增的读放大路径，P0-5）

| 服务端 TTFB | 数值 |
|---|---|
| P50 | 15.0 ms |
| P90 | 24.5 ms |
| P99 | 164.3 ms |
| 平均 | 22.6 ms |

> 客户端开销（总耗时 − TTFB，均值）= **0.3 ms**（可忽略，说明测得的延迟就是服务端延迟）


## 三、队列排空曲线（压测期间每秒采样）

### 削峰队列

| 时刻 | 耗时(s) | XLEN | pending | lag | 消费者 | SUCCESS | QUEUED | FAILED | 内存(MB) |
|---|---|---|---|---|---|---|---|---|---|
| 17:20:29 | 0.0 | 0 | 0 | 0 | 64 | 0 | 0 | 0 | 2.6 |
| 17:20:30 | 1.1 | 0 | 0 | 0 | 64 | 0 | 0 | 0 | 2.5 |
| 17:20:31 | 2.1 | 0 | 0 | 0 | 64 | 0 | 0 | 0 | 2.5 |
| 17:20:32 | 3.2 | 0 | 0 | 0 | 64 | 0 | 0 | 0 | 2.5 |
| 17:20:33 | 4.2 | 200 | 187 | 0 | 64 | 14 | 186 | 104 | 2.6 |
| 17:20:34 | 5.3 | 200 | 149 | 0 | 64 | 51 | 149 | 104 | 2.8 |
| 17:20:35 | 6.4 | 200 | 106 | 0 | 64 | 95 | 105 | 104 | 2.8 |
| 17:20:36 | 7.4 | 200 | 64 | 0 | 64 | 137 | 63 | 104 | 2.8 |
| 17:20:37 | 8.5 | 200 | 18 | 0 | 64 | 183 | 17 | 104 | 2.8 |
| 17:20:38 | 9.6 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:39 | 10.6 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:40 | 11.7 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:41 | 12.7 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:42 | 13.8 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.7 |
| 17:20:43 | 14.9 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:44 | 15.9 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:46 | 17.0 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.7 |
| 17:20:47 | 18.0 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.7 |
| 17:20:48 | 19.1 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.7 |
| 17:20:49 | 20.2 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:50 | 21.2 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:51 | 22.3 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.7 |
| 17:20:52 | 23.3 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:53 | 24.4 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:54 | 25.4 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:55 | 26.5 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:56 | 27.5 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.7 |
| 17:20:57 | 28.6 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.7 |
| 17:20:58 | 29.6 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:20:59 | 30.7 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:21:00 | 31.7 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.8 |
| 17:21:01 | 32.8 | 200 | 0 | 0 | 64 | 200 | 0 | 104 | 2.7 |

- 积压峰值：`pending`（未确认）**187** / `XLEN`（流内消息总数）200
  > `XLEN` 不会因 ACK 而下降（已确认的消息仍留在 stream 里，直到被 `max-stream-length` 裁剪），**所以排空判据看 `pending` 与 `lag`，不看 `XLEN`**。
- 失败终态（FAILED 结果）：**104** ⚠️ 必须归因，它同时包含「落库失败（≈死信）」与「入队失败（预扣已回补）」两类；配合 `mall_circuit_rejected_total{resource="seckill-queue"}` 区分
- 排空：**已排空** ✅（pending 与 lag 均归零）
- 消费者：64 个
- 排空耗时（首现积压 17:20:33 → pending 归零 17:20:38）：约 **5.4 s**

## 四、服务端佐证（Prometheus /actuator/prometheus）

```
http_server_requests_seconds_count{application="geek-mall-server",error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/api/v1/seckill/result/{requestId}"} 2591
http_server_requests_seconds_count{application="geek-mall-server",error="none",exception="none",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/seckill/{itemId}/order"} 20000
http_server_requests_seconds_max{application="geek-mall-server",error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/api/v1/seckill/result/{requestId}"} 0.0993982
http_server_requests_seconds_max{application="geek-mall-server",error="none",exception="none",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/seckill/{itemId}/order"} 0.498388
http_server_requests_seconds_sum{application="geek-mall-server",error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/api/v1/seckill/result/{requestId}"} 13.4213856
http_server_requests_seconds_sum{application="geek-mall-server",error="none",exception="none",method="POST",outcome="SUCCESS",status="200",uri="/api/v1/seckill/{itemId}/order"} 145.9442834
mall_circuit_rejected_total{application="geek-mall-server",resource="seckill-queue"} 104.0
mall_circuit_state{application="geek-mall-server",resource="seckill-queue"} 0.0
executor_active_threads{application="geek-mall-server",name="seckillExecutor"} 0.0
executor_pool_size_threads{application="geek-mall-server",name="seckillExecutor"} 0.0
```
