// ============================================================
// 长稳压测（k6）：恒定到达率打读链路，持续 30 分钟
//
// 目标不是峰值容量，而是暴露「跑一会儿才出问题」的缺陷：
//   · 堆内存是否单调上涨（缓存/L1 泄漏、日志对象堆积）
//   · HikariCP active/pending 是否漂移（连接泄漏）
//   · Tomcat busy 线程是否持续贴顶（慢下游）
//   · Redis 内存是否随缓存 key 增长失控
//
// 用法：
//   RATE=150 DURATION=30m k6 run loadtest/k6/soak-read.js
//   （配合 loadtest/scripts/soak_monitor.py 采样服务端指标）
// ============================================================
import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 150);
const DURATION = __ENV.DURATION || '30m';

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  http.get(`${BASE_URL}/api/v1/home/floors`, { tags: { name: 'home-floors' } });
  const pid = 1 + ((__VU + __ITER) % 13);
  http.get(`${BASE_URL}/api/v1/products/${pid}`, { tags: { name: 'product-detail' } });
}
