# Redis Stream 消费组多实例验证（本机 · 2026-10-25）

> 补上 `多实例部署验证-2026-10-08.md` 留下的空白：**Redis Stream 消费者组在多实例下的分工与回收**。
> 验证方式：3 实例真实 Docker 部署 + 26 项自动化断言，全部由
> `loadtest/scripts/verify_stream_group.py` 产出。

---

## 〇、先更正一个概念

**Redis Stream 没有 Kafka 式的 rebalance。** `XGROUP` + `XREADGROUP` 是**拉取式**分工：
每个消费者独立 `XREADGROUP ... >` 取新消息，不存在分区分配，也没有 coordinator 触发再均衡。

因此本报告验证的是两件真实存在的事，而不是照搬 Kafka 的概念：

1. **分工自动调整** —— 新消费者上线会分走后续消息；消费者退出后剩下的自然接手
2. **pending 回收** —— 消费者死了，它名下「已读未 ACK」的消息必须被别人 `XCLAIM` 回来重投

---

## 一、环境

| 项 | 取值 |
| --- | --- |
| 拓扑 | `infra/docker-compose.multi.yml`，3 实例 + MySQL 8 + Redis 7 |
| 开关 | `mall.seckill.async.enabled=true`、`batch-enabled=true` |
| 回收窗口 | `reclaim-min-idle=2s`、`reclaim-interval=1s`（默认 1m/30s，验证时必须压到秒级） |
| 消费者 | 每实例 4 线程 → 共 12 个 consumer，命名 `seckill-<容器主机名>-8080-<线程号>` |
| 注入方式 | `XADD` 直接注入消息（隔离队列语义）；抢购受理/预扣链路由 `SeckillBatchPersistIntegrationTest` 覆盖 |

---

## 二、结果：9 个用例 / 26 项断言全过

| 用例 | 覆盖 | 结果 |
| --- | --- | --- |
| **V6a** 分工 | 3 实例同组消费 100 条 | ✅ 5/5 |
| **V6b** 消费者中途加入 | 第 3 实例上线分走后续消息 | ✅ 3/3 |
| **V6c** 消费者中途退出 | 剩余实例接手干净 | ✅ 3/3 |
| **V6d** 孤儿 pending 回收 | 假消费者读走不 ACK（等价 kill -9 现场） | ✅ 4/4 |
| **V6e** 真实 kill -9 | `docker kill -9` 后其 pending 被回收 | ✅ 3/3 |
| **V6f** 自愈 + 无僵尸 | 重启 3 次消费者数不变；组被删后自愈 | ✅ 3/3 |
| **V6g** 深积压 | 300 孤儿 >> `batch-size=16` | ✅ 2/2 |
| **V6h** 重复回补幂等 | 两次补偿只归还一份库存 | ✅ 3/3 |
| **V6i** 毒消息 | 字段损坏消息被丢弃，不阻塞后续 | ✅ 2/2 |

### 关键数据

**V6a 分工**（100 条消息 / 12 个 consumer）：

```
seckill-b9ef1d58661a-8080-0: 50   seckill-1d429a0f6230-8080-0: 16
seckill-b9ef1d58661a-8080-1: 33   seckill-1d429a0f6230-8080-1: 16
seckill-b9ef1d58661a-8080-2: 18   seckill-1d429a0f6230-8080-2: 16
seckill-b9ef1d58661a-8080-3: 29   seckill-1d429a0f6230-8080-3: 16
seckill-b358b4ad5f2e-8080-0: 17   ...（12 个 consumer 均分到活）
```

- 订单数 == 注入消息数 **100/100**，每个 requestId 恰好一单
- `XPENDING` 归零，consumer 名 12 个 / 12 个不重复

**V6d / V6e 孤儿回收**：100 条挂在「已死消费者」名下 → **5.4~14.4s** 内被存活消费者回收，
建单 100/100、零重复、pending 归零。V6e 的消息挂在 `geek-mall-app2` 的**真实**消费者名
`seckill-b87bdc800715-8080-0` 下再 `kill -9`，而不是借假消费者模拟。

**V6g 深积压**：300 条孤儿 **32.7s** 排干（≈16 条/轮 × 1s 扫描间隔，符合
`reclaimStale` 的 `maxCount` 行为）—— 深积压能持续排干，不会卡在前 N 条。

