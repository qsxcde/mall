// ============================================================
// 同轮并发：秒杀瞬时洪峰 + 普通下单持续流量
//
// 目的：验证治理清单「秒杀峰值不拖垮普通下单接口」——
//       在秒杀洪峰打进来的同一时刻，普通下单链路的延迟/成功率是否被拖垮。
//
// 做法：两个 k6 scenario 并行跑，用 exec.scenario.name 分流到不同的业务逻辑与指标。
//       秒杀 scenario 在 NORMAL 稳定运行一段时间后（startTime）才启动，形成"洪峰撞上来"的效果。
//
// 用法：
//   NORMAL_RATE=150 DURATION=30s SECKILL_START=12s SECKILL_VUS=1000 ITEM_ID=1 \
//     k6 run --summary-export=... --out csv=... loadtest/k6/order-vs-seckill.js
// ============================================================
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { userForVU } from './lib/users.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const DURATION = __ENV.DURATION || '30s';
const NORMAL_RATE = Number(__ENV.NORMAL_RATE || 150);      // 普通下单到达率（次/秒）
const SECKILL_VUS = Number(__ENV.SECKILL_VUS || 1000);     // 秒杀洪峰并发
const SECKILL_START = __ENV.SECKILL_START || '12s';
const ITEM_ID = __ENV.ITEM_ID || '1';

/** 非热点 SKU 池（排除秒杀热点 1、6） */
const PRODUCTS = (__ENV.PRODUCTS || '2,3,4,5,7,8,9,10,11,12,13,14')
  .split(',')
  .map((x) => Number(x.trim()))
  .filter((x) => !Number.isNaN(x));

export const options = {
  scenarios: {
    // 普通下单：恒定的下单到达率，持续时间覆盖住秒杀洪峰
    normal: {
      executor: 'constant-arrival-rate',
      rate: NORMAL_RATE,
      timeUnit: '1s',
      duration: DURATION,
      startTime: '0s',
      preAllocatedVUs: Math.max(50, Math.ceil(NORMAL_RATE / 4)),
      maxVUs: Math.max(200, NORMAL_RATE * 2),
      gracefulStop: '15s',
      tags: { path: 'normal' },
    },
    // 秒杀洪峰：一次性齐发
    seckill: {
      executor: 'per-vu-iterations',
      vus: SECKILL_VUS,
      iterations: 1,
      startTime: SECKILL_START,
      maxDuration: '1m',
      gracefulStop: '10s',
      tags: { path: 'seckill' },
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(99)'],
  thresholds: {},
};

// ---- 普通下单侧 ----
const orderSuccess = new Counter('order_success');
const orderFail = new Counter('order_fail');
const orderServerError = new Counter('order_server_error');
const submitLatency = new Trend('submit_latency', true);
/** 下单接口的服务端首字节时间（剥离客户端开销，用于判断延迟归属） */
const submitTtfb = new Trend('submit_ttfb', true);
const addCartLatency = new Trend('add_cart_latency', true);

// ---- 秒杀侧 ----
const seckillSuccess = new Counter('seckill_success');
const seckillReject = new Counter('seckill_reject');
const seckillServerError = new Counter('seckill_server_error');
const seckillLatency = new Trend('seckill_latency', true);

export default function () {
  if (exec.scenario.name === 'seckill') {
    doSeckill();
  } else {
    doNormalOrder();
  }
}

function doNormalOrder() {
  const user = userForVU(__VU);
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${user.token}`,
  };
  const productId = PRODUCTS[(__VU + __ITER) % PRODUCTS.length];

  const add = http.post(
    `${BASE_URL}/api/v1/cart/items`,
    JSON.stringify({ productId, qty: 1 }),
    { headers, tags: { name: 'add-cart' } }
  );
  if (add.status >= 400) {
    orderServerError.add(add.status >= 500 ? 1 : 0);
    orderFail.add(1);
    return;
  }
  addCartLatency.add(add.timings.duration);
  const cartItemId = add.json('data');
  if (!cartItemId) {
    orderFail.add(1);
    return;
  }

  const submit = http.post(
    `${BASE_URL}/api/v1/trade/orders`,
    JSON.stringify({
      addressId: Number(user.addressId),
      cartItemIds: [cartItemId],
      requestId: `mix-${__VU}-${__ITER}-${Date.now()}`,
      payMethod: 'wechat',
    }),
    { headers, tags: { name: 'submit-order' } }
  );

  if (submit.status >= 500) {
    orderServerError.add(1);
    orderFail.add(1);
    return;
  }
  if (submit.status >= 400) {
    orderFail.add(1);
    return;
  }

  let code = null;
  try {
    code = submit.json('code');
  } catch (e) {
    orderServerError.add(1);
    orderFail.add(1);
    return;
  }
  if (code === 0) {
    orderSuccess.add(1);
    submitLatency.add(submit.timings.duration);
    submitTtfb.add(submit.timings.waiting);
  } else if (code === 9999) {
    orderServerError.add(1);
    orderFail.add(1);
  } else {
    orderFail.add(1);
  }
}

function doSeckill() {
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
    seckillReject.add(1);
    return;
  }
  if (res.status >= 500) {
    seckillServerError.add(1);
    return;
  }
  seckillLatency.add(res.timings.duration);

  let code = null;
  try {
    code = res.json('code');
  } catch (e) {
    seckillServerError.add(1);
    return;
  }
  if (code === 0) {
    seckillSuccess.add(1);
  } else if (code === 9999) {
    seckillServerError.add(1);
  } else {
    seckillReject.add(1);
  }
}
