#!/usr/bin/env python3
"""长稳压测期间的服务端指标采样。

每 ``--interval`` 秒抓一次 ``/actuator/prometheus``（HTTP Basic ``obs``）与
``docker stats``，输出 CSV。判读方式：

* ``heap_used_mb`` 单调上涨且不回落 → 内存泄漏；
* ``hikari_active`` 持续贴住池上限、``hikari_pending`` 长期 > 0 → 连接不够或泄漏；
* ``tomcat_busy`` 持续接近 max-threads → 下游变慢，线程被占满；
* ``redis_mem_mb`` 持续上涨 → 缓存 key 无界增长。

用法：
  python3 loadtest/scripts/soak_monitor.py --duration 1860 --interval 15 \
      --out loadtest/results/soak-20261026.csv
"""

from __future__ import annotations

import argparse
import base64
import csv
import os
import re
import subprocess
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load_env() -> dict[str, str]:
    """读取 infra/.env（properties 格式），缺失时返回空。"""
    env: dict[str, str] = {}
    path = ROOT / "infra" / ".env"
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip()
    return env


def scrape(port: int, password: str) -> dict[str, float]:
    """抓 Prometheus 文本，抽取关心的指标（多实例/多标签时取 sum）。"""
    url = f"http://localhost:{port}/actuator/prometheus"
    request = urllib.request.Request(url)
    token = base64.b64encode(f"obs:{password}".encode()).decode()
    request.add_header("Authorization", f"Basic {token}")
    text = urllib.request.urlopen(request, timeout=5).read().decode("utf-8", "ignore")

    wanted = {
        # 标签里 application 排在 area 前面，不能假设 area 紧跟左花括号
        "heap_used_mb": r'jvm_memory_used_bytes\{[^}]*area="heap"[^}]*\}\s+([\d.eE+]+)',
        "hikari_active": r'hikaricp_connections_active\{[^}]*\}\s+([\d.eE+]+)',
        "hikari_idle": r'hikaricp_connections_idle\{[^}]*\}\s+([\d.eE+]+)',
        "hikari_pending": r'hikaricp_connections_pending\{[^}]*\}\s+([\d.eE+]+)',
        "tomcat_busy": r'tomcat_threads_busy_threads\{[^}]*\}\s+([\d.eE+]+)',
        "tomcat_current": r'tomcat_threads_current_threads\{[^}]*\}\s+([\d.eE+]+)',
        "queue_pending": r"mall_seckill_queue_pending\{[^}]*\}\s+([\d.eE+]+)",
        "gc_pause_seconds": r"jvm_gc_pause_seconds_sum\{[^}]*\}\s+([\d.eE+]+)",
        "process_cpu": r"process_cpu_usage\{[^}]*\}\s+([\d.eE+]+)",
    }
    result: dict[str, float] = {}
    for key, pattern in wanted.items():
        values = [float(m) for m in re.findall(pattern, text)]
        if not values:
            continue
        # 堆按 area 可能有多条（heap 已限定），其余多标签取总和
        result[key] = sum(values) / 1_000_000 if key == "heap_used_mb" else sum(values)
    return result


def container_mem() -> dict[str, float]:
    """docker stats 的内存占用（MiB）。"""
    try:
        out = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{.Name}} {{.MemUsage}}"],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        ).stdout
    except Exception:
        return {}
    mem: dict[str, float] = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue
        name = parts[0]
        raw = parts[1]
        match = re.match(r"([\d.]+)([KMG]i?B)", raw)
        if not match:
            continue
        value = float(match.group(1))
        unit = match.group(2)
        factor = {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024, "KB": 1 / 1000, "MB": 1, "GB": 1000}[unit]
        if name in ("geek-mall-mysql", "geek-mall-redis"):
            mem["mysql_mem_mb" if "mysql" in name else "redis_mem_mb"] = value * factor
    return mem


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--duration", type=int, default=1860, help="采样总时长（秒）")
    parser.add_argument("--interval", type=int, default=15)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--out", default="loadtest/results/soak.csv")
    args = parser.parse_args()

    env = load_env()
    password = env.get("MALL_OBS_PASSWORD", "dev-obs-password-change-me")
    out_path = ROOT / args.out
    out_path.parent.mkdir(parents=True, exist_ok=True)

    fields = [
        "ts",
        "heap_used_mb",
        "hikari_active",
        "hikari_idle",
        "hikari_pending",
        "tomcat_busy",
        "tomcat_current",
        "queue_pending",
        "gc_pause_seconds",
        "process_cpu",
        "mysql_mem_mb",
        "redis_mem_mb",
    ]
    deadline = time.time() + args.duration
    with out_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        while time.time() < deadline:
            row: dict[str, float | str] = {"ts": time.strftime("%H:%M:%S")}
            try:
                row.update(scrape(args.port, password))
            except Exception as ex:  # 抓取失败也要留痕，不能静默跳过
                row["error"] = str(ex)[:60]
            # docker stats 在 macOS 上偶发挂起，采样循环不能因此停摆：
            # 它只影响「容器内存」两列，拿不到就留空，主指标（堆/Hikari/tomcat）继续采。
            try:
                row.update(container_mem())
            except Exception:
                pass
            writer.writerow({key: row.get(key, "") for key in fields})
            handle.flush()
            time.sleep(args.interval)

    print(f"采样完成：{out_path}")


if __name__ == "__main__":
    main()
