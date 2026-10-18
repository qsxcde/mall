#!/usr/bin/env python3
"""
商品搜索三方案对比基准（LIKE / FULLTEXT / ES）。

为什么需要这个脚本
------------------
手工 curl 测出来的数字不可比：缓存冷热、JIT 预热、并发顺序都会影响结果。
这里把「关键词集、测量轮次、统计口径」全部固化，保证三种方案在同一把尺子下比较。

测量口径
--------
- **串行延迟**：每个关键词连续请求 N 次，取均值 / P50 / P99（丢弃前 2 次做 JIT 预热）
- **并发**：C 个并发同时发同一请求，记录全部完成耗时（观测是否会互相拖慢）
- **关键词分三类**（这是上一次实测发现的关键）：
  · high   高频词（命中数万条）—— 能靠 LIMIT 提前终止
  · low    低频词（命中 2 条）—— 必须扫完全表，最坏情况
  · none   无匹配 —— 同样必须全表扫
  只测高频词会严重低估真实成本。

用法
----
    python3 loadtest/scripts/bench_search.py --label LIKE
    python3 loadtest/scripts/bench_search.py --label FULLTEXT --rounds 5
    python3 loadtest/scripts/bench_search.py --label ES --concurrency 1 3 6
    python3 loadtest/scripts/bench_search.py --label ES --json out.json   # 存成 JSON 供汇总

依赖：仅标准库（urllib），无需 requests。
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "http://localhost:8080/api/v1/products/search"

# 三类关键词：必须覆盖，否则会得出错误结论
KEYWORDS = [
    ("high", "华为"),      # 命中 13 万+
    ("high", "Redmi"),     # 命中 3 万+
    ("low", "iPhone"),     # 命中 2 条 —— 最坏情况
    ("low", "ThinkPad"),   # 命中 2 万，但排序靠后
    ("none", "zzz不存在zzz"),  # 无匹配 —— 必全表扫
]


def fetch(keyword: str, page: int = 1, page_size: int = 8, timeout: float = 120.0) -> tuple[float, int, int]:
    """返回 (耗时秒, HTTP 状态, 命中总数)。命中数为 -1 表示解析失败。"""
    url = f"{BASE}?keyword={urllib.parse.quote(keyword)}&page={page}&pageSize={page_size}"
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            body = resp.read()
            status = resp.status
    except Exception as exc:  # noqa: BLE001
        return time.perf_counter() - start, 0, -1
    elapsed = time.perf_counter() - start
    total = -1
    try:
        data = json.loads(body)
        if data.get("code") == 0:
            total = data.get("data", {}).get("total", -1)
    except Exception:  # noqa: BLE001
        pass
    return elapsed, status, total


def percentile(values: list[float], p: float) -> float:
    if not values:
        return float("nan")
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, int(round((p / 100.0) * (len(ordered) - 1)))))
    return ordered[idx]


def measure_serial(keyword: str, rounds: int, warmup: int) -> dict:
    for _ in range(warmup):
        fetch(keyword)
    times, total, status = [], -1, 0
    for _ in range(rounds):
        elapsed, status, total = fetch(keyword)
        times.append(elapsed)
    return {
        "keyword": keyword,
        "hits": total,
        "status": status,
        "mean_ms": round(statistics.mean(times) * 1000, 1),
        "p50_ms": round(percentile(times, 50) * 1000, 1),
        "p99_ms": round(percentile(times, 99) * 1000, 1),
        "min_ms": round(min(times) * 1000, 1),
        "max_ms": round(max(times) * 1000, 1),
        "samples": len(times),
    }


def measure_concurrent(keyword: str, concurrency: int) -> dict:
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        start = time.perf_counter()
        futures = [pool.submit(fetch, keyword) for _ in range(concurrency)]
        results = [f.result() for f in futures]
        wall = time.perf_counter() - start
    durations = [r[0] for r in results]
    return {
        "keyword": keyword,
        "concurrency": concurrency,
        "wall_ms": round(wall * 1000, 1),
        "mean_ms": round(statistics.mean(durations) * 1000, 1),
        "max_ms": round(max(durations) * 1000, 1),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="商品搜索方案对比基准")
    parser.add_argument("--label", required=True, help="方案名，如 LIKE / FULLTEXT / ES")
    parser.add_argument("--rounds", type=int, default=5, help="每个关键词的串行测量次数")
    parser.add_argument("--warmup", type=int, default=2, help="预热次数（丢弃）")
    parser.add_argument("--concurrency", type=int, nargs="*", default=[1, 3, 6],
                        help="并发档位，默认 1 3 6；传空则不测并发")
    parser.add_argument("--json", help="把结果写入该 JSON 文件")
    parser.add_argument("--skip-serial", action="store_true", help="跳过串行测量")
    args = parser.parse_args()

    result = {"label": args.label, "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
              "serial": [], "concurrent": []}

    if not args.skip_serial:
        print(f"=== [{args.label}] 串行延迟（每词 {args.rounds} 次，预热 {args.warmup} 次）===")
        print(f"{'类型':<6}{'关键词':<16}{'命中':>10}{'均值ms':>10}{'P50':>10}{'P99':>10}")
        print("-" * 66)
        for kind, kw in KEYWORDS:
            row = measure_serial(kw, args.rounds, args.warmup)
            row["kind"] = kind
            result["serial"].append(row)
            hits = row["hits"] if row["hits"] >= 0 else "ERR"
            print(f"{kind:<6}{kw:<16}{str(hits):>10}{row['mean_ms']:>10}"
                  f"{row['p50_ms']:>10}{row['p99_ms']:>10}")

    if args.concurrency:
        print(f"\n=== [{args.label}] 并发（低频词 iPhone，最坏情况）===")
        print(f"{'并发':>6}{'总耗时ms':>12}{'单请求均值ms':>16}{'最慢ms':>12}")
        print("-" * 48)
        for c in args.concurrency:
            row = measure_concurrent("iPhone", c)
            result["concurrent"].append(row)
            print(f"{c:>6}{row['wall_ms']:>12}{row['mean_ms']:>16}{row['max_ms']:>12}")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(result, fh, ensure_ascii=False, indent=2)
        print(f"\n结果已写入 {args.json}")


if __name__ == "__main__":
    main()
