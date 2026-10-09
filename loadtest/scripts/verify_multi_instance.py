#!/usr/bin/env python3
"""多实例协调性验证：限流全局量 / 号段跨实例唯一 / 缓存击穿互斥 / 会话失效广播。

前提：
  1) 三个后端实例已起（见 infra/docker-compose.multi.yml），端口 8081/8082/8083
  2) 用 `-Dmall.cluster.instance-count=3` 启动（否则本地限流额度不会按实例数分摊）
  3) MySQL 容器 geek-mall-mysql、Redis 容器 geek-mall-redis 在跑

ShedLock 单点执行（V4）需要「制造过期订单 + 轮询 job-lock」，见
docs/benchmark/多实例部署验证-2026-10-08.md；本脚本覆盖可自动化的 V1/V2/V3/V5。

注意：鉴权失败返回的是 HTTP 200 + body `code=401`（见 RestAuthenticationEntryPoint），
因此所有「令牌是否有效」的判断都看 body.code 而非 HTTP 状态。

用法： python3 verify_multi_instance.py
      python3 verify_multi_instance.py --no-v1 --no-v2 --no-v3   # 只跑 V5（登录额度干净）
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
# (缓存名, 请求路径, 是否有本地 L1)  —— L1 白名单见 mall.cache.local.names
CACHE_CASES = [
    ("categoryTree",     "/api/v1/categories/tree",      True),
    ("homeFloors",       "/api/v1/home/floors",          True),
    ("productDetail",    "/api/v1/products/1",           False),
    ("productRecommend", "/api/v1/products/1/recommend", False),
]


def v3_cache_mutex(n):
    print(f"\n=== V3 缓存击穿互斥（逐缓存，{n} 并发跨 3 实例）===")
    print(f"  {'缓存':<17}{'实际 key':<42}{'重建增量':>9}{'锁等待':>8}  结果")
    for name, path, has_l1 in CACHE_CASES:
        prefix = f"mall:cache:{name}"
        # 先预热一次，确保 key 真的存在（同时暴露真实 key 形态）
        http("GET", path, port=PORTS[0])
        time.sleep(0.3)
        keys = [k for k in redis("--scan", "--pattern", prefix + "*").splitlines() if k.strip()]
        before = cache_metrics()
        for k in keys:
            redis("DEL", k)
        if has_l1:
            # 两个 L1 缓存（categoryTree / homeFloors）需等本地 3s 过期，否则读到 L1 测不到互斥
            time.sleep(4)
        blast(n, lambda i: http("GET", path, port=PORTS[i % 3]))
        time.sleep(0.5)
        after = cache_metrics()
        dr = tot(after, "mall_cache_rebuild_total") - tot(before, "mall_cache_rebuild_total")
        lw = tot(after, "mall_cache_lock_wait_total") - tot(before, "mall_cache_lock_wait_total")
        shown = keys[0] if keys else "(未找到 key)"
        print(f"  {name:<17}{shown:<42}{dr:>9.0f}{lw:>8.0f}  "
              f"{'✅ 只回源一次' if dr == 1 else f'❌ 期望 1，实际 {dr:.0f}'}")


# ------------------------------------------------------ V5 会话失效广播
SESSION_CHANNEL = "mall:auth:session:invalidated"


def json_code(body):
    """鉴权失败在 body.code（HTTP 仍是 200），因此统一从这里取业务码。"""
    return body.get("code") if isinstance(body, dict) else None


def profile_code(token, port):
    """探测令牌在该实例上是否仍被接受：0=有效，401=已失效，其它=异常/限流。"""
    _, body = http("GET", "/api/v1/user/profile", token=token, port=port)
    return json_code(body)


def login_buyer(port, account="13800000000", password="123456"):
    """登录演示买家并返回令牌；失败返回 None。"""
    _, body = http("POST", "/api/v1/auth/login",
                   {"account": account, "password": password}, port=port)
    if json_code(body) == 0:
        return (body.get("data") or {}).get("token")
    return None


def clear_login_rate_limit():
    """清掉 auth-login 的分布式计数。

    它属于 DISTRIBUTED 层（全局计数在 Redis），因此可以直接清；
    LOCAL 层（如 auth-logout）的计数在进程内且会被 instance-count 分摊
    （10/60s → 每实例 4 次），清不掉，只能避开——这也是本项选「重新登录」
    而非「登出」做触发点的原因。
    """
    for k in redis("--scan", "--pattern", "mall:rate:auth-login:*").splitlines():
        if k.strip():
            redis("DEL", k.strip())


def v5_session_broadcast(poll_interval=0.25, timeout=6.0):
    """验证会话失效能跨实例立即生效（广播清掉各实例的本地令牌缓存）。

    触发点用「同一账号重新登录」（顶下线）而非「登出」：两者都调用同一条
    `LoginSessionBroadcaster.invalidate(userId)` 广播（见 AuthServiceImpl 的
    issueToken 与 logout），机制完全相同；但 auth-login 是 DISTRIBUTED 层、
    可先清计数，脚本能反复运行。

    鉴权路径有 30s 本地令牌缓存；不广播时其它实例最长 30s 才会拒绝旧令牌。
    这里把观察窗口压到 6s（远小于 30s），于是只有「广播清缓存」能解释结果。
    """
    print("\n=== V5 会话失效跨实例广播（顶下线触发） ===")
    subs = redis("PUBSUB", "NUMSUB", SESSION_CHANNEL).split()
    print(f"  频道 {SESSION_CHANNEL} 订阅者 = {subs[-1] if subs else '?'}（应为实例数 3）")

    # ① 登录取「旧令牌」：保证 Redis 会话一定存在，不依赖 users.csv 里的历史会话
    clear_login_rate_limit()
    old = login_buyer(PORTS[0])
    if not old:
        print("  ❌ 登录失败（若为 429，说明 auth-login 的 IP 额度被占，可 --no-v1 --no-v2 --no-v3 单独跑本项）")
        return

    # ② 预热三实例的本地令牌缓存
    warm = {p: profile_code(old, p) for p in PORTS}
    if any(c != 0 for c in warm.values()):
        print(f"  ❌ 预热未全部成功（期望三实例 code=0）：{warm}")
        return
    print(f"  ① 旧令牌预热三实例本地缓存：/user/profile 均 code=0 {warm}")

    # ③ 跨过 JWT 秒边界后同账号重新登录：新会话覆盖旧会话并广播失效。
    #    JWT 的 iat 精度到秒且无 jti，同一秒内两次登录会签发完全相同的令牌，
    #    因此必须等到下一秒，确保新旧令牌确实不同（否则「顶下线」这个场景不成立）。
    fresh, t0 = old, None
    for _ in range(5):
        time.sleep(1.1)
        clear_login_rate_limit()
        t0 = time.monotonic()
        fresh = login_buyer(PORTS[0]) or old
        if fresh != old:
            break
    if fresh == old or t0 is None:
        print("  ❌ 未能取得不同的新令牌（跨秒重试 5 次仍相同）")
        return
    print("  ② 同账号重新登录（顶下线）：已签发新令牌并广播 session:invalidated（t0 起算）")

    # ④ 其它实例：旧令牌应立即被拒（窗口 6s << 30s TTL），新令牌应可用
    print(f"  ③ 观察其它实例（轮询 {int(poll_interval * 1000)}ms，窗口 {timeout:.0f}s；本地缓存 TTL=30s）")
    for p in PORTS[1:]:
        deadline = t0 + timeout
        ok_at, seen_429, last = None, False, None
        while time.monotonic() < deadline:
            last = profile_code(old, p)
            if last == 401:
                ok_at = time.monotonic() - t0
                break
            if last == 429:
                seen_429 = True
            time.sleep(poll_interval)
        if ok_at is not None:
            print(f"     app{p}: ✅ 旧令牌 {ok_at * 1000:.0f}ms 后 401（本地缓存已被广播清除）"
                  f"；新令牌 code={profile_code(fresh, p)}")
        else:
            hint = "（轮询期间出现 429，建议调大 poll_interval）" if seen_429 else ""
            print(f"     app{p}: ❌ {timeout:.0f}s 内旧令牌仍是 code={last}（未被广播清除）{hint}")

    # ⑤ app1 自身：同实例也应立即失效
    self_code = profile_code(old, PORTS[0])
    print(f"  ④ app1 自身：旧令牌 code={self_code}"
          f" {'✅' if self_code == 401 else '❌'}（t={time.monotonic() - t0:.2f}s）")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--item", type=int, default=1, help="V2 使用的秒杀商品 ID")
    ap.add_argument("--no-v1", action="store_true")
    ap.add_argument("--no-v2", action="store_true")
    ap.add_argument("--no-v3", action="store_true")
    ap.add_argument("--no-v5", action="store_true")
    args = ap.parse_args()

    if not args.no_v1:
        v1_rate_limit(load_users(1)[0]["token"])
    if not args.no_v2:
        v2_order_no(args.item, 300)
    if not args.no_v3:
        v3_cache_mutex(30)
    if not args.no_v5:
        v5_session_broadcast()


if __name__ == "__main__":
    main()
