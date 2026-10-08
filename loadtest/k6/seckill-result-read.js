// ============================================================
// 抢购结果查询接口压测 —— 纯读（replay）
//
// 对应方案文档 P0-5 的第二半：**接口本身的承载上限**。
//
// 与 `seckill-result.js`（live）的区别：
//   live   —— 先抢购再轮询，测「真实放大倍数」与端到端等待；
//   replay —— 用已生成的 requestId 池只打 GET，测这条读路径**能扛多少 QPS、延迟多少、
//             Redis 压力多大**。轮询流量是「客户端数 × 轮询频率」产生的，
//             用到达率模型（constant-arrival-rate）可以直接标定承载线。
//
// ⚠️ 前置（在 A 机执行）：
//   1. 先跑一轮削峰模式秒杀压测（SECKILL_ASYNC_ENABLED=true），产生结果记录；
//   2. python3 loadtest/scripts/collect_seckill_results.py
//      → 生成 loadtest/k6/data/seckill-results.js（含各 requestId 的**属主 token**，
//        因为查询接口会校验归属，拿别人的 token 查会全部 404）
//   3. 结果 key 有 TTL（默认 30m），采集后要**尽快**压，否则大量 request_not_found。
//
// 用法（B 机）：
//   RATE=500 DURATION=60s k6 run \
//     -e BASE_URL=http://<A_IP>:8080 \
//     --summary-export=results/seckill-result-read.json \
//     loadtest/k6/seckill-result-read.js
//
// 参数（环境变量）：
//   BASE_URL     被测地址，默认 http://localhost:8080
//   RATE         每秒轮询请求数，默认 500
//   DURATION     持续时长，默认 60s
//   PRE_VUS      预分配 VU 数，默认 100（GET 很快，够用）
//   MAX_VUS      VU 上限，默认 500
//   LABEL        报告标签（仅注释用途）
//
// 判读：
//   result_server_error 必须为 0（已设 threshold，非 0 时 k6 直接判定失败）；
//   result_not_found 占比高 ⇒ 数据已过期，重新采集即可，不是接口问题；
//   result_rate_limited 出现 ⇒ 打到限流了，说明已触到保护线（这是保护生效，不是故障）。
// ============================================================
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { results as generated } from './data/seckill-results.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 500);
const DURATION = __ENV.DURATION || '60s';
const PRE_VUS = Number(__ENV.PRE_VUS || 100);
const MAX_VUS = Number(__ENV.MAX_VUS || 500);

// SharedArray：数据只在 init 阶段构建一次，各 VU 共享只读副本
const pool = new SharedArray('seckill-results', function () {
  return generated;
});

if (pool.length === 0) {
  throw new Error('loadtest/k6/data/seckill-results.js 为空，请先在 A 机运行 '
    + 'loadtest/scripts/collect_seckill_results.py 采集 requestId');
}

export const options = {
  scenarios: {
    poll: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(99)'],
  thresholds: {
    // 系统错误率验收线：查询结果接口不该出现 5xx（方案文档第五节）
    result_server_error: ['count==0'],
  },
};

const resultOk = new Counter('result_success');
const resultQueued = new Counter('result_queued');
const resultNotFound = new Counter('result_not_found');
const resultRateLimited = new Counter('result_rate_limited');
const resultServerError = new Counter('result_server_error');
const resultLatency = new Trend('result_latency', true);

export default function () {
  // 用 VU 号 + 迭代号在池里游走：既保证同一 VU 均匀覆盖全部 requestId，
  // 又避免所有 VU 同时打同一条（那会测成单 key 热点，而不是接口吞吐）
  const entry = pool[(__VU + __ITER) % pool.length];

  const res = http.get(`${BASE_URL}/api/v1/seckill/result/${entry.requestId}`, {
    headers: { Authorization: `Bearer ${entry.token}` },
    tags: { name: 'seckill-result' },
  });
  resultLatency.add(res.timings.duration);

  if (res.status === 429) {
    resultRateLimited.add(1);
    return;
  }
  if (res.status >= 500) {
    resultServerError.add(1);
    return;
  }

  let body = null;
  try {
    body = res.json();
  } catch (e) {
    resultServerError.add(1);
    return;
  }
  if (body.code === 9999) {
    resultServerError.add(1);
    return;
  }
  if (body.code !== 0) {
    resultNotFound.add(1);
    return;
  }

  resultOk.add(1);
  if (body.data && body.data.status === 'QUEUED') {
    resultQueued.add(1);
  }
}
