#!/usr/bin/env python3
"""
k6 结果渲染脚本：读取 k6 `--summary-export` 导出的 JSON，输出与 JMeter 报告同口径的
QPS / TPS / P50 / P90 / P99。

指标口径与 loadtest/scripts/report_metrics.py 保持一致：
  QPS = http_reqs.count / 压测持续时长
  TPS = order_success.count / 压测持续时长（秒杀场景即「成功建单」吞吐）

用法：
  python3 k6_summary.py results/k6-seckill.summary.json --label "..." [--markdown out.md]
"""
import argparse
import json
import sys
from datetime import datetime

PERCENTILES = [50, 90, 99]


def pick(values: dict, *keys, default=0.0):
    """兼顾 k6 新旧 summary 格式：优先精确 key，再退化到常见别名。"""
    for k in keys:
        if k in values:
            return values[k]
    return default


def trend_pct(values: dict, p: int):
    # k6 的 trend values 里 50 分位叫 med，其余是 "p(90)" 这种形式
    if p == 50:
        return pick(values, "p(50)", "med", "avg")
    return pick(values, f"p({p})", default=0.0)


def get_metric(metrics: dict, name: str):
    """k6 的 --summary-export 把指标名直接映射到 values 字典（无 type/values 包裹）；
    这里兼容两种形态，避免 k6 版本差异导致解析失败。"""
    m = metrics.get(name)
    if m is None:
        return None
    if isinstance(m, dict) and "values" in m and isinstance(m["values"], dict):
        return m["values"]
    return m


def duration_seconds(data: dict) -> float:
    state = data.get("state") or {}
    ms = state.get("testRunDurationMs")
    if ms:
        return max(ms / 1000.0, 0.001)
    # k6 v2 的 summary 不带 state：用 http_reqs 的 count/rate 反推压测时长
    metrics = data.get("metrics", {})
    reqs = get_metric(metrics, "http_reqs") or {}
    rate = reqs.get("rate") or 0
    count = reqs.get("count") or 0
    if rate:
        return max(count / rate, 0.001)
    return 0.001


def render(jtl_data, label):
    metrics = jtl_data.get("metrics", {})
    dur = duration_seconds(jtl_data)

    reqs = get_metric(metrics, "http_reqs") or {}
    total = int(pick(reqs, "count", default=0))
    qps = pick(reqs, "rate", default=(total / dur if total else 0.0))

    failed = get_metric(metrics, "http_req_failed") or {}
    fail_rate = pick(failed, "rate", "value", default=0.0)

    overall = get_metric(metrics, "http_req_duration") or {}
    # TTFB 口径要和「总耗时」严格配对，否则会拿「全部请求」去减「仅下单请求」，算出负值：
    #   下单脚本 → submit_ttfb(仅下单) ↔ submit_latency(仅下单)
    #   其它脚本 → http_req_waiting(全部) ↔ http_req_duration(全部)
    waiting = get_metric(metrics, "submit_ttfb") or {}
    if waiting:
        matched_total = get_metric(metrics, "submit_latency") or overall
    else:
        waiting = get_metric(metrics, "http_req_waiting") or {}
        matched_total = overall
    ok_trend = (get_metric(metrics, "order_latency")
                or get_metric(metrics, "submit_latency") or {})

    ok = get_metric(metrics, "order_success") or {}
    ok_count = int(pick(ok, "count", default=0))
    tps = ok_count / dur

    biz_ok = get_metric(metrics, "business_ok") or {}
    rejected = get_metric(metrics, "order_rejected") or {}
    limited = get_metric(metrics, "rate_limited") or {}
    srv_err = get_metric(metrics, "server_error") or {}

    # 成功请求的分位：秒杀用 order_latency（只含建单成功），其余场景退回整体
    success_trend = ok_trend if ok_trend else (overall if ok_count else {})

    lines = []
    lines.append(f"### {label}")
    lines.append("")
    lines.append(f"- 样本总数：**{total}**"
                 f"（成功建单 {ok_count} / 业务拒绝 {int(pick(rejected, 'count', default=0))} "
                 f"/ 限流 429 {int(pick(limited, 'count', default=0))} "
                 f"/ 系统错误 {int(pick(srv_err, 'count', default=0))}，"
                 f"HTTP 失败率 {fail_rate * 100:.2f}%）")
    lines.append(f"- 压测持续：**{dur:.2f} s**")
    lines.append(f"- **QPS（总吞吐）**：{qps:.1f} req/s")
    lines.append(f"- **TPS（成功吞吐）**：{tps:.1f} req/s")
    if biz_ok:
        lines.append(f"- 业务成功数（含幂等命中）：{int(pick(biz_ok, 'count', default=0))}")
    lines.append("")
    lines.append("| 分位 | 全部请求 | 成功请求 |")
    lines.append("|---|---|---|")
    for p in PERCENTILES:
        all_v = trend_pct(overall, p)
        ok_v = trend_pct(success_trend, p) if success_trend else None
        lines.append(f"| P{p} | {fmt(all_v)} | {fmt(ok_v) if ok_v else ''} |")
    lines.append(f"| 平均 | {fmt(pick(overall, 'avg'))} | "
                 f"{fmt(pick(success_trend, 'avg')) if success_trend else ''} |")
    lines.append(f"| 最大 | {fmt(pick(overall, 'max'))} | - |")
    lines.append(f"| 最小 | {fmt(pick(overall, 'min'))} | - |")
    lines.append("")
    if waiting:
        # http_req_waiting = 服务端首字节时间(TTFB)，不含客户端建连/排队开销，
        # 是判断「延迟到底是服务端慢还是压测机慢」的关键指标。
        lines.append("| 服务端 TTFB | 数值 |")
        lines.append("|---|---|")
        for p in PERCENTILES:
            lines.append(f"| P{p} | {fmt(trend_pct(waiting, p))} |")
        lines.append(f"| 平均 | {fmt(pick(waiting, 'avg'))} |")
        lines.append("")
        gap = pick(matched_total, "avg", default=0) - pick(waiting, "avg", default=0)
        lines.append(f"> 客户端开销（总耗时 − TTFB，均值）= **{gap:.1f} ms**"
                     f"{'（可忽略，说明测得的延迟就是服务端延迟）' if abs(gap) < 5 else ''}")
        lines.append("")
    return "\n".join(lines)


def fmt(ms):
    if ms is None:
        return ""
    try:
        return f"{float(ms):.1f} ms"
    except (TypeError, ValueError):
        return ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("summary_json")
    ap.add_argument("--label", default=None)
    ap.add_argument("--markdown", default=None)
    args = ap.parse_args()

    with open(args.summary_json, encoding="utf-8") as f:
        data = json.load(f)

    label = args.label or args.summary_json.split("/")[-1].replace(".summary.json", "")
    lines = [f"## k6 压测指标（{datetime.now():%Y-%m-%d %H:%M:%S}）", ""]
    lines.append(render(data, label))
    text = "\n".join(lines)
    print(text)

    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"\n已写入 {args.markdown}", file=sys.stderr)


if __name__ == "__main__":
    main()
