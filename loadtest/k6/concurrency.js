// ============================================================
// 并发正确性测试（k6）
//
// 三个场景（用 SCENARIO 环境变量切换）：
//   idempotent —— 同一 requestId 并发提交 100 次，期望只生成 1 张订单（P1-4）
//   cancel     —— 同一订单并发取消 100 次，期望库存只回退 1 次（P0-4）
//   oversell   —— N 个用户并发抢 1 件秒杀商品，期望订单数 == 发放库存、不超卖（P0-2/P2-6）
//
// 用法：
//   FIXED_TOKEN=xxx SCENARIO=idempotent k6 run loadtest/k6/concurrency.js
//   FIXED_TOKEN=xxx SCENARIO=cancel     k6 run loadtest/k6/concurrency.js
//   SCENARIO=oversell VUS=500 ITEM_ID=2 k6 run loadtest/k6/concurrency.js
//
// 结果由 loadtest/scripts/verify_results.py 从数据库侧判定（脚本侧只做快速失败兜底）。
// ============================================================
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { userForVU } from './lib/users.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'idempotent';
const FIXED_TOKEN = __ENV.FIXED_TOKEN || '';
const FIXED_ADDRESS_ID = Number(__ENV.FIXED_ADDRESS_ID || 1);
const ITEM_ID = __ENV.ITEM_ID || '2';
const VUS = Number(__ENV.VUS || (SCENARIO === 'oversell' ? 500 : 100));

/** 幂等键固定为 IDEM-FIXED，与 verify_results.py 的断言保持一致。 */
const IDEM_REQUEST_ID = 'IDEM-FIXED';

const serverError = new Counter('server_error');
const businessOk = new Counter('business_ok');

function buildOptions() {
  // per-vu-iterations：每个 VU 各跑 1 轮。幂等/取消场景 = 100 个请求同时打同一目标；
  // 防超卖场景 = 每个 VU 用不同账号各抢一次（保证一人一单）。
  return {
    scenarios: {
      run: {
        executor: 'per-vu-iterations',
        vus: VUS,
        iterations: 1,
        maxDuration: __ENV.MAX_DURATION || '3m',
        gracefulStop: '5s',
      },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(99)'],
    thresholds: {},
  };
}

export const options = buildOptions();

/** 准备阶段：造购物车项 / 造一张待取消的订单，并把关键 ID 传给 VU。 */
export function setup() {
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${FIXED_TOKEN}`,
  };

  if (SCENARIO === 'idempotent') {
    const res = http.post(
      `${BASE_URL}/api/v1/cart/items`,
      JSON.stringify({ productId: 5, qty: 1 }),
      { headers }
    );
    return { cartItemId: res.json('data') };
  }

  if (SCENARIO === 'cancel') {
    const cart = http.post(
      `${BASE_URL}/api/v1/cart/items`,
      JSON.stringify({ productId: 3, qty: 1 }),
      { headers }
    );
    const cartItemId = cart.json('data');
    const order = http.post(
      `${BASE_URL}/api/v1/trade/orders`,
      JSON.stringify({
        addressId: FIXED_ADDRESS_ID,
        cartItemIds: [cartItemId],
        requestId: `CANCEL-PREP-${Date.now()}`,
        payMethod: 'wechat',
      }),
      { headers }
    );
    const orderNo = order.json('data');
    console.log(`cancel-test orderNo = ${orderNo}`);
    return { orderNo };
  }

  return {};
}

export default function (data) {
  if (SCENARIO === 'idempotent') {
    const res = http.post(
      `${BASE_URL}/api/v1/trade/orders`,
      JSON.stringify({
        addressId: FIXED_ADDRESS_ID,
        cartItemIds: [data.cartItemId],
        requestId: IDEM_REQUEST_ID,
        payMethod: 'wechat',
      }),
      {
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${FIXED_TOKEN}`,
        },
        tags: { name: 'submit-same-request-id' },
      }
    );
    countResult(res);
    return;
  }

  if (SCENARIO === 'cancel') {
    const res = http.post(
      `${BASE_URL}/api/v1/trade/orders/${data.orderNo}/cancel`,
      JSON.stringify({ reason: 'concurrency-cancel' }),
      {
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${FIXED_TOKEN}`,
        },
        tags: { name: 'cancel-same-order' },
      }
    );
    countResult(res);
    return;
  }

  // oversell：每个 VU 用固定账号抢一次
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
  countResult(res);
}

function countResult(res) {
  if (res.status >= 500 || res.status === 429) {
    serverError.add(1);
    check(res, { 'no 5xx / no 429': (r) => r.status < 500 && r.status !== 429 });
    return;
  }
  let code = null;
  try {
    code = res.json('code');
  } catch (e) {
    serverError.add(1);
    return;
  }
  // code=9999 才是系统异常；1000/2001/「状态已变更」等均为预期业务结果
  if (code === 9999) {
    serverError.add(1);
    check(res, { 'no system error': () => false });
    return;
  }
  businessOk.add(1);
}
