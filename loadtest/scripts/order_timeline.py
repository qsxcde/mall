#!/usr/bin/env python3
"""
同轮并发时间线分析：秒杀洪峰期间，普通下单链路是否被拖垮。

数据来源：k6 `--out csv=<file>` 导出的原始样本（每条样本带 metric_name / timestamp / scenario）。

输出：
  1) 逐秒时间线（普通下单成功数 / P50 / P90 / P99 + 秒杀请求数）
  2) 洪峰前 / 洪峰中 / 洪峰后 三个窗口的对比

用法：
  python3 order_timeline.py results/mixed.csv [--markdown out.md]
"""
import argparse
import csv
import sys
from collections import defaultdict
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
    return sorted_values[lo] * (1 - (k - lo)) + sorted_values[hi] * (k - lo)


def load(path):
    """返回 {'submit': {sec: [ms]}, 'add': {...}, 'seckill': {...}, 'ok': {sec: n}, 'err': {sec: n}}"""
    data = {
        "submit": defaultdict(list),
        "submit_ttfb": defaultdict(list),
        "add": defaultdict(list),
        "seckill": defaultdict(list),
        "ok": defaultdict(int),
        "fail": defaultdict(int),
        "err": defaultdict(int),
        "seckill_ok": defaultdict(int),
    }
    t0 = None
    with open(path, newline="", encoding="utf-8", errors="ignore") as f:
        for row in csv.DictReader(f):
            name = row.get("metric_name", "")
            try:
                ts = int(row["timestamp"])
                val = float(row["metric_value"])
            except (KeyError, ValueError, TypeError):
                continue
            t0 = ts if t0 is None or ts < t0 else t0
            data.setdefault("_raw", []).append((ts, name, val))

    for ts, name, val in data.pop("_raw"):
        sec = ts - t0
        if name == "submit_latency":
            data["submit"][sec].append(val)
        elif name == "submit_ttfb":
            data["submit_ttfb"][sec].append(val)
        elif name == "add_cart_latency":
            data["add"][sec].append(val)
        elif name == "seckill_latency":
            data["seckill"][sec].append(val)
        elif name == "order_success":
            data["ok"][sec] += 1
        elif name == "order_fail":
            data["fail"][sec] += 1
        elif name == "seckill_success":
            data["seckill_ok"][sec] += 1
    return data


def window_stats(data, lo, hi):
    """统计 [lo, hi) 秒窗口内普通下单链路的指标。"""
    lat = []
    ttfb = []
    ok = 0
    fail = 0
    for sec, values in data["submit"].items():
        if lo <= sec < hi:
            lat.extend(values)
    for sec, n in data["ok"].items():
        if lo <= sec < hi:
            ok += n
    for sec, n in data["fail"].items():
        if lo <= sec < hi:
            fail += n
    for sec, values in data["submit_ttfb"].items():
        if lo <= sec < hi:
            ttfb.extend(values)
    lat.sort()
    ttfb.sort()
    span = max(hi - lo, 1)
    return {
        "qps": ok / span,
        "ok": ok,
        "fail": fail,
        "n": len(lat),
        "p": {p: percentile(lat, p) for p in PERCENTILES},
        "avg": (sum(lat) / len(lat)) if lat else 0.0,
        "max": lat[-1] if lat else 0.0,
        "ttfb": {p: percentile(ttfb, p) for p in PERCENTILES},
        "ttfb_avg": (sum(ttfb) / len(ttfb)) if ttfb else 0.0,
    }


