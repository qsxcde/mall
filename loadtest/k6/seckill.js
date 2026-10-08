// ============================================================
// 秒杀抢购压测（k6）—— 含「端到端」口径
//
// 本脚本解决 docs/benchmark/测试补充项与MQ对比压测方案.md 里的两个缺陷：
//
//   D2 口径不同（苹果比橘子）
//     旧版只统计「受理耗时」，而两种模式的受理含义不同：
//       非MQ：预扣 + 落库完成（请求线程阻塞到建单）
//       MQ  ：预扣 + 入队受理（毫秒级返回）
//     于是「P99 ↓83%」里混着口径差异，无法直接引用。
//     本版统一按「点击 → 轮询到终态」计时，两组（同步 / 异步）口径完全一致。
//
//   D3 单热点 SKU 掩盖收益
//     用 ITEM_IDS=1,2,3（或更多）让不同 VU 打不同 SKU，把 InnoDB 行锁串行
//     这个物理瓶颈从「1 个 SKU」放宽到「N 个 SKU」，才能看出 MQ 真正的收益场景。
//
// 指标口径（对应文档第 4 节 G1/G2、第 6 节对比矩阵）：
//   accept_latency      受理耗时（POST 返回）              —— G1，两组同口径
//   queue_wait_latency  排队耗时（受理 → 终态，墙钟）      —— 异步特有代价
//   order_latency       端到端耗时（点击 → 终态）          —— G2，两组同口径
//   order_success       受理成功数（抢到资格）             —— 两组同口径
//   e2e_success         真正建单成功数（拿到订单号）
//   e2e_failed/ missing / poll_timeout  异步专有的三类异常终态
//   poll_requests       轮询请求数（异步新增的读放大）     —— P0-5
//
// 用法：
//   # 非MQ（同步落库）
//   ITEM_ID=1 VUS=200 ITERATIONS=100 k6 run --summary-export=results/seckill-sync.json seckill.js
//   # MQ（削峰）—— 只改开关与输出名
//   ITEM_ID=1 VUS=200 ITERATIONS=100 ASYNC=true k6 run --summary-export=results/seckill-async.json seckill.js
//   # 多 SKU（D3）：VU 按序号轮流取 SKU
//   ITEM_IDS=1,2,3 VUS=600 ITERATIONS=1 ASYNC=true k6 run ... seckill.js
//
// 参数（环境变量）：
//   BASE_URL         被测地址，默认 http://localhost:8080
//   ITEM_ID          单个秒杀商品 ID，默认 1
//   ITEM_IDS         多 SKU：逗号分隔，VU 按序号轮流取（优先于 ITEM_ID）
//   VUS              并发虚拟用户数，默认 200
//   ITERATIONS       每个 VU 的迭代次数，默认 100（总请求 = VUS × ITERATIONS）
//   ASYNC            auto | true | false，默认 auto（按返回体判定：订单号以 GM 开头即同步）
//   POLL_INTERVAL_MS 轮询间隔，默认 200（排队耗时精度受它限制）
//   POLL_TIMEOUT_MS  轮询上限，默认 30000；超时计入 poll_timeout
//   MAX_DURATION     k6 场景最长时长，默认 5m（异步轮询会拉长单次迭代）
// ============================================================
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { sleep } from 'k6';
import { userForVU } from './lib/users.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ITEM_IDS = (__ENV.ITEM_IDS
  ? String(__ENV.ITEM_IDS).split(',').map((s) => s.trim()).filter((s) => s)
  : [String(__ENV.ITEM_ID || '1')]);
const VUS = Number(__ENV.VUS || 200);
const ITERATIONS = Number(__ENV.ITERATIONS || 100);
const POLL_INTERVAL_MS = Number(__ENV.POLL_INTERVAL_MS || 200);
const POLL_TIMEOUT_MS = Number(__ENV.POLL_TIMEOUT_MS || 30000);

// auto：运行时按返回体判定（同步模式返回订单号 GMxxxx，异步返回 UUID 请求号）
// 显式 true/false 可跳过判定，用于「接口契约回归」时固定两条路径
const ASYNC_RAW = String(__ENV.ASYNC || 'auto').toLowerCase();
const ASYNC_MODE = ASYNC_RAW === 'auto' ? null : ASYNC_RAW === 'true';

