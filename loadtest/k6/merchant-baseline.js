// ============================================================
// 商家端读接口基线压测（k6）
//
// 补的是《测试补充项》P1-5 的空白：商家端 34 个接口此前零压测。
// 这里覆盖 8 个高频读入口（看板、角标、商品/订单/售后/评价/发货列表、资金汇总），
// 写接口（批量改价、发货、审批）不在本脚本内 —— 它们有独立的写限流。
//
// 用法：
//   VUS=50 ITERATIONS=20 k6 run loadtest/k6/merchant-baseline.js
// ============================================================
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 50);
const ITERATIONS = Number(__ENV.ITERATIONS || 20);

export const options = {
  scenarios: {
    merchant: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: '5m',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

const PAGES = [
  '/api/v1/merchant/overview',
  '/api/v1/merchant/nav/badges',
  '/api/v1/merchant/product/page?page=1&pageSize=10',
  '/api/v1/merchant/order/page?page=1&pageSize=10',
  '/api/v1/merchant/aftersale/page?page=1&pageSize=10',
  '/api/v1/merchant/review/page?page=1&pageSize=10',
  '/api/v1/merchant/shipment/page?page=1&pageSize=10',
  '/api/v1/merchant/fund/summary',
];

export function setup() {
  const res = http.post(
    `${BASE_URL}/api/v1/merchant/auth/login`,
    JSON.stringify({ account: 'merchant', password: '123456' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'merchant-login' } },
  );
  const body = res.json();
  const token = body && body.data && body.data.token;
  if (!token) {
    throw new Error(`商家登录失败（${res.status}）：${res.body}`);
  }
  return { token };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };
  for (const path of PAGES) {
    const res = http.get(`${BASE_URL}${path}`, {
      headers,
      tags: { name: path.split('?')[0] },
    });
    check(res, { 'status=200': (r) => r.status === 200 });
  }
}
