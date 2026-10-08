#!/usr/bin/env python3
"""
秒杀抢购结果采集（P0-5 的前置数据）。

为什么需要它
------------
`GET /api/v1/seckill/result/{requestId}` 是削峰模式**新增的读放大路径**：
前端要为每个抢购请求反复轮询，这条流量在同步模式下根本不存在。
要单独压它，就必须先有一批「真实且仍然有效」的 requestId —— 而且因为接口会校验归属，
每个 requestId 必须配**它所属用户的 token**，不能拿一个 token 乱查。

于是本脚本把两边接起来：
  Redis `mall:seckill:result:*`（requestId / userId / status）
  + `loadtest/data/users.csv`（userId → token）
  → `loadtest/k6/data/seckill-results.js`（k6 可直接 import 的静态模块）

用法
----
  # 秒杀压测跑完之后，在**跑着 Redis 容器的那台机器**上执行
  python3 collect_seckill_results.py

  # 只要成功终态、最多 5000 条
  python3 collect_seckill_results.py --status SUCCESS --limit 5000

  # 自定义输入输出
  python3 collect_seckill_results.py --users data/users.csv --out k6/data/seckill-results.js

接着即可跑纯读压测：
  RATE=500 DURATION=60s k6 run loadtest/k6/seckill-result-read.js

注意
----
* **结果 key 有 TTL（`mall.seckill.async.result-ttl`，默认 30m）**，采集必须紧接压测；
  同理 **token 有效期 2 小时**，超时请重跑 `prepare_data.py` + `gen_k6_users.py` 再采集。
* 产物落在 `loadtest/k6/data/`，该目录已被 `.gitignore` 排除（含 JWT），**不要入库**。
"""
import argparse
import csv
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REDIS_CONTAINER = os.environ.get("REDIS_CONTAINER", "geek-mall-redis")
RESULT_PATTERN = "mall:seckill:result:*"
STATUS_RE = re.compile(r'"status"\s*:\s*"(SUCCESS|QUEUED|FAILED)"')
REQUEST_ID_RE = re.compile(r'"requestId"\s*:\s*"([^"]+)"')
MGET_CHUNK = 500


def redis(*args) -> list:
    cmd = ["docker", "exec", REDIS_CONTAINER, "redis-cli", "--raw", *args]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        # 这里只用 SCAN / MGET，不会因 key 不存在报错；非 0 一律是环境问题
        # （Docker 未启动 / 容器名不对），直接把原始报错抛出来，别伪装成「没有数据」
        stderr = proc.stderr.decode("utf-8", "ignore").strip()
        raise SystemExit(
            f"访问 Redis 失败（容器 {REDIS_CONTAINER}）：{stderr[:300]}\n"
            f"请确认 Docker 已启动且容器名正确（可用 REDIS_CONTAINER 环境变量覆盖）"
        )
    return [line for line in proc.stdout.decode("utf-8", "ignore").splitlines() if line.strip()]


def load_tokens(csv_path: Path) -> dict:
    """userId -> token。users.csv 由 prepare_data.py 生成。"""
    if not csv_path.exists():
        raise SystemExit(f"未找到 {csv_path}，请先在 A 机运行 prepare_data.py")
    tokens = {}
    with csv_path.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            tokens[str(row["userId"]).strip()] = row["token"].strip()
    return tokens


def scan_results() -> list:
    """从 Redis 扫描全部抢购结果，返回 [(requestId, userId, status)]。"""
    keys = redis("--scan", "--pattern", RESULT_PATTERN)
    if not keys:
        return []
    found = []
    for start in range(0, len(keys), MGET_CHUNK):
        values = redis("MGET", *keys[start:start + MGET_CHUNK])
        for value in values:
            if not value:
                continue
            status_match = STATUS_RE.search(value)
            request_match = REQUEST_ID_RE.search(value)
            user_match = re.search(r'"userId"\s*:\s*(\d+)', value)
            if not (status_match and request_match and user_match):
                continue
            found.append((request_match.group(1), user_match.group(1), status_match.group(1)))
    return found


def main():
    ap = argparse.ArgumentParser(description="采集秒杀抢购结果，生成 k6 轮询压测数据")
    ap.add_argument("--users", default=str(ROOT / "data" / "users.csv"), help="账号 CSV（含 token）")
    ap.add_argument("--out", default=str(ROOT / "k6" / "data" / "seckill-results.js"), help="输出 JS 模块")
    ap.add_argument("--status", default=None, choices=["SUCCESS", "QUEUED", "FAILED"],
                    help="只保留某个终态；默认全部（QUEUED 也可被轮询，便于测中间态读放大）")
    ap.add_argument("--limit", type=int, default=20000, help="最多导出条数，默认 20000")
    args = ap.parse_args()

    tokens = load_tokens(Path(args.users))
    found = scan_results()
    if not found:
        raise SystemExit("Redis 中没有抢购结果。请先跑一轮削峰模式（SECKILL_ASYNC_ENABLED=true）的秒杀压测")

    by_status = {"SUCCESS": 0, "QUEUED": 0, "FAILED": 0}
    rows = []
    skipped_no_token = 0
    for request_id, user_id, status in found:
        by_status[status] = by_status.get(status, 0) + 1
        if args.status and status != args.status:
            continue
        token = tokens.get(user_id)
        if not token:
            # 账号不在 users.csv（或已重新造数换了一批 userId）→ 无法通过归属校验
            skipped_no_token += 1
            continue
        rows.append({"requestId": request_id, "token": token, "status": status})

    rows = rows[:args.limit]
    if not rows:
        raise SystemExit("采集到结果，但没有一条能匹配到可用 token，请先重跑 prepare_data.py 造数")

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    body = ",\n".join("  " + json.dumps(r, ensure_ascii=False) for r in rows)
    out_path.write_text(
        "// ⚠️ 本文件由 loadtest/scripts/collect_seckill_results.py 自动生成，请勿手工编辑。\n"
        "// 含 JWT 与 requestId，禁止提交仓库（loadtest/k6/data/ 已在 .gitignore 中）。\n"
        f"// 来源：Redis {RESULT_PATTERN}（{len(rows)} 条可用）\n"
        "export const results = [\n"
        f"{body}\n"
        "];\n",
        encoding="utf-8",
    )

    print("=" * 68)
    print("秒杀抢购结果采集完成")
    print("=" * 68)
    print(f"  Redis 中结果总数：{len(found)}"
          f"（SUCCESS {by_status['SUCCESS']} / QUEUED {by_status['QUEUED']} / FAILED {by_status['FAILED']}）")
    if args.status:
        print(f"  按 --status {args.status} 过滤后：{sum(1 for r in rows if r['status'] == args.status)}")
    if skipped_no_token:
        print(f"  ⚠️ 因缺少对应 token 跳过 {skipped_no_token} 条（账号已重建？请重跑 prepare_data.py）")
    print(f"  已写出：{out_path}（{len(rows)} 条，{out_path.stat().st_size / 1024:.0f} KB）")
    print("\n下一步（B 机）：")
    print(f"  RATE=500 DURATION=60s k6 run "
          f"-e BASE_URL=http://<A_IP>:8080 loadtest/k6/seckill-result-read.js")


if __name__ == "__main__":
    main()