def fmt(ms):
    return f"{ms:.1f} ms"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv_path")
    ap.add_argument("--label", default="秒杀洪峰 × 普通下单 同轮并发")
    ap.add_argument("--markdown", default=None)
    args = ap.parse_args()

    data = load(args.csv_path)
    if not data["submit"] and not data["seckill"]:
        print(f"未从 {args.csv_path} 解析到样本", file=sys.stderr)
        sys.exit(2)

    # 洪峰窗口：出现秒杀请求的秒区间
    seckill_secs = sorted(set(data["seckill"]) | set(data["seckill_ok"]))
    if not seckill_secs:
        print("未发现秒杀样本，无法定位洪峰窗口", file=sys.stderr)
        sys.exit(2)
    burst_lo, burst_hi = seckill_secs[0], seckill_secs[-1] + 1

    total_secs = max(max(data["submit"], default=0), burst_hi) + 1

    lines = []
    lines.append(f"## {args.label}（{datetime.now():%Y-%m-%d %H:%M:%S}）")
    lines.append("")
    lines.append(f"- 观测总时长：**{total_secs} s**")
    lines.append(f"- 秒杀洪峰窗口：**第 {burst_lo} ~ {burst_hi - 1} 秒**"
                 f"（共 {len(seckill_secs)} 秒内收到秒杀请求）")
    lines.append(f"- 洪峰期秒杀请求数：**{sum(len(v) for v in data['seckill'].values())}"
                 f"**，其中成功建单 **{sum(data['seckill_ok'].values())}**")
    lines.append("")

    # ---- 三窗口对比 ----
    before = window_stats(data, 0, burst_lo)
    during = window_stats(data, burst_lo, burst_hi)
    after = window_stats(data, burst_hi, total_secs)

    lines.append("### 洪峰前 / 洪峰中 / 洪峰后 对比（普通下单链路）")
    lines.append("")
    lines.append("| 窗口 | 普通下单 TPS | 成功 | 失败 | P50 | P90 | P99 | 平均 | 最大 | 服务端 TTFB P50 | 服务端 TTFB P99 |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|")
    for label, w in (("洪峰**前**", before), ("**洪峰中**", during), ("洪峰**后**", after)):
        lines.append(
            f"| {label} | {w['qps']:.1f} | {w['ok']} | {w['fail']} | "
            f"{fmt(w['p'][50])} | {fmt(w['p'][90])} | {fmt(w['p'][99])} | "
            f"{fmt(w['avg'])} | {fmt(w['max'])} | "
            f"{fmt(w['ttfb'][50])} | {fmt(w['ttfb'][99])} |"
        )
    lines.append("")

    # ---- 影响判定 ----
    if before["p"][99] and during["p"][99]:
        ratio = during["p"][99] / before["p"][99]
        t_ratio = (during["ttfb"][99] / before["ttfb"][99]) if before["ttfb"][99] else 0
        verdict = ("普通下单未被明显拖垮。" if ratio < 2
                   else "普通下单延迟被明显抬高（但无失败），需要评估隔离方案。")
        lines.append(f"> **判定**：洪峰中普通下单 P99 为洪峰前的 **{ratio:.2f} 倍**"
                     f"（{fmt(before['p'][99])} → {fmt(during['p'][99])}），"
                     f"服务端 TTFB P99 {fmt(before['ttfb'][99])} → {fmt(during['ttfb'][99])}"
                     f"（{t_ratio:.2f} 倍），"
                     f"TPS {before['qps']:.1f} → {during['qps']:.1f}，失败数 {during['fail']}。"
                     f"{verdict}")
        lines.append("")

    # ---- 逐秒时间线 ----
    lines.append("### 逐秒时间线")
    lines.append("")
    lines.append("| 秒 | 普通下单成功 | 普通下单 P50 | P90 | P99 | 秒杀请求 | 秒杀建单 |")
    lines.append("|---|---|---|---|---|---|---|")
    for sec in range(total_secs):
        lat = sorted(data["submit"].get(sec, []))
        sk = len(data["seckill"].get(sec, [])) + data["seckill_ok"].get(sec, 0)
        sk_ok = data["seckill_ok"].get(sec, 0)
        ok = data["ok"].get(sec, 0)
        marker = " ⚡" if burst_lo <= sec < burst_hi else ""
        if not lat and not sk and not ok:
            continue
        p50 = fmt(percentile(lat, 50)) if lat else "-"
        p90 = fmt(percentile(lat, 90)) if lat else "-"
        p99 = fmt(percentile(lat, 99)) if lat else "-"
        lines.append(f"| {sec}{marker} | {ok} | {p50} | {p90} | {p99} | {sk} | {sk_ok} |")
    lines.append("")
    lines.append("> ⚡ 标记为秒杀洪峰窗口。")

    text = "\n".join(lines)
    print(text)
    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"\n已写入 {args.markdown}", file=sys.stderr)


if __name__ == "__main__":
    main()
