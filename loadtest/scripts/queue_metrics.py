#!/usr/bin/env python3
"""
秒杀削峰队列指标采集（对应方案文档 P0-4：积压 / 排空曲线 / 死信）。

为什么需要它
------------
异步削峰的验收线不在「响应快」，而在**积压是否有界**：
  * 队列长度应收敛到「预扣成功数」，不该被无效请求灌爆；
  * 持续过载时排空时长必须有界（持续不降 = 消费能力不足）；
  * 死信 / 重投必须为 0，> 0 就要归因（`max-deliveries=3` 超限会被丢成 FAILED 终态）。

采集什么
--------
  XLEN                                         队列长度（近似裁剪后的真实长度）
  XINFO GROUPS → pending / lag / consumers     未确认数 / 落后量 / 存活消费者数
  XPENDING 摘要                                 未被确认的消息总数（与 pending 互校）
  mall:seckill:result:* 按 status 分类计数      SUCCESS / QUEUED（未落库）/ FAILED（≈死信）
  INFO memory → used_memory                     Redis 内存（验证 max-stream-length 裁剪生效）

用法
----
  # 单次快照（压测前后各跑一次做对比）
  python3 queue_metrics.py

  # 排空曲线：持续过载场景边压边采（P0-4）
  python3 queue_metrics.py --watch --interval 2 --duration 300 --csv ../results/queue-drain.csv

  # 中文化 label + 直接出 markdown
  python3 queue_metrics.py --label "MQ 削峰 200VU×100" --markdown ../results/queue.md

注意
----
* 走 `docker exec`，只能在**跑着 Redis 容器的那台机器**上执行（容器名可用
  `REDIS_CONTAINER` 环境变量覆盖）。
* `FAILED` 结果即当前实现下的「死信」（消费者超 max-deliveries 会回补预扣 +
  写 FAILED 终态 + 丢弃），因此「死信数 = FAILED 数」。
* 结果 key 有 `result-ttl`（默认 30m）会自然过期，长时间压测后统计会偏低，
  这也是为什么要在**排空后立即**采集。
"""
import argparse
import csv
import os
import re
import subprocess
import sys
import time
from datetime import datetime

REDIS_CONTAINER = os.environ.get("REDIS_CONTAINER", "geek-mall-redis")
STREAM = "mall:seckill:order:stream"
GROUP = "mall:seckill:order:group"
RESULT_PATTERN = "mall:seckill:result:*"
STATUS_RE = re.compile(r'"status"\s*:\s*"(SUCCESS|QUEUED|FAILED)"')
MGET_CHUNK = 500


def redis(*args) -> list:
    """执行 redis-cli --raw，返回非空行列表。

    key 不存在（XINFO / XPENDING 会直接报错）属于「还没有积压」的正常状态，返回空；
    其它非 0 退出码一律是环境问题（Docker 未启动 / 容器名不对），直接抛出原始报错，
    避免把「连不上 Redis」误判成「队列是空的」。
    """
    cmd = ["docker", "exec", REDIS_CONTAINER, "redis-cli", "--raw", *args]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        stderr = proc.stderr.decode("utf-8", "ignore").strip()
        if "no such key" in stderr.lower():
            return []
        raise SystemExit(f"访问 Redis 失败（容器 {REDIS_CONTAINER}）：{stderr[:300]}\n"
                         f"请确认 Docker 已启动且容器名正确（可用 REDIS_CONTAINER 覆盖）")
    return [line for line in proc.stdout.decode("utf-8", "ignore").splitlines() if line.strip()]


def scalar_int(*args, default=0) -> int:
    out = redis(*args)
    if not out:
        return default
    try:
        return int(out[0])
    except ValueError:
        return default


def group_info() -> dict:
    """解析 XINFO GROUPS：字段名与值在 --raw 下逐行交替出现。"""
    lines = redis("XINFO", "GROUPS", STREAM)
    if not lines:
        return {}
    info = {}
    for idx in range(0, len(lines) - 1):
        key = lines[idx].strip()
        if key in ("name", "consumers", "pending", "lag", "entries-read", "last-delivered-id"):
            info.setdefault(key, lines[idx + 1].strip())
    return info


def result_counts() -> tuple:
    """扫描结果 key，按 status 分类计数（异步链路的终态分布）。"""
    keys = redis("--scan", "--pattern", RESULT_PATTERN)
    if not keys:
        return 0, 0, 0
    success = queued = failed = 0
    for start in range(0, len(keys), MGET_CHUNK):
        values = redis("MGET", *keys[start:start + MGET_CHUNK])
        for value in values:
            if not value:
                continue
            match = STATUS_RE.search(value)
            if not match:
                continue
            status = match.group(1)
            if status == "SUCCESS":
                success += 1
            elif status == "FAILED":
                failed += 1
            else:
                queued += 1
    return success, queued, failed


def redis_memory_mb() -> float:
    out = redis("INFO", "memory")
    for line in out:
        if line.startswith("used_memory:"):
            try:
                return int(line.split(":", 1)[1]) / 1024.0 / 1024.0
            except ValueError:
                return 0.0
    return 0.0


def snapshot() -> dict:
    info = group_info()
    success, queued, failed = result_counts()
    pending = int(info.get("pending") or 0)
    return {
        "ts": datetime.now().strftime("%H:%M:%S"),
        "xlen": scalar_int("XLEN", STREAM),
        "pending": pending,
        "pending_summary": scalar_int("XPENDING", STREAM, GROUP),
        "lag": int(info.get("lag") or 0),
        "consumers": int(info.get("consumers") or 0),
        "success": success,
        "queued": queued,
        "failed": failed,
        "mem_mb": round(redis_memory_mb(), 1),
    }


