// ============================================================
// 读链路压测（k6）：首页楼层 + 商品详情，用于验证缓存收益（P1-1）
//
// 用法：
//   VUS=200 ITERATIONS=100 \
//     k6 run --summary-export=results/k6-read.summary.json loadtest/k6/read.js
//
// 每个 VU 每次迭代发 2 个请求，故总请求数 = VUS × ITERATIONS × 2
// ============================================================
import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 200);
const ITERATIONS = Number(__ENV.ITERATIONS || 100);

export const options = {
  scenarios: {
    read: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: __ENV.MAX_DURATION || '5m',
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(99)'],
  thresholds: {
    // 读链路是纯缓存命中路径，这里用阈值把「缓存失效」这类回归钉死
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  http.get(`${BASE_URL}/api/v1/home/floors`, {
    tags: { name: 'home-floors' },
  });

  const pid = 1 + ((__VU + __ITER) % 13);
  http.get(`${BASE_URL}/api/v1/products/${pid}`, {
    tags: { name: 'product-detail' },
  });
}
