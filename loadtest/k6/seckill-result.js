// ============================================================
// 抢购结果轮询压测 —— 真实链路（live）
//
// 对应方案文档 P0-5：`GET /api/v1/seckill/result/{requestId}` 是削峰模式**新增的读放大路径**。
// 同步模式下这条流量根本不存在；异步模式下它由「前端轮询」产生，会被写进新的读流量里，
// 却从未被压过。本脚本回答两个问题：
//
//   1. 放大倍数：一次抢购平均产生多少次轮询？（= poll_requests / grab_ok）
//   2. 轮询读写代价：单个 GET 的延迟分布 + 排队期间的总等待
//
// 与 `seckill.js` 的分工（避免重复）：
//   seckill.js        —— 端到端对比（受理 / 排队 / 端到端），轮询只是顺带计量
//   本脚本            —— 只盯轮询这条读路径：先抢一次，再按固定间隔轮询到终态，
//                        并把每次 GET 的延迟单独统计（result_latency）
//
// ⚠️ 必须先开启削峰模式（`mall.seckill.async.enabled=true`）：
//    同步模式下抢购直接返回订单号，没有「排队」这个中间态，也就没有轮询可言。
//    脚本会识别到并计入 `sync_mode_detected`（非 0 说明开关没打开）。
//
// 用法：
//   # 100 个用户各抢 1 次并轮询到终态
//   VUS=100 ITERATIONS=1 k6 run --summary-export=results/seckill-result-live.json \
//     loadtest/k6/seckill-result.js
//
//   # 多 SKU：VU 按序号轮流打不同商品
//   ITEM_IDS=101,102,103 VUS=300 ITERATIONS=1 k6 run ... loadtest/k6/seckill-result.js
//
// 参数（环境变量）：
//   BASE_URL          被测地址，默认 http://localhost:8080
//   ITEM_ID           单个秒杀商品 ID，默认 1
//   ITEM_IDS          多 SKU：逗号分隔（优先于 ITEM_ID）
//   VUS               并发虚拟用户数，默认 100
//   ITERATIONS        每个 VU 的迭代次数（每次 = 抢购 1 次 + 轮询到终态），默认 1
//   POLL_INTERVAL_MS  轮询间隔，默认 200
//   POLL_TIMEOUT_MS   轮询上限，默认 30000；超时计入 poll_timeout
// ============================================================
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { userForVU } from './lib/users.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ITEM_IDS = (__ENV.ITEM_IDS
  ? String(__ENV.ITEM_IDS).split(',').map((s) => s.trim()).filter((s) => s)
  : [String(__ENV.ITEM_ID || '1')]);
const VUS = Number(__ENV.VUS || 100);
const ITERATIONS = Number(__ENV.ITERATIONS || 1);
const POLL_INTERVAL_MS = Number(__ENV.POLL_INTERVAL_MS || 200);
const POLL_TIMEOUT_MS = Number(__ENV.POLL_TIMEOUT_MS || 30000);

export const options = {
  scenarios: {
    poll: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: __ENV.MAX_DURATION || '5m',
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(99)'],
  thresholds: {},
};

// 抢购阶段
const grabOk = new Counter('grab_ok');
const grabRejected = new Counter('grab_rejected');
const syncModeDetected = new Counter('sync_mode_detected');

// 轮询阶段（读放大路径的核心计数）
const resultOk = new Counter('result_success');        // 查到记录（含中间态）
const resultQueued = new Counter('result_queued');     // 其中仍处于 QUEUED（未落库）
const resultNotFound = new Counter('result_not_found'); // 记录不存在 / 已过期 / 越权
const resultRateLimited = new Counter('result_rate_limited'); // HTTP 429
const resultServerError = new Counter('result_server_error'); // 5xx / code=9999
const pollRequests = new Counter('poll_requests');
const pollTimeout = new Counter('poll_timeout');
const e2eSuccess = new Counter('e2e_success');
const e2eFailed = new Counter('e2e_failed');

const grabLatency = new Trend('grab_latency', true);
const resultLatency = new Trend('result_latency', true); // **单次轮询 GET 的延迟**
const pollCount = new Trend('poll_count', false);
const pollWait = new Trend('poll_wait', true);           // 受理 → 终态的墙钟等待

/** 同步模式返回订单号（GM 开头），异步返回 UUID 请求号。 */
function looksLikeOrderNo(payload) {
  return typeof payload === 'string' && payload.indexOf('GM') === 0;
}

/** 单次 GET 结果接口，按响应分类计数，并记录这一次的延迟。 */
function fetchOnce(requestId, token) {
  const res = http.get(`${BASE_URL}/api/v1/seckill/result/${requestId}`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'seckill-result' },
  });
  pollRequests.add(1);
  resultLatency.add(res.timings.duration);

  if (res.status === 429) {
    resultRateLimited.add(1);
    return 'RATE_LIMITED';
  }
  if (res.status >= 500) {
    resultServerError.add(1);
    return 'ERROR';
  }

  let body = null;
  try {
    body = res.json();
  } catch (e) {
    resultServerError.add(1);
    return 'ERROR';
  }
  if (body.code === 9999) {
    resultServerError.add(1);
    return 'ERROR';
  }
  if (body.code !== 0) {
    // 不存在 / 已过期 / 越权：都是「永远查不到」的终态
    resultNotFound.add(1);
    return 'MISSING';
  }

  resultOk.add(1);
  const status = body.data && body.data.status;
  if (status === 'SUCCESS') {
    return 'SUCCESS';
  }
  if (status === 'FAILED') {
    return 'FAILED';
  }
  resultQueued.add(1);
  return 'QUEUED';
}

export default function () {
  const user = userForVU(__VU);
  const itemId = ITEM_IDS[(__VU - 1) % ITEM_IDS.length];

  const grab = http.post(
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
  grabLatency.add(grab.timings.duration);

  let payload = null;
  let code = null;
  try {
    code = grab.json('code');
    payload = grab.json('data');
  } catch (e) {
    code = -1;
  }
  if (code !== 0) {
    grabRejected.add(1);
    return;
  }
  grabOk.add(1);

  if (looksLikeOrderNo(payload)) {
    // 后端没开削峰：受理即终态，没有可轮询的中间态
    syncModeDetected.add(1);
    e2eSuccess.add(1);
    return;
  }

  const started = Date.now();
  let polls = 0;
  let terminal = 'TIMEOUT';
  while (Date.now() - started < POLL_TIMEOUT_MS) {
    if (polls > 0) {
      sleep(POLL_INTERVAL_MS / 1000);
    }
    polls++;
    const outcome = fetchOnce(payload, user.token);
    if (outcome === 'QUEUED' || outcome === 'RATE_LIMITED' || outcome === 'ERROR') {
      continue; // 未到终态 / 被限流 / 瞬时错误 → 继续轮询
    }
    terminal = outcome;
    break;
  }

  pollCount.add(polls);
  pollWait.add(Date.now() - started);
  if (terminal === 'SUCCESS') {
    e2eSuccess.add(1);
  } else if (terminal === 'FAILED' || terminal === 'MISSING') {
    e2eFailed.add(1);
  } else {
    pollTimeout.add(1);
  }
}
