#!/usr/bin/env python3
"""
L1 本地缓存一致性实测（V7）。

回答的是「多实例下，实例 A 改了数据，实例 B 还要多久才看得到」——
也就是本地 Caffeine L1 带来的**陈旧窗口**到底有多长、上界由什么决定。

机制前提（见 ResilientRedisCache / CacheProperties）：
  · L1 只对 mall.cache.local.names 白名单缓存生效，默认 homeFloors + categoryTree
  · L1 用 expireAfterWrite（读不续期）→ 持续读不会延长陈旧窗口，上界 = mall.cache.local.ttl
  · 商家改价的 @CacheEvict(allEntries=true) 只清**本实例** L1 + 共享 Redis，
    其它实例的 L1 会继续服务至多 mall.cache.local.ttl

用例：
  L1-1  最坏情形窗口（homeFloors，有 L1）  B 读填 L1 → 立刻经 A 改价 → 轮询 B
  L1-2  对照组（productDetail，无 L1）      同协议，价格敏感数据不进 L1，应当近乎立即生效
  L1-3  随机间隔分布（homeFloors）          warm 读与写之间随机 gap，看 W+gap 是否恒为 TTL
  L1-4  长尾：写时高并发读 + 再缓存污染     写后必须收敛，且 Redis 缓存值 == DB 值
  L1-5  TTL 缩放反证                       改 mall.cache.local.ttl 后窗口应随之缩放
                                          （由调用方重建实例后跑 --only l1-1 --ttl 1）

用法（需先起 3 实例，见 infra/docker-compose.multi.yml）：
    python3 loadtest/scripts/verify_cache_l1.py                # L1-1 ~ L1-4
    python3 loadtest/scripts/verify_cache_l1.py --only l1-1
    python3 loadtest/scripts/verify_cache_l1.py --only l1-1 --ttl 1   # L1-5：TTL=1s 时
"""
import argparse
import base64
import json
import os
import random
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENV_FILE = ROOT / "infra" / ".env"

# 3 个实例在宿主机上的端口（见 infra/docker-compose.multi.yml 的 port 映射）
PORTS = [8081, 8082, 8083]
A, B, C = PORTS          # A 写、B 读、C 只在 L1-4 里凑并发

POLL = 0.05              # 轮询粒度（秒），陈旧窗口的测量分辨率
WRITE_SPACING = 2.0      # 两次改价的最小间隔：merchant-write 是 20/10s SUBJECT(LOCAL，分摊后约 6/10s/实例)
TOLERANCE = 0.6          # 断言容差（秒）：吸收改价请求自身耗时与 Caffeine 过期判定抖动


# ---------------------------------------------------------------- 基础设施


def load_env():
    env = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip().strip('"').strip("'")
    return env


ENV = load_env()

REDIS_PASSWORD = os.environ.get("REDIS_PASSWORD", ENV.get("REDIS_PASSWORD", ""))
MYSQL_PASSWORD = os.environ.get("MYSQL_ROOT_PASSWORD", ENV.get("MYSQL_ROOT_PASSWORD", "root"))
OBS_PASSWORD = os.environ.get("MALL_OBS_PASSWORD", ENV.get("MALL_OBS_PASSWORD", ""))


def sh(argv):
    # stderr 并入 stdout：应用日志在容器里走 stderr，只取 stdout 会拿到空
    return subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True).stdout


def redis(*args):
    argv = ["docker", "exec", "geek-mall-redis", "redis-cli"]
    if REDIS_PASSWORD:
        argv += ["-a", REDIS_PASSWORD, "--no-auth-warning"]
    return sh(argv + list(args))


def mysql(sql):
    # 用 MYSQL_PWD 传口令而不是 -p：后者会往 stderr 打告警，会混进 sh() 的结果集
    return subprocess.run(
        ["docker", "exec", "-i", "-e", f"MYSQL_PWD={MYSQL_PASSWORD}",
         "geek-mall-mysql", "mysql", "-uroot", "-N", "geek_mall", "-e", sql],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    ).stdout