**V6h 重复回补**：同用户两次补偿后 `mall:seckill:stock:*` = **101**（初始 100），
而不是 102。`RELEASE_SCRIPT` 的 `SREM` 守卫确实挡住了「越补越多」。

---

## 三、修掉两个「消息只进不出」的真 bug

### ① 消费组被删后无法自愈（`6fe719c`）

`ensureGroup` 的 `groupReady` 一旦置 true 就**永久短路**。此前只修了「首次建组失败要重试」，
漏了「建好之后被删」这一半：

> 消费组被 `XGROUP DESTROY`、Redis 淘汰或故障转移丢掉后，所有 `XREADGROUP` 一直报 NOGROUP
> —— 消息照常 `XADD` 进流，却再没有消费者取走，**用户永远停在「排队中」，只能重启进程恢复**。

由 V6a 实测暴露：`reset()` 删掉消费组后，100 条消息 **0 条**被消费。
修复：`poll` / `reclaimStale` 捕获 NOGROUP 时撤销 `groupReady`，下次调用重建。

### ② 自愈修复本身失效（`b308f84`）

第一次修复的 `invalidateGroupIfGone` **从来没触发过** —— 它只看顶层异常 message，
而 Lettuce 抛的 `RedisCommandExecutionException(NOGROUP ...)` 会被 Spring 再包一层
（`UncategorizedRedisException` 等），顶层 message 里只有「Error in execution」。

由 V6c 实测暴露：消费组被删后 80 条消息 **0 条**被消费，日志反复报 NOGROUP
却始终没有「消费组已丢失」的告警。改为遍历 cause 链后全部通过。

> **教训**：这两条只跑 V6a/V6d 是发现不了的。V6a 靠的是「消费组刚好还能被重建」，
> 必须有 V6c 这种「先杀实例再注入」的顺序，才让自愈兜底真正无路可退。

---

## 四、两个测试设计上的坑

**① 制造孤儿现场必须先冻结实例**（V6d / V6e / V6g）

```bash
docker pause app1 app2 app3     # 不冻结的话，消息注入瞬间就被真实消费者抢走
XADD  ...                        # 轮不到假消费者，测出来的是「正常消费」而非「孤儿回收」
XREADGROUP GROUP ... seckill-dead-faker ...
docker unpause app1 app2 app3
```

**② V6h 的失败条件不能用 `not_start`**

`not_start` 只有**批量路径**校验，逐条路径的 `grabInTx` 不校验它 —— 消息会直接建单成功，
反而测不到回补。必须用 **DB 活动库存 = 0**，两条路径才会都以 `OUT_OF_STOCK` 失败。

---

## 五、复现方式

```bash
# 1) 起 3 实例（回收窗口必须压到秒级）
SECKILL_ASYNC_ENABLED=true SECKILL_BATCH_ENABLED=true \
SECKILL_RECLAIM_MIN_IDLE=2s SECKILL_RECLAIM_INTERVAL=1s \
  docker compose -f infra/docker-compose.yml -f infra/docker-compose.multi.yml up -d

# 2) 跑全部 9 个用例
python3 loadtest/scripts/verify_stream_group.py --n 100

# 3) 只跑指定用例
python3 loadtest/scripts/verify_stream_group.py --only v6e
```

脚本会自行准备夹具（秒杀商品 / 收货地址）、清理上一轮数据、冻结实例制造确定性现场，
并从 3 个容器的 `docker logs` 里按 `consumer=` 统计分工。

---

## 六、仍留白

| 项 | 说明 |
| --- | --- |
| 本地 L1 一致性窗口 | 仍未实测「实例 A 写 → 实例 B 读」的延迟分布，当前靠 `mall.cache.local.names` 白名单收窄 |
| MAX_DELIVERIES 转死信 | 代码路径已有（`deadLetter` 回补预扣 + 写 FAILED + discard），但未单独做端到端用例 |
| 消费者组 `lag` 观测 | `XINFO GROUPS` 的 `lag` 未纳入看板；积压目前只看 `mall_seckill_queue_*` |

---

## 相关文档

- `docs/benchmark/多实例部署验证-2026-10-08.md` —— 上一轮多实例验证（限流/号段/缓存/ShedLock/会话广播）
- `loadtest/results/批量落库对比-2026-10-24.md` —— 批量落库压测与 Stage 2 决策
- `loadtest/scripts/verify_stream_group.py` —— 本报告的可执行来源