// per-vu-iterations：所有 VU 同时起跑（无 ramp），总请求 = VUS × ITERATIONS，
// 得到的是稳态并发下的真实吞吐（k6 里 {vus, iterations} 简写是 shared-iterations 语义，故显式声明场景）
export const options = {
  scenarios: {
    grab: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: __ENV.MAX_DURATION || '5m',
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(99)'],
  // 不设 thresholds 为失败条件：秒杀场景「已抢光」是预期业务结果，不是错误
  thresholds: {},
};

// 业务口径计数器：把「建单成功 / 抢光 / 限流 / 系统错误」分开统计
const orderSuccess = new Counter('order_success');   // 受理成功（抢到资格）
const orderRejected = new Counter('order_rejected'); // 已抢光 / 限购（受理阶段被拒）
const rateLimited = new Counter('rate_limited');     // HTTP 429
const serverError = new Counter('server_error');     // 5xx 或 code=9999

// 端到端终态计数（异步模式下「受理成功」≠「最终成功」，必须分开统计）
const e2eSuccess = new Counter('e2e_success');       // 轮询到 SUCCESS
const e2eFailed = new Counter('e2e_failed');         // 轮询到 FAILED（落库失败）
const e2eMissing = new Counter('e2e_missing');       // 结果不存在 / 已过期
const pollTimeout = new Counter('poll_timeout');     // 超时仍未到终态
const pollRequests = new Counter('poll_requests');   // 轮询请求总数（读放大）

// 时延趋势：受理 / 排队 / 端到端三档，两组对比时逐档看
const acceptLatency = new Trend('accept_latency', true);
const queueWaitLatency = new Trend('queue_wait_latency', true);
const orderLatency = new Trend('order_latency', true);
const rejectLatency = new Trend('reject_latency', true);
const pollCount = new Trend('poll_count', false);

/** 同步模式的订单号形如 GM+yyyyMMddHHmmss+10 位序列；异步返回的是 UUID 请求号。 */
function looksLikeOrderNo(payload) {
  return typeof payload === 'string' && payload.indexOf('GM') === 0;
}

/**
 * 轮询抢购结果直到终态。
 *
 * 用墙钟（Date.now）而非累加 http 耗时：排队期间真正的等待发生在 sleep 间隔里，
 * 只累加请求耗时会漏掉排队时间，把「排队无期」这个异步核心风险测没。
 */
function pollUntilDone(requestId, token) {
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  let polls = 0;
  while (Date.now() < deadline) {
    const res = http.get(`${BASE_URL}/api/v1/seckill/result/${requestId}`, {
      headers: { Authorization: `Bearer ${token}` },
      tags: { name: 'seckill-result' },
    });
    polls++;
    pollRequests.add(1);

    if (res.status === 429) {
      rateLimited.add(1);
      sleep(POLL_INTERVAL_MS / 1000);
      continue;
    }
    if (res.status >= 500) {
      serverError.add(1);
      sleep(POLL_INTERVAL_MS / 1000);
      continue;
    }

    let body = null;
    try {
      body = res.json();
    } catch (e) {
      body = null;
    }
    if (!body || body.code !== 0) {
      // 记录不存在 / 已过期 / 越权：都是「永远查不到结果」的终态
      return { status: 'MISSING', polls };
    }
    const st = body.data && body.data.status;
    if (st === 'SUCCESS') {
      return { status: 'SUCCESS', polls };
    }
    if (st === 'FAILED') {
      return { status: 'FAILED', polls };
    }
    sleep(POLL_INTERVAL_MS / 1000);
  }
  return { status: 'TIMEOUT', polls };
}

export default function () {
  const user = userForVU(__VU);
  // 多 SKU：同一 VU 固定打同一个 SKU（与「固定账号」同理，避免同一 VU 竞态干扰）
  const itemId = ITEM_IDS[(__VU - 1) % ITEM_IDS.length];

  const res = http.post(
    `${BASE_URL}/api/v1/seckill/${itemId}/order`,
    JSON.stringify({ addressId: Number(user.addressId) }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${user.token}`,
      },
      tags: { name: 'seckill-grab' },
    }
  );

  if (res.status === 429) {
    rateLimited.add(1);
    rejectLatency.add(res.timings.duration);
    return;
  }
  if (res.status >= 500) {
    serverError.add(1);
    return;
  }

  let code = null;
  let payload = null;
  try {
    code = res.json('code');
    payload = res.json('data');
  } catch (e) {
    serverError.add(1);
    return;
  }

  if (code === 9999) {
    serverError.add(1);
    return;
  }
  if (code !== 0) {
    orderRejected.add(1);
    rejectLatency.add(res.timings.duration);
    return;
  }

  // 受理成功：先记受理时延（G1，两组同口径）
  orderSuccess.add(1);
  acceptLatency.add(res.timings.duration);

  const isAsync = ASYNC_MODE === null ? !looksLikeOrderNo(payload) : ASYNC_MODE;
  if (!isAsync) {
    // 同步模式：受理即终态，端到端 == 受理，排队为 0
    orderLatency.add(res.timings.duration);
    queueWaitLatency.add(0);
    e2eSuccess.add(1);
    return;
  }

  // 异步模式：必须轮询到终态，否则「受理成功」只是入队成功，不代表抢到
  const started = Date.now();
  const r = pollUntilDone(payload, user.token);
  const wait = Date.now() - started;
  queueWaitLatency.add(wait);
  pollCount.add(r.polls);
  orderLatency.add(res.timings.duration + wait);

  if (r.status === 'SUCCESS') {
    e2eSuccess.add(1);
  } else if (r.status === 'FAILED') {
    e2eFailed.add(1);
  } else if (r.status === 'MISSING') {
    e2eMissing.add(1);
  } else {
    pollTimeout.add(1);
  }
}
