// ============================================================
// 秒杀抢购压测（k6）
//
// 目的：用 Go 实现的 k6 替代 JMeter，压测机的 CPU/内存开销小一个数量级，
//       从而消除「压测机与被测系统同机争抢 CPU」带来的延迟噪声。
//
// 用法：
//   ITEM_ID=1 VUS=200 ITERATIONS=100 \
//     k6 run --summary-export=results/k6-seckill.summary.json loadtest/k6/seckill.js
//
// 参数（环境变量）：
//   BASE_URL    被测地址，默认 http://localhost:8080
//   ITEM_ID     秒杀商品 ID，默认 1
//   VUS         并发虚拟用户数，默认 200
//   ITERATIONS  每个 VU 的迭代次数，默认 100（总请求 = VUS × ITERATIONS）
// ============================================================
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { userForVU } from './lib/users.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ITEM_ID = __ENV.ITEM_ID || '1';
const VUS = Number(__ENV.VUS || 200);
const ITERATIONS = Number(__ENV.ITERATIONS || 100);

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
const orderSuccess = new Counter('order_success');
const orderRejected = new Counter('order_rejected'); // 已抢光 / 限购
const rateLimited = new Counter('rate_limited');     // HTTP 429
const serverError = new Counter('server_error');     // 5xx 或 code=9999

// 分开记录两类请求的耗时，便于解释「拒绝路径快、建单路径受行锁串行」
const orderLatency = new Trend('order_latency', true);
const rejectLatency = new Trend('reject_latency', true);

export default function () {
  const user = userForVU(__VU);
  const res = http.post(
    `${BASE_URL}/api/v1/seckill/${ITEM_ID}/order`,
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
  try {
    code = res.json('code');
  } catch (e) {
    serverError.add(1);
    return;
  }

  if (code === 0) {
    orderSuccess.add(1);
    orderLatency.add(res.timings.duration);
  } else if (code === 9999) {
    serverError.add(1);
  } else {
    orderRejected.add(1);
    rejectLatency.add(res.timings.duration);
  }
}
