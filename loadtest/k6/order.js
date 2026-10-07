// ============================================================
// 普通下单链路压测（k6）—— 补齐治理清单「下单（非热点 SKU）TPS ≥ 300」
//
// 业务流程：加购（POST /cart/items）→ 提交订单（POST /trade/orders）
// 说明：接口没有「立即购买」，所以按真实用户路径构造，一次迭代 = 加购 + 下单。
//
// 刻意排除秒杀热点商品 1、6，保证测的是「非热点 SKU」的普通下单能力。
//
// 用法：
//   VUS=200 ITERATIONS=50 \
//     k6 run --summary-export=results/k6-order.summary.json loadtest/k6/order.js
// ============================================================
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { userForVU } from './lib/users.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 200);
const ITERATIONS = Number(__ENV.ITERATIONS || 50);

/** 非热点 SKU 池：排除秒杀热点商品 1（iPhone 15 Pro Max）与 6（红米 K70）。 */
const PRODUCTS = (__ENV.PRODUCTS || '2,3,4,5,7,8,9,10,11,12,13,14')
  .split(',')
  .map((x) => Number(x.trim()))
  .filter((x) => !Number.isNaN(x));

export const options = {
  scenarios: {
    order: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: __ENV.MAX_DURATION || '10m',
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(99)'],
  thresholds: {},
};

// ---- 业务口径指标 ----
const orderSuccess = new Counter('order_success');   // 下单成功
const orderFail = new Counter('order_fail');         // 下单业务失败（库存不足/购物车空等）
const addCartFail = new Counter('add_cart_fail');    // 加购失败
const rateLimited = new Counter('rate_limited');     // HTTP 429
const serverError = new Counter('server_error');     // 5xx 或 code=9999

/** 下单接口本身的耗时（不含加购），是「下单链路 TPS」对应的延迟口径。 */
const submitLatency = new Trend('submit_latency', true);
/** 下单接口的服务端首字节时间（剥离客户端开销，用于判断延迟归属） */
const submitTtfb = new Trend('submit_ttfb', true);

export default function () {
  const user = userForVU(__VU);
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${user.token}`,
  };
  const productId = PRODUCTS[(__VU + __ITER) % PRODUCTS.length];

  // 1) 加购
  const add = http.post(
    `${BASE_URL}/api/v1/cart/items`,
    JSON.stringify({ productId, qty: 1 }),
    { headers, tags: { name: 'add-cart' } }
  );
  if (add.status === 429) {
    rateLimited.add(1);
    return;
  }
  if (add.status >= 500) {
    serverError.add(1);
    addCartFail.add(1);
    return;
  }
  const cartItemId = add.json('data');
  if (!cartItemId) {
    addCartFail.add(1);
    return;
  }

  // 2) 提交订单（requestId 唯一，避免被幂等拦截）
  const requestId = `ord-${__VU}-${__ITER}-${Date.now()}`;
  const submit = http.post(
    `${BASE_URL}/api/v1/trade/orders`,
    JSON.stringify({
      addressId: Number(user.addressId),
      cartItemIds: [cartItemId],
      requestId,
      payMethod: 'wechat',
    }),
    { headers, tags: { name: 'submit-order' } }
  );

  if (submit.status === 429) {
    rateLimited.add(1);
    orderFail.add(1);
    return;
  }
  if (submit.status >= 500) {
    serverError.add(1);
    orderFail.add(1);
    return;
  }

  let code = null;
  try {
    code = submit.json('code');
  } catch (e) {
    serverError.add(1);
    orderFail.add(1);
    return;
  }

  if (code === 0) {
    orderSuccess.add(1);
    submitLatency.add(submit.timings.duration);
    submitTtfb.add(submit.timings.waiting);
  } else if (code === 9999) {
    serverError.add(1);
    orderFail.add(1);
  } else {
    orderFail.add(1);
  }
}