def http(method, path, body=None, token=None, port=None, timeout=20):
    """返回 (http_status, 解析后的 body 或 None)。"""
    port = port or PORTS[0]
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        f"http://localhost:{port}{path}", data=data, headers=headers, method=method
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            try:
                return resp.status, json.loads(resp.read())
            except Exception:
                return resp.status, None
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception:
        return 0, None


def obs_metrics(port, name):
    """抓单实例的 Prometheus 指标，返回 {label字典: 累计值}。

    指标端点要求观测账号 HTTP Basic（mall.observability.scrape-password）。
    """
    req = urllib.request.Request(f"http://localhost:{port}/actuator/prometheus")
    if OBS_PASSWORD:
        token = base64.b64encode(f"obs:{OBS_PASSWORD}".encode()).decode()
        req.add_header("Authorization", f"Basic {token}")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            text = resp.read().decode()
    except Exception:
        return {}
    out = {}
    for line in text.splitlines():
        m = re.match(r'^(\w+)(\{[^}]*\})?\s+([\d.eE+-]+)$', line)
        if not m or m.group(1) != name:
            continue
        labels = dict(re.findall(r'(\w+)="([^"]*)"', m.group(2) or ""))
        out[tuple(sorted(labels.items()))] = out.get(tuple(sorted(labels.items())), 0.0) + float(m.group(3))
    return out


def hits(port, cache, level):
    """按 cache / level 标签取累计值。

    注意指标上还带 common tag（如 application=geek-mall-server），
    所以这里做「包含」匹配而不是精确匹配 label 组合。
    """
    want = {"cache": cache, "level": level}
    total = 0.0
    for labels, value in obs_metrics(port, "mall_cache_hits_total").items():
        if all(dict(labels).get(k) == v for k, v in want.items()):
            total += value
    return total


