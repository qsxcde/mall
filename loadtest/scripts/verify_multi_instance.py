#!/usr/bin/env python3
"""多实例协调性验证：限流全局量 / 号段跨实例唯一 / 缓存击穿互斥。

前提：
  1) 三个后端实例已起（见 infra/docker-compose.multi.yml），端口 8081/8082/8083
  2) 用 `-Dmall.cluster.instance-count=3` 启动（否则本地限流额度不会按实例数分摊）
  3) MySQL 容器 geek-mall-mysql、Redis 容器 geek-mall-redis 在跑

ShedLock 单点执行（V4）需要「制造过期订单 + 轮询 job-lock」，见
docs/benchmark/多实例部署验证-2026-10-08.md；本脚本覆盖可自动化的 V1/V2/V3。

用法： python3 verify_multi_instance.py
"""
import argparse
import csv
import json
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request
from collections import Counter

PORTS = [8081, 8082, 8083]
ROOT = "/Users/wangjie/CodeBuddy/demo"
USERS_CSV = f"{ROOT}/loadtest/data/users.csv"


def sh(argv):
    return subprocess.run(argv, capture_output=True, text=True).stdout


def redis(*args):
    return sh(["docker", "exec", "geek-mall-redis", "redis-cli", *args])


def http(method, path, body=None, token=None, port=None, timeout=20):
    """返回 (http_status, 解析后的 body dict 或 None)。"""
    port = port or PORTS[0]
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"http://localhost:{port}{path}", data=data,
                                 headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            try:
                return r.status, json.loads(r.read())
            except Exception:
                return r.status, None
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception:
        return 0, None


def blast(total, fn):
    res, lock = [], threading.Lock()

    def worker(i):
        out = fn(i)
        with lock:
            res.append(out)

    ts = [threading.Thread(target=worker, args=(i,)) for i in range(total)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    return res


def load_users(n):
    with open(USERS_CSV, encoding="utf-8") as f:
        return [r for i, r in enumerate(csv.DictReader(f)) if i < n]


def cache_metrics():
    out = {}
    for p in PORTS:
        txt = sh(["curl", "-s", "-m", "3", f"http://localhost:{p}/actuator/prometheus"])
        vals = {}
        for line in txt.splitlines():
            if line.startswith("mall_cache_"):
                m = re.match(r"^(mall_cache_\w+)(\{[^}]*\})?\s+([\d.eE+]+)$", line)
                if m:
                    vals[m.group(1)] = vals.get(m.group(1), 0.0) + float(m.group(3))
        out[p] = vals
    return out


def tot(m, name):
    return sum(v.get(name, 0.0) for v in m.values())


# ---------------------------------------------------------------- V1 限流
def v1_rate_limit(token):
    print("=== V1 限流全局量 ===")
    # V1a：DISTRIBUTED 层 10 次/60s(IP) —— 全局计数，不应被实例数放大
    res = blast(60, lambda i: http("POST", "/api/v1/auth/login",
                                   {"account": "13900000001", "password": "bad"}, port=PORTS[i % 3]))
    st = Counter(s for s, _ in res)
    ok = st.get(429, 0) == 50
    print(f"  V1a auth-login(DISTRIBUTED 10/60s IP): 总=60 放行={st.get(200,0)} 429={st.get(429,0)}"
          f"  {'✅ 全局恰好 10（未被 3 实例放大）' if ok else '❌ 期望 50 个 429'}")

    # V1b：LOCAL 层 60 次/10s(USER) —— 应被 instance-count=3 分摊为每实例 20
    res = blast(180, lambda i: http("GET", "/api/v1/cart/items", token=token, port=PORTS[i % 3]))
    st = Counter(s for s, _ in res)
    ok = st.get(200, 0) == 60
    print(f"  V1b cart-items(LOCAL 60/10s USER):     总=180 放行={st.get(200,0)} 429={st.get(429,0)}"
          f"  {'✅ 全局 60 = 每实例 20 × 3（不分摊会是 180）' if ok else f'❌ 期望放行 60，实际 {st.get(200,0)}'}")


# ------------------------------------------------------------ V2 号段发号
def v2_order_no(item_id, n):
    print("\n=== V2 号段发号跨实例唯一 ===")
    users = load_users(n)
    before = redis("GET", "mall:order:no:segment").strip()
    res = blast(len(users), lambda i: http("POST", f"/api/v1/seckill/{item_id}/order",
                                           {"addressId": int(users[i]["addressId"])},
                                           token=users[i]["token"], port=PORTS[i % 3]))
    codes = Counter((b or {}).get("code") for _, b in res)
    order_nos = [(b or {}).get("data") for _, b in res
                 if (b or {}).get("code") == 0 and isinstance((b or {}).get("data"), str)]
    after = redis("GET", "mall:order:no:segment").strip()
    print(f"  请求={len(users)}  业务码分布={dict(codes)}")
    unique_ok = len(order_nos) == len(set(order_nos))
    print(f"  返回订单号 {len(order_nos)} 个 / 去重后 {len(set(order_nos))} 个 → {'✅ 无重复' if unique_ok else '❌ 有重复'}")
    if before and after:
        delta = int(after) - int(before)
        # 号段按 1000 一段缓存在本地，只有耗尽才再 INCRBY：
        #   · 实例刚启动后首次下单 → 每实例各领 1 段（3 实例 = +3000）
        #   · 紧接着重复压测     → 本地还有余号，delta 可能为 0（这是设计，不是缺陷）
        note = "（本地号段未耗尽，本次未重新申请——符合「每 1000 单才出网一次」的设计）" if delta == 0 else ""
        ok = delta >= 0 and delta % 1000 == 0
        print(f"  号段计数器 {before} → {after}（增量 {delta}，应为 1000 的整数倍）"
              f" → {'✅ 段区间互不重叠' if ok else '❌ 异常'} {note}")


# ---------------------------------------------------------- V3 缓存互斥
def v3_cache_mutex(n):
    print("\n=== V3 缓存击穿互斥（productDetail：唯一使用 sync=true 的缓存）===")
    key = "mall:cache:productDetail::1"
    before = cache_metrics()
    redis("DEL", key)
    blast(n, lambda i: http("GET", "/api/v1/products/1", port=PORTS[i % 3]))
    time.sleep(1)
    after = cache_metrics()
    dr = tot(after, "mall_cache_rebuild_total") - tot(before, "mall_cache_rebuild_total")
    lw = tot(after, "mall_cache_lock_wait_total") - tot(before, "mall_cache_lock_wait_total")
    print(f"  清空 {key} 后并发 {n} 请求（轮流打 3 实例）")
    print(f"  全局回源重建增量={dr}  锁等待增量={lw}"
          f"  → {'✅ 3 实例并发只回源 DB 一次' if dr == 1 else f'❌ 期望 1，实际 {dr}'}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--item", type=int, default=1, help="V2 使用的秒杀商品 ID")
    ap.add_argument("--no-v1", action="store_true")
    ap.add_argument("--no-v2", action="store_true")
    ap.add_argument("--no-v3", action="store_true")
    args = ap.parse_args()

    if not args.no_v1:
        v1_rate_limit(load_users(1)[0]["token"])
    if not args.no_v2:
        v2_order_no(args.item, 300)
    if not args.no_v3:
        v3_cache_mutex(30)


if __name__ == "__main__":
    main()
