#!/usr/bin/env python3
"""
压测指标统计脚本：解析 JMeter JTL，输出 QPS / TPS / P50 / P90 / P99。

指标口径：
  QPS  = 总请求数 / 压测持续时长（秒）
  TPS  = 成功请求数 / 压测持续时长（秒）   —— 秒杀场景中即「成功建单」吞吐
  Pxx  = 全部请求响应时间的分位数（同时给出成功 / 失败分组）

用法： python3 report_metrics.py results/seckill-stress.jtl [--markdown out.md]
"""
import argparse
import csv
import sys
from datetime import datetime

PERCENTILES = [50, 90, 99]


def percentile(sorted_values, p):
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return float(sorted_values[0])
    k = (len(sorted_values) - 1) * (p / 100.0)
    lo = int(k)
    hi = min(lo + 1, len(sorted_values) - 1)
    frac = k - lo
    return sorted_values[lo] * (1 - frac) + sorted_values[hi] * frac


def load(jtl_path):
    rows = []
    with open(jtl_path, newline="", encoding="utf-8", errors="ignore") as f:
        reader = csv.DictReader(f)
        for r in reader:
            try:
                rows.append({
                    "ts": int(r["timeStamp"]),
                    "elapsed": int(r["elapsed"]),
                    "success": str(r.get("success", "")).lower() == "true",
                    "code": r.get("responseCode", ""),
                    "label": r.get("label", ""),
                })
            except (KeyError, ValueError, TypeError):
                continue
    return rows


def summarize(rows, name):
    if not rows:
        return None
    start = min(r["ts"] for r in rows)
    end = max(r["ts"] + r["elapsed"] for r in rows)
    duration_ms = max(end - start, 1)
    duration_s = duration_ms / 1000.0

    ok = [r for r in rows if r["success"]]
    fail = [r for r in rows if not r["success"]]
    all_lat = sorted(r["elapsed"] for r in rows)
    ok_lat = sorted(r["elapsed"] for r in ok)

    # 秒级吞吐：按起始秒分桶求平均，反映稳态 QPS
    buckets = {}
    for r in rows:
        buckets[r["ts"] // 1000] = buckets.get(r["ts"] // 1000, 0) + 1
    active_seconds = len(buckets)
    steady_qps = len(rows) / active_seconds if active_seconds else 0.0
    peak_qps = max(buckets.values()) if buckets else 0

    code_dist = {}
    for r in rows:
        code_dist[r["code"]] = code_dist.get(r["code"], 0) + 1

    return {
        "name": name,
        "samples": len(rows),
        "success": len(ok),
        "failure": len(fail),
        "duration_s": duration_s,
        "qps": len(rows) / duration_s,
        "steady_qps": steady_qps,
        "peak_qps": peak_qps,
        "tps": len(ok) / duration_s,
        "success_rate": len(ok) / len(rows) * 100,
        "all": {p: percentile(all_lat, p) for p in PERCENTILES},
        "ok": {p: percentile(ok_lat, p) for p in PERCENTILES},
        "avg_all": sum(all_lat) / len(all_lat),
        "avg_ok": (sum(ok_lat) / len(ok_lat)) if ok_lat else 0.0,
        "max": max(all_lat),
        "min": min(all_lat),
        "code_dist": dict(sorted(code_dist.items(), key=lambda x: -x[1])),
    }


def fmt(ms):
    return f"{ms:.1f} ms"


def render(s, lines):
    lines.append(f"### {s['name']}")
    lines.append("")
    lines.append(f"- 样本总数：**{s['samples']}**（成功 {s['success']} / 失败 {s['failure']}，成功率 {s['success_rate']:.2f}%）")
    lines.append(f"- 压测持续：**{s['duration_s']:.2f} s**")
    lines.append(f"- **QPS（总吞吐）**：{s['qps']:.1f} req/s（稳态 {s['steady_qps']:.1f}，峰值 {s['peak_qps']}）")
    lines.append(f"- **TPS（成功吞吐）**：{s['tps']:.1f} req/s")
    lines.append("")
    lines.append("| 分位 | 全部请求 | 成功请求 |")
    lines.append("|---|---|---|")
    for p in PERCENTILES:
        v = s["all"][p]
        ov = s["ok"][p] if s["ok"][p] else float("nan")
        lines.append(f"| P{p} | {fmt(v)} | {'' if s['ok'][p] == 0 else fmt(ov)} |")
    lines.append(f"| 平均 | {fmt(s['avg_all'])} | {fmt(s['avg_ok'])} |")
    lines.append(f"| 最大 | {fmt(s['max'])} | - |")
    lines.append(f"| 最小 | {fmt(s['min'])} | - |")
    lines.append("")
    lines.append(f"- HTTP/业务码分布：`{s['code_dist']}`")
    lines.append("")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jtl")
    ap.add_argument("--label", default=None)
    ap.add_argument("--markdown", default=None)
    args = ap.parse_args()

    rows = load(args.jtl)
    if not rows:
        print(f"未从 {args.jtl} 读到样本", file=sys.stderr)
        sys.exit(2)

    label = args.label or args.jtl.split("/")[-1].replace(".jtl", "")
    s = summarize(rows, label)

    lines = []
    lines.append(f"## 压测指标（{datetime.now():%Y-%m-%d %H:%M:%S}）")
    lines.append("")
    render(s, lines)
    text = "\n".join(lines)
    print(text)

    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"\n已写入 {args.markdown}", file=sys.stderr)


if __name__ == "__main__":
    main()
