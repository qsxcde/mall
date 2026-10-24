#!/usr/bin/env python3
"""
Redis Stream 消费组多实例验证（V6a 分工 / V6d 孤儿回收）。

回答的是「多实例共用一个消费组时，消息到底怎么被分掉、消费者死了消息会不会丢」。
注意：**Redis Stream 没有 Kafka 式 rebalance** —— XGROUP + XREADGROUP 是拉取式分工，
不存在分区分配，也没有 coordinator 触发再均衡。因此本脚本验证的是两件真实存在的事：

  V6a 分工            3 个实例各自 XREADGROUP，每条消息恰好被一个消费者处理
                      （不重不漏 + 多个消费者确实分到了活）
  V6d 孤儿 pending 回收  消费者「读到但没 ACK」就消失（等价 kill -9），
                      其名下 pending 必须被存活消费者 XCLAIM 回来重投

消息用 XADD 直接注入而不是走 HTTP 抢购 —— 这里要隔离的是**队列语义**，
抢购受理/预扣那条链路由 SeckillBatchPersistIntegrationTest 覆盖。

用法（需先起 3 实例，见 infra/docker-compose.multi.yml）：
    # 必须打开削峰，并把回收窗口调到秒级，否则每个用例要等满默认的 1 分钟
    SECKILL_ASYNC_ENABLED=true SECKILL_BATCH_ENABLED=true \
    SECKILL_RECLAIM_MIN_IDLE=2s SECKILL_RECLAIM_INTERVAL=1s \
      docker compose -f infra/docker-compose.yml -f infra/docker-compose.multi.yml up -d

    python3 loadtest/scripts/verify_stream_group.py            # V6a + V6d
    python3 loadtest/scripts/verify_stream_group.py --no-v6a   # 只跑 V6d
    python3 loadtest/scripts/verify_stream_group.py --n 200    # 注入消息数
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENV_FILE = ROOT / "infra" / ".env"

# 3 个实例在宿主机上的端口（见 infra/docker-compose.multi.yml 的 port 映射）
PORTS = [8081, 8082, 8083]
APP_CONTAINERS = ["geek-mall-app1", "geek-mall-app2", "geek-mall-app3"]

STREAM = "mall:seckill:order:stream"
GROUP = "mall:seckill:order:group"
RESULT_PREFIX = "mall:seckill:result:"


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
    # 用 MYSQL_PWD 传口令而不是 -p：后者会往 stderr 打
    # 「Using a password on the command line interface」告警，
    # 而 sh() 为了读应用日志把 stderr 并进了 stdout，那行会混进结果集。
    # 注意 docker exec 不透传宿主机 env，必须用 -e 显式送进容器。
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


def wait_healthy(timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if all(http("GET", "/api/v1/cms/faqs", port=p)[0] == 200 for p in PORTS):
            return True
        time.sleep(1)
    return False


# ------------------------------------------------------------------ 断言


def count_orders(prefix):
    out = mysql(f"SELECT COUNT(*) FROM oms_order WHERE deleted=0 AND request_id LIKE '{prefix}%'")
    m = re.search(r"\d+", out)
    return int(m.group(0)) if m else 0


def duplicated_request_ids(prefix):
    out = mysql(
        "SELECT request_id FROM oms_order WHERE deleted=0 "
        f"AND request_id LIKE '{prefix}%' GROUP BY request_id HAVING COUNT(*)>1"
    )
    return [x for x in out.split() if x]


def pending_count():
    out = redis("XPENDING", STREAM, GROUP)
    # 形如：(integer) 0   或多行摘要，取首个整数
    m = re.search(r"(\d+)", out)
    return int(m.group(1)) if m else -1


def consumer_snapshot():
    """XINFO CONSUMERS 里登记过的 consumer 名列表。

    redis-cli 的输出是扁平的「字段名 / 值」逐行交替（name / <值> / pending / <值> / ...），
    没有引号也没有嵌套层级，只能按「遇到 name 取下一行」来取值。
    """
    lines = [ln.strip() for ln in redis("XINFO", "CONSUMERS", STREAM, GROUP).splitlines() if ln.strip()]
    return [lines[i + 1] for i, ln in enumerate(lines[:-1]) if ln == "name"]


def consumer_names_from_logs():
    """按 consumer 名统计「处理了多少条」，这是分工最直接的证据。

    多实例场景下日志在各容器里（{@code docker logs}），不是单个文件；
    --log 显式给了文件时才按文件读，便于单实例调试。
    """
    pat = re.compile(r"consumer=([A-Za-z0-9_.:@-]+)")
    counter = Counter()

    def feed(lines):
        for line in lines:
            if "落库成功" in line or "批量落库完成" in line:
                m = pat.search(line)
                if m:
                    # 批量行带「成功=N」，逐条行记 1
                    bm = re.search(r"成功=(\d+)", line)
                    counter[m.group(1)] += int(bm.group(1)) if bm else 1

    if LOG_FILE:
        try:
            with open(LOG_FILE, encoding="utf-8", errors="ignore") as f:
                feed(f)
        except FileNotFoundError:
            pass
        return counter

    for name in APP_CONTAINERS:
        feed(sh(["docker", "logs", "--tail", "2000", name]).splitlines())
    return counter


# ------------------------------------------------------------------ 清场


def reset(prefix, item_id):
    """清掉本轮数据：订单、结果、消息流、消费组、一人一单与活动库存 key。"""
    mysql(
        "DELETE FROM oms_order_status_log WHERE order_no IN "
        f"(SELECT order_no FROM oms_order WHERE request_id LIKE '{prefix}%'); "
        "DELETE FROM inv_bucket_log WHERE order_no IN "
        f"(SELECT order_no FROM oms_order WHERE request_id LIKE '{prefix}%'); "
        "DELETE FROM oms_order_item WHERE order_no IN "
        f"(SELECT order_no FROM oms_order WHERE request_id LIKE '{prefix}%'); "
        f"DELETE FROM oms_order WHERE request_id LIKE '{prefix}%';"
    )
    redis("DEL", STREAM)
    redis("DEL", f"mall:seckill:bought:{item_id}")
    redis("DEL", f"mall:seckill:stock:{item_id}")
    # 结果键是 mall:seckill:result:<requestId>，按前缀扫掉
    for key in redis("KEYS", f"{RESULT_PREFIX}*").split():
        redis("DEL", key)
    # 让消费端 ensureGroup 重新建组，避免沿用上一轮的 last-delivered
    time.sleep(1)


def prepare_item(item_id, stock=1000):
    """把测试商品调成「不分桶 + 足量活动库存」，让消息里不必带 bucketNo。"""
    mysql(
        f"UPDATE mkt_seckill_item SET stock={stock}, total={stock}, sold=0, "
        f"bucket_count=1, not_start=0, deleted=0 WHERE id={item_id};"
    )
    mysql(f"DELETE FROM mkt_seckill_bucket WHERE item_id={item_id};")


def pick_fixtures():
    """取一个可用的 (itemId, userId, addressId) 三元组。"""
    item = re.search(r"\d+", mysql("SELECT id FROM mkt_seckill_item WHERE deleted=0 ORDER BY id LIMIT 1"))
    if not item:
        raise SystemExit("找不到秒杀商品，先跑 reset_env.sh 准备场景数据")
    item = item.group(0)
    addr = mysql(
        "SELECT a.user_id, a.id FROM ums_user_address a "
        "WHERE a.deleted=0 ORDER BY a.id LIMIT 1"
    )
    m = re.search(r"(\d+)\s+(\d+)", addr)
    if not m:
        raise SystemExit(f"找不到收货地址，先跑 prepare_data.py 准备压测账号（原始输出：{addr!r}）")
    return int(item), int(m.group(1)), int(m.group(2))


def inject(prefix, n, item_id, user_id, address_id):
    """XADD 注入 n 条消息，返回 requestId 列表。"""
    ids = [f"{prefix}-{i:04d}" for i in range(n)]
    for rid in ids:
        redis(
            "XADD", STREAM, "*",
            "requestId", rid,
            "userId", str(user_id),
            "itemId", str(item_id),
            "addressId", str(address_id),
            "traceId", f"v6-{rid}",
        )
    return ids


def wait_drain(expected, timeout=60):
    """轮询直到订单数达到预期或超时，返回 (是否达到, 用时)。"""
    start = time.time()
    while time.time() - start < timeout:
        if count_orders_in_scope() >= expected:
            return True, time.time() - start
        time.sleep(0.5)
    return False, time.time() - start


# count_orders_in_scope 由各用例闭包注入当前 prefix
_current_prefix = ""


def count_orders_in_scope():
    return count_orders(_current_prefix)


def apps_pause():
    """冻结 JVM（不杀进程）：让本用例能独占消息流，制造确定性的 pending 现场。

    不冻结的话，注入的瞬间就会被真实消费者抢走，根本轮不到假消费者
    —— 那样测出来的就不是「孤儿回收」而是「正常消费」。
    """
    for name in APP_CONTAINERS:
        sh(["docker", "pause", name])


def apps_unpause():
    for name in APP_CONTAINERS:
        sh(["docker", "unpause", name])


# ------------------------------------------------------------------ V6a


def v6a(n):
    global _current_prefix
    print("=== V6a 消费组分工：3 实例同组消费，不重不漏 ===")
    item_id, user_id, address_id = pick_fixtures()
    prepare_item(item_id)
    prefix = "V6a"
    _current_prefix = prefix
    reset(prefix, item_id)

    inject(prefix, n, item_id, user_id, address_id)
    print(f"  注入 {n} 条消息（requestId={prefix}-*）")

    ok, elapsed = wait_drain(n, timeout=90)
    orders = count_orders(prefix)
    dups = duplicated_request_ids(prefix)
    pending = pending_count()
    names = consumer_names_from_logs()
    snap = consumer_snapshot()

    print(f"  建单 {orders}/{n}   耗时 {elapsed:.1f}s   pending={pending}")
    print(f"  XINFO CONSUMERS：{snap}")
    print(f"  按 consumer 统计的处理量：{dict(names) if names else '（日志里没取到）'}")

    checks = []
    checks.append(("订单数 == 注入消息数（不丢单）", orders == n, f"{orders} vs {n}"))
    checks.append(("每个 requestId 恰好一单（不重单）", not dups, f"重复={dups or '无'}"))
    checks.append(("pending 归零（无永久孤儿）", pending == 0, f"pending={pending}"))
    # 分工证据：至少 2 个不同的 consumer 处理过消息
    checks.append(("≥2 个 consumer 分到了活（分工成立）", len(names) >= 2,
                   f"consumer 数={len(names)}"))
    # 命名唯一：同名 consumer 一旦撞车，XCLAIM 会把正常消费中的消息抢走
    checks.append(("consumer 命名唯一（无撞名）", len(snap) == len(set(snap)),
                   f"{len(snap)} 个 / {len(set(snap))} 个不重复"))

    return report("V6a", checks)


# ------------------------------------------------------------------ V6d


def v6d(n):
    global _current_prefix
    print("\n=== V6d 孤儿 pending 回收：读到但没 ACK 就消失（等价 kill -9）===")
    item_id, user_id, address_id = pick_fixtures()
    prepare_item(item_id)
    prefix = "V6d"
    _current_prefix = prefix
    reset(prefix, item_id)

    # 必须先冻结消费者再注入：否则消息注入的瞬间就被真实消费者抢走，
    # 轮不到假消费者，测出来的就是「正常消费」而不是「孤儿回收」
    fake = "seckill-dead-faker"
    apps_pause()
    try:
        # reset() 删掉了流与消费组，正常由消费端 ensureGroup 自愈重建；
        # 但此刻实例被冻结、没人会去重建 —— 必须显式建组，否则 XREADGROUP 直接 NOGROUP
        redis("XGROUP", "CREATE", STREAM, GROUP, "0", "MKSTREAM")
        ids = inject(prefix, n, item_id, user_id, address_id)
        # 假消费者把消息全部读走但**不 ACK** —— 这与实例被 kill -9 后
        # 留下的现场完全一致：消息在 pending 里、归属一个永不确认的消费者
        redis("XREADGROUP", "GROUP", GROUP, fake, "COUNT", str(n), "STREAMS", STREAM, ">")
        before = pending_count()
    finally:
        apps_unpause()
    print(f"  注入 {n} 条并由假消费者 {fake} 读走（不 ACK）→ pending={before}")
    assert before > 0, "假消费者没有形成 pending，XREADGROUP 可能失败了"

    ok, elapsed = wait_drain(n, timeout=90)
    orders = count_orders(prefix)
    dups = duplicated_request_ids(prefix)
    pending = pending_count()

    print(f"  建单 {orders}/{n}   耗时 {elapsed:.1f}s   pending={pending}")

    checks = []
    checks.append(("孤儿消息被回收重投（不丢单）", orders == n, f"{orders} vs {n}"))
    checks.append(("重复投递不重复建单（不重单）", not dups, f"重复={dups or '无'}"))
    checks.append(("pending 归零（孤儿被清干净）", pending == 0, f"pending={pending}"))
    checks.append(("回收耗时受 reclaim-min-idle 控制", elapsed < 60, f"{elapsed:.1f}s"))

    return report("V6d", checks)


# ------------------------------------------------------------------ 汇总


def report(name, checks):
    print(f"\n--- {name} 结果 ---")
    failed = 0
    for label, passed, detail in checks:
        mark = "✅" if passed else "❌"
        print(f"  {mark} {label}：{detail}")
        failed += 0 if passed else 1
    print(f"  结果：{len(checks) - failed}/{len(checks)} 项通过")
    return failed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=100, help="每个用例注入的消息数")
    ap.add_argument("--log", default=None,
                    help="应用日志文件；不给则从 3 个 app 容器的 docker logs 里取（多实例默认）")
    ap.add_argument("--no-v6a", action="store_true")
    ap.add_argument("--no-v6d", action="store_true")
    args = ap.parse_args()

    global LOG_FILE
    LOG_FILE = args.log

    if not wait_healthy():
        raise SystemExit(
            "3 个实例未全部就绪。先执行：\n"
            "  SECKILL_ASYNC_ENABLED=true SECKILL_BATCH_ENABLED=true \\\n"
            "  SECKILL_RECLAIM_MIN_IDLE=2s SECKILL_RECLAIM_INTERVAL=1s \\\n"
            "    docker compose -f infra/docker-compose.yml -f infra/docker-compose.multi.yml up -d"
        )

    total = 0
    if not args.no_v6a:
        total += v6a(args.n)
    if not args.no_v6d:
        total += v6d(args.n)

    print(f"\n===== V6 消费组验证：{'全部通过' if total == 0 else f'{total} 项失败'} =====")
    sys.exit(1 if total else 0)


LOG_FILE = None

if __name__ == "__main__":
    main()