def snapshot_lines(snap: dict) -> list:
    return [
        f"- 采集时间：{snap['ts']}",
        f"- 队列长度 `XLEN`：**{snap['xlen']}**",
        f"- 未确认 `pending`：**{snap['pending']}**（XPENDING 汇总 {snap['pending_summary']}，"
        f"`lag` {snap['lag']}，存活消费者 {snap['consumers']}）",
        f"- 结果分布：SUCCESS **{snap['success']}** / QUEUED（未落库）**{snap['queued']}** / "
        f"FAILED（≈死信）**{snap['failed']}**",
        f"- Redis 内存：{snap['mem_mb']} MB",
    ]


def verdict(snaps: list) -> list:
    """按方案文档第五节的验收线给判读，避免「只看数字不看含义」。"""
    lines = []
    last = snaps[-1]
    peak_xlen = max(s["xlen"] for s in snaps)
    peak_pending = max(s["pending"] for s in snaps)

    lines.append(f"- 积压峰值：`XLEN` {peak_xlen} / `pending` {peak_pending}")
    if last["failed"] == 0:
        lines.append("- 死信 / 重投：**0** ✅（稳态应如此）")
    else:
        lines.append(f"- 死信 / 重投：**{last['failed']}** ❌（> 0 必须归因：DB 故障？毒消息？）")
    if last["pending"] == 0 and last["xlen"] == 0:
        lines.append("- 排空：**已排空** ✅（队列与 pending 均归零）")
    else:
        lines.append(f"- 排空：⚠️ 未排空（XLEN {last['xlen']} / pending {last['pending']}），"
                     f"若持续不降说明消费能力不足")
    if last["consumers"] == 0:
        lines.append("- 消费者：⚠️ 0 个（异步模式未开启，或消费者已退出）")
    else:
        lines.append(f"- 消费者：{last['consumers']} 个")
    return lines


def main():
    ap = argparse.ArgumentParser(description="秒杀削峰队列指标采集（积压/排空/死信）")
    ap.add_argument("--watch", action="store_true", help="持续采集（排空曲线）")
    ap.add_argument("--interval", type=float, default=2.0, help="采集间隔秒，默认 2")
    ap.add_argument("--duration", type=float, default=300.0, help="持续秒数，默认 300")
    ap.add_argument("--label", default="秒杀削峰队列指标", help="报告标题")
    ap.add_argument("--csv", default=None, help="写入时序 CSV（排空曲线）")
    ap.add_argument("--markdown", default=None, help="写入 markdown 报告")
    args = ap.parse_args()

    snaps = []

    if not args.watch:
        snap = snapshot()
        snaps.append(snap)
        lines = snapshot_lines(snap)
        lines.append("")
        lines.extend(verdict(snaps))
        print(f"### {args.label}")
        print("\n".join(lines))
    else:
        total = max(1, int(args.duration / args.interval))
        print(f"开始采集：间隔 {args.interval}s，共 {total} 次（Ctrl+C 可提前结束）", file=sys.stderr)
        header = ["ts", "elapsed_s", "xlen", "pending", "lag", "consumers",
                  "success", "queued", "failed", "mem_mb"]
        rows = []
        started = time.time()
        try:
            for i in range(total):
                snap = snapshot()
                snap["elapsed"] = round(time.time() - started, 1)
                snaps.append(snap)
                rows.append([snap["ts"], snap["elapsed"], snap["xlen"], snap["pending"], snap["lag"],
                             snap["consumers"], snap["success"], snap["queued"], snap["failed"],
                             snap["mem_mb"]])
                print(f"  [{snap['elapsed']:>7.1f}s] xlen={snap['xlen']:<6} pending={snap['pending']:<6} "
                      f"lag={snap['lag']:<6} ok={snap['success']:<6} queued={snap['queued']:<6} "
                      f"failed={snap['failed']}", file=sys.stderr)
                if i < total - 1:
                    time.sleep(args.interval)
        except KeyboardInterrupt:
            print("已手动结束采集", file=sys.stderr)

        if args.csv:
            with open(args.csv, "w", encoding="utf-8", newline="") as f:
                writer = csv.writer(f)
                writer.writerow(header)
                writer.writerows(rows)
            print(f"时序数据已写入 {args.csv}", file=sys.stderr)

        lines = []
        lines.append("")
        lines.append("| 时刻 | 耗时(s) | XLEN | pending | lag | 消费者 | SUCCESS | QUEUED | FAILED | 内存(MB) |")
        lines.append("|---|---|---|---|---|---|---|---|---|---|")
        for snap in snaps:
            lines.append(f"| {snap['ts']} | {snap['elapsed']} | {snap['xlen']} | {snap['pending']} | "
                         f"{snap['lag']} | {snap['consumers']} | {snap['success']} | "
                         f"{snap['queued']} | {snap['failed']} | {snap['mem_mb']} |")
        lines.append("")
        lines.extend(verdict(snaps))
        print(f"### {args.label}")
        print("\n".join(lines))

    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as f:
            f.write(f"### {args.label}\n\n" + "\n".join(lines) + "\n")
        print(f"\n已写入 {args.markdown}", file=sys.stderr)


if __name__ == "__main__":
    main()