def wait_healthy(timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if all(http("GET", "/api/v1/home/floors", port=p)[0] == 200 for p in PORTS):
            return True
        time.sleep(1)
    return False


# ------------------------------------------------------------------ 业务探针


def merchant_token():
    status, body = http("POST", "/api/v1/merchant/auth/login",
                        {"account": "merchant", "password": "123456"}, port=A)
    if status == 200 and (body or {}).get("code") == 0:
        return (body.get("data") or {}).get("token")
    raise SystemExit(f"商家登录失败：http={status} body={body!r}（先确认演示商家账号存在）")


def pick_product():
    """取销量最高的在售商品 —— homeFloors 的 hotProducts 是「在售按销量 top8」，它必在其中。"""
    out = mysql("SELECT id, price FROM pms_product WHERE deleted=0 AND status=1 "
                "ORDER BY sales DESC, id ASC LIMIT 1")
    m = re.search(r"(\d+)\s+([\d.]+)", out)
    if not m:
        raise SystemExit(f"找不到在售商品，先跑 reset_env.sh 准备场景数据（原始输出：{out!r}）")
    return int(m.group(1)), float(m.group(2))


def floor_price(port, pid):
    """homeFloors 里该商品的售价；找不到返回 None。

    返回 (价格, http_status, 业务码)；429 / 网络错误由调用方判为「本轮无效」。
    """
    status, body = http("GET", "/api/v1/home/floors", port=port)
    if status != 200 or not isinstance(body, dict):
        return None, status, None
    code = body.get("code")
    if code != 0:
        return None, status, code
    data = body.get("data") or {}
    for key in ("hotProducts", "newProducts"):
        for card in data.get(key) or []:
            if card.get("id") == pid:
                return float(card.get("price")), status, code
    return None, status, code


def detail_price(port, pid):
    """productDetail（无 L1）里该商品的售价。"""
    status, body = http("GET", f"/api/v1/products/{pid}", port=port)
    if status != 200 or not isinstance(body, dict):
        return None, status, None
    code = body.get("code")
    if code != 0:
        return None, status, code
    data = body.get("data") or {}
    price = data.get("price")
    return (float(price) if price is not None else None), status, code


def db_price(pid):
    out = mysql(f"SELECT price FROM pms_product WHERE id={pid}")
    m = re.search(r"[\d.]+", out)
    return float(m.group(0)) if m else None


_last_write = [0.0]


def bump_price(token, pid, value=1):
    """经 A 批量改价（相对增减），返回 (预期新价, t0=响应时刻, 耗时, 错误或 None)。

    t0 取「HTTP 响应返回」＝事务已提交，这是陈旧窗口的计时起点。
    """
    wait = WRITE_SPACING - (time.perf_counter() - _last_write[0])
    if wait > 0:
        time.sleep(wait)
    before = db_price(pid)
    t_start = time.perf_counter()
    status, body = http("POST", "/api/v1/merchant/product/batch-price",
                        {"ids": [pid], "mode": "amount", "value": value},
                        token=token, port=A)
    t0 = time.perf_counter()
    _last_write[0] = t0
    if status == 429 or (body or {}).get("code") == 429:
        return None, t0, t0 - t_start, "429（merchant-write 限流，放慢节拍）"
    if status != 200 or (body or {}).get("code") != 0:
        return None, t0, t0 - t_start, f"http={status} body={body!r}"
    # data 是受影响行数：为 0 说明商品不属于该商家（或被乐观锁拒绝），改价实际没发生
    if int((body or {}).get("data") or 0) < 1:
        return None, t0, t0 - t_start, f"改价未生效（affected={body!r}）"
    if before is None:
        return None, t0, t0 - t_start, "读不到原价"
    # batchUpdatePrice 的口径：next = price.add(value).max(0).setScale(2, HALF_UP)
    expected = round(before + value, 2)
    return expected, t0, t0 - t_start, None


def restore_price(pid, price):
    """复原价格并清掉缓存：SQL 直改不走业务写路径，不会触发 @CacheEvict，
    不清的话下一轮 warm 读会拿到上一轮留下的陈旧价。"""
    mysql(f"UPDATE pms_product SET price={price} WHERE id={pid}")
    for pattern in ("mall:cache:homeFloors*", "mall:cache:productDetail*"):
        for key in redis("--scan", "--pattern", pattern).splitlines():
            if key.strip():
                redis("DEL", key.strip())


# ------------------------------------------------------------------ 测量核心


def measure(reader, ttl, gap, warm_wait, timeout=None):
    """一轮测量：等 L1 过期 → warm 读 B 填 L1 → gap → 经 A 写 → 轮询 B。

    返回 dict：w（陈旧窗口，写响应→B 首次读到新价）、w_start（写发起→B 首次读到新价）、
    write_dur（改价请求耗时）、error（本轮作废原因）。
    """
    if warm_wait:
        time.sleep(warm_wait)
    old, status, code = reader(B, PID)
    if old is None:
        return {"error": f"warm 读失败（http={status} code={code}）"}
    if gap:
        time.sleep(gap)
    new, t0, write_dur, err = bump_price(TOKEN, PID)
    if err:
        return {"error": err, "write_dur": write_dur}
    t_start = _last_write[0] - write_dur
    deadline = t0 + (timeout if timeout is not None else ttl * 3 + 3)
    while time.perf_counter() < deadline:
        v, status, code = reader(B, PID)
        if v is None:
            if status == 429 or (status not in (200, 0)):
                return {"error": f"轮询读失败（http={status} code={code}）"}
            time.sleep(POLL)
            continue
        if abs(v - new) <= 0.001:
            t1 = time.perf_counter()
            return {"w": t1 - t0, "w_start": t1 - t_start, "write_dur": write_dur,
                    "old": old, "new": new}
        time.sleep(POLL)
    return {"error": f"超时未见新价（{deadline - t0:.1f}s）", "write_dur": write_dur,
            "old": old, "new": new}


def percentile(sorted_vals, p):
    if not sorted_vals:
        return float("nan")
    k = max(0, min(len(sorted_vals) - 1, int(round(p / 100 * (len(sorted_vals) - 1)))))
    return sorted_vals[k]


def summarize(name, ws):
    if not ws:
        return f"  {name}: 无有效样本"
    s = sorted(ws)
    return (f"  {name}: n={len(s)} min={s[0]:.2f}s P50={percentile(s, 50):.2f}s "
            f"P95={percentile(s, 95):.2f}s max={s[-1]:.2f}s")


# ------------------------------------------------------------------ 用例


def run_window_case(label, reader, has_l1, ttl, rounds, gap_fn, expect):
    """通用窗口用例：gap_fn(i) 给出 warm 读与写之间的间隔。"""
    print(f"\n=== {label}（{rounds} 轮，L1={'开' if has_l1 else '关'}，ttl={ttl}s）===")
    print(f"  {'轮次':<5}{'gap':>6}{'写耗时':>8}{'W':>8}{'W_start':>9}  结果")
    ws, w_starts, bad = [], [], []
    for i in range(rounds):
        gap = gap_fn(i)
        warm_wait = (ttl + 0.5) if has_l1 else 0.3
        r = measure(reader, ttl, gap, warm_wait)
        if "error" in r:
            print(f"  {i + 1:<5}{gap:>6.2f}{r.get('write_dur', 0):>8.3f}{'-':>8}{'-':>9}  ⚠ {r['error']}")
            bad.append(r["error"])
            continue
        w, w_start = r["w"], r["w_start"]
        ws.append(w)
        w_starts.append(w_start)
        ok = expect(w, gap)
        print(f"  {i + 1:<5}{gap:>6.2f}{r['write_dur']:>8.3f}{w:>8.3f}{w_start:>9.3f}  "
              f"{'✅' if ok else '❌ 超出预期窗口'}")
        if not ok:
            bad.append(f"第 {i + 1} 轮 W={w:.3f} gap={gap:.3f}")
    print(summarize("W（写响应 → B 见新值）", ws))
    print(summarize("W_start（写发起 → B 见新值）", w_starts))
    return ws, bad


def l1_1_worst_case(ttl, rounds):
    """L1-1：B 刚把 L1 填满就立刻改价 —— 这是陈旧窗口的最坏情形，应约等于 L1 TTL。"""
    before_local = hits(B, "homeFloors", "local")
    ws, bad = run_window_case(
        "L1-1 最坏情形窗口（homeFloors，有 L1）", floor_price, True, ttl, rounds,
        gap_fn=lambda i: 0.0,
        expect=lambda w, gap: (ttl - TOLERANCE) <= w <= (ttl + TOLERANCE),
    )
    after_local = hits(B, "homeFloors", "local")
    delta = after_local - before_local
    # 读指标差值断言：陈旧窗口期间 B 的「本地 L1 命中」必须递增，
    # 这才证明旧值确实由 L1 提供，而不是靠观察猜测。
    print(f"  B 侧 homeFloors 本地命中 {before_local:.0f} → {after_local:.0f}（+{delta:.0f}）"
          f"  {'✅ 旧值确由 L1 提供' if delta > 0 else '❌ 本地命中未递增'}")
    if delta <= 0:
        bad.append("L1 本地命中未递增")
    return ws, bad


def l1_2_no_l1_control(ttl, rounds):
    """L1-2：productDetail 不在白名单，没有 L1，改价应近乎立即生效。"""
    before_local = hits(B, "productDetail", "local")
    ws, bad = run_window_case(
        "L1-2 对照组（productDetail，无 L1）", detail_price, False, ttl, rounds,
        gap_fn=lambda i: 0.0,
        expect=lambda w, gap: w <= 0.3,
    )
    after_local = hits(B, "productDetail", "local")
    delta = after_local - before_local
    print(f"  B 侧 productDetail 本地命中 {before_local:.0f} → {after_local:.0f}（+{delta:.0f}）"
          f"  {'✅ 未走 L1（与白名单一致）' if delta == 0 else '❌ 不该有本地命中'}")
    if delta != 0:
        bad.append("productDetail 出现了本地 L1 命中")
    return ws, bad


def l1_3_random_gap(ttl, rounds):
    """L1-3：warm 读与写之间随机间隔 —— W 应恒等于「写时刻的剩余 L1 TTL」。"""
    random.seed(20261025)
    ws, bad = run_window_case(
        "L1-3 随机间隔分布（homeFloors，有 L1）", floor_price, True, ttl, rounds,
        gap_fn=lambda i: random.uniform(0, ttl),
        # W + gap ≈ ttl：L1 在 warm 读时填充，写发生在 gap 之后
        expect=lambda w, gap: (ttl - TOLERANCE) <= (w + gap) <= (ttl + TOLERANCE),
    )
    return ws, bad


def l1_4_pollution(ttl, rounds):
    """L1-4：写的同时高并发读，写后继续压 —— 三实例必须收敛，且 Redis 缓存值 == DB 值。

    这是唯一可能把陈旧窗口从 3s 放大到分钟级的路径：
    @CacheEvict 相对事务提交的先后未固定（CacheConfig 未用 TransactionAwareCacheManagerProxy），
    若 evict 早于 commit，并发读可能把**提交前的旧值**重新写进 Redis 并带上 5min/30min 新 TTL。
    """
    print(f"\n=== L1-4 写时高并发读 + 再缓存污染（{rounds} 轮，写后持续读 {ttl + 2:.0f}s）===")
    stop = threading.Event()
    active = threading.Event()
    active.set()
    stats = {p: {"ok": 0, "429": 0, "err": 0} for p in PORTS}

    def hammer(port):
        while not stop.is_set():
            if not active.is_set():
                time.sleep(0.05)      # 检查阶段暂停，避免收尾读撞上本地限流额度
                continue
            v, status, _ = floor_price(port, PID)
            if v is not None:
                stats[port]["ok"] += 1
            elif status == 429:
                stats[port]["429"] += 1
            else:
                stats[port]["err"] += 1
            time.sleep(0.02)

    threads = [threading.Thread(target=hammer, args=(p,)) for p in PORTS for _ in range(2)]
    for t in threads:
        t.start()
    bad = []
    try:
        for i in range(rounds):
            time.sleep(ttl + 0.5)
            new, t0, write_dur, err = bump_price(TOKEN, PID)
            if err:
                print(f"  第 {i + 1} 轮 ⚠ {err}")
                bad.append(err)
                continue
            time.sleep(ttl + 2)          # 给足 L1 过期 + 重建的时间
            active.clear()
            time.sleep(1.5)              # 等本地限流窗口（1s）排空，让收尾读一定拿得到结果
            seen = {p: floor_price(p, PID)[0] for p in PORTS}
            cached = redis_cached_price()
            db = db_price(PID)
            active.set()
            converged = all(v is not None and abs(v - new) <= 0.001 for v in seen.values())
            clean = cached is None or abs(cached - db) <= 0.001
            ok = converged and clean
            print(f"  第 {i + 1} 轮 新价={new} 三实例={[f'{p}:{seen[p]}' for p in PORTS]} "
                  f"Redis缓存={cached} DB={db}  {'✅' if ok else '❌'}")
            if not converged:
                bad.append(f"第 {i + 1} 轮未收敛：{seen}")
            if not clean:
                bad.append(f"第 {i + 1} 轮 Redis 缓存值 {cached} != DB {db}（疑似旧值被重新缓存）")
    finally:
        stop.set()
        for t in threads:
            t.join()
    print(f"  读压力：{ {p: stats[p] for p in PORTS} }")
    return bad


JAVA_TYPE = re.compile(r'^(java|com)\.[\w.$]+$')


def unwrap(value):
    """剥掉 Jackson DefaultTyping 的类型壳。

    缓存值用 GenericJacksonJsonRedisSerializer + DefaultTyping.NON_FINAL 写入，
    非 final 类型会被序列化成 `["java.util.ArrayList", [ ... ]]` / `["java.math.BigDecimal", "30.15"]`
    —— 首个元素是类型名字符串。不剥壳就迭代会拿到字符串而不是字典。
    只在首元素形如 Java 全限定类名时才剥，避免误伤真正的「两元素字符串列表」。
    """
    while (isinstance(value, list) and len(value) == 2
           and isinstance(value[0], str) and JAVA_TYPE.match(value[0])):
        value = value[1]
    return value


def redis_cached_price():
    """取 homeFloors 缓存在 Redis 里的售价（None = 无缓存条目）。"""
    for key in redis("--scan", "--pattern", "mall:cache:homeFloors*").splitlines():
        key = key.strip()
        if not key:
            continue
        raw = redis("GET", key)
        try:
            obj = json.loads(raw)
        except Exception:
            continue
        for card in unwrap((obj or {}).get("hotProducts")) or []:
            if not isinstance(card, dict) or card.get("id") != PID:
                continue
            price = unwrap(card.get("price"))
            if price is not None:
                return float(price)
    return None


# ------------------------------------------------------------------ 入口


def main():
    global TOKEN, PID
    parser = argparse.ArgumentParser(description="L1 本地缓存一致性实测")
    parser.add_argument("--ttl", type=float, default=3.0,
                        help="mall.cache.local.ttl 的实际值（秒），断言用（默认 3）")
    parser.add_argument("--rounds", type=int, default=0,
                        help="每用例轮数（默认 L1-1/2/4=10，L1-3=20）")
    parser.add_argument("--only", choices=["l1-1", "l1-2", "l1-3", "l1-4"],
                        help="只跑指定用例")
    parser.add_argument("--skip-restore", action="store_true", help="不复原商品价格")
    args = parser.parse_args()

    if not wait_healthy():
        raise SystemExit("3 个实例未就绪，先起 infra/docker-compose.multi.yml")
    TOKEN = merchant_token()
    PID, orig_price = pick_product()
    price, status, code = floor_price(A, PID)
    if price is None:
        raise SystemExit(f"商品 {PID} 不在 homeFloors 楼层里，无法观测（http={status} code={code}）")
    print(f"  观测商品 id={PID} 当前价={orig_price}（homeFloors 里={price}）  写实例 A={A} 读实例 B={B}")

    n_default = {"l1-1": 10, "l1-2": 10, "l1-3": 20, "l1-4": 10}
    cases = ["l1-1", "l1-2", "l1-3", "l1-4"]
    if args.only:
        cases = [args.only]
    rounds = args.rounds
    ttl = args.ttl

    failures = []
    try:
        for case in cases:
            n = rounds or n_default[case]
            if case == "l1-1":
                _, bad = l1_1_worst_case(ttl, n)
            elif case == "l1-2":
                _, bad = l1_2_no_l1_control(ttl, n)
            elif case == "l1-3":
                _, bad = l1_3_random_gap(ttl, n)
            else:
                bad = l1_4_pollution(ttl, n)
            failures += [f"{case}: {x}" for x in bad]
    finally:
        if not args.skip_restore:
            restore_price(PID, orig_price)
            print(f"\n  已复原商品 {PID} 价格 → {orig_price}（当前 DB={db_price(PID)}）")

    print("\n=== 汇总 ===")
    if failures:
        print(f"  ❌ {len(failures)} 项未达标：")
        for f in failures:
            print(f"    - {f}")
        sys.exit(1)
    print("  ✅ 全部断言通过")
    print("  判定口径：max(W_L1) ≤ ttl+容差 且 max(W_noL1)≈0 且无再缓存污染 → 白名单收窄足够，无需跨实例失效广播")


if __name__ == "__main__":
    main()
