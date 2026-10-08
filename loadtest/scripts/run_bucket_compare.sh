#!/usr/bin/env bash
# ============================================================
# 秒杀库存分桶 × MQ 的 2×2 对比（单热点 SKU）
#
#   组别： A=sync-nobucket  B=sync-bucket  C=mq-nobucket  D=mq-bucket
#   场景： item1 库存 200，200 VU × 100 轮（每 VU 固定账号，最多成交 1 单/人）
#   口径： 与 docs/benchmark/跨机压测第二轮-MQ对比.md §6.1 对齐
#          —— 限流关闭、消费线程 64（对齐同步侧有效并行度）
#
# 用法：
#   bash loadtest/scripts/run_bucket_compare.sh                 # 跑全部 4 组
#   ARMS=sync-nobucket,sync-bucket bash loadtest/scripts/run_bucket_compare.sh
#
# 可覆盖参数：
#   BUCKETS=10          分桶组的桶数（受 Hikari 池 50 截断，见文档 §2.4）
#   VUS=200 ITER=100    负载
#   ITEM=1 STOCK=200    单热点 SKU 与其库存
#   CONSUMER_THREADS=64 削峰消费线程数
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPTS="$ROOT_DIR/loadtest/scripts"
K6_DIR="$ROOT_DIR/loadtest/k6"
RESULTS="$ROOT_DIR/loadtest/results"
DATA="$ROOT_DIR/loadtest/data"
BASE_URL="${BASE_URL:-http://localhost:8080}"

USERS="${USERS:-1000}"

BUCKETS="${BUCKETS:-10}"
VUS="${VUS:-200}"
ITER="${ITER:-100}"
ITEM="${ITEM:-1}"
STOCK="${STOCK:-200}"
CONSUMER_THREADS="${CONSUMER_THREADS:-64}"
ARMS="${ARMS:-sync-nobucket,sync-bucket,mq-nobucket,mq-bucket}"
APP_LOG="${GM_LOG:-/tmp/geekmall-app.log}"

# 组别定义：名字|是否异步|桶数
arm_spec() {
  case "$1" in
    sync-nobucket) echo "false|1" ;;
    sync-bucket)   echo "false|$BUCKETS" ;;
    mq-nobucket)   echo "true|1" ;;
    mq-bucket)     echo "true|$BUCKETS" ;;
    *) echo "未知组别：$1" >&2; return 1 ;;
  esac
}

# 从应用日志里量「落库排空时间」：首笔成功 → 末笔成功的墙钟跨度（毫秒）+ 成功笔数。
# 比 k6 的汇总更贴近「DB 到底多久把这批单写完」，用于换算落库 TPS。
drain_stats() {
  python3 - "$APP_LOG" <<'PY'
import os, re, sys
path = sys.argv[1]
pat = re.compile(r'^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*(秒杀成功：|落库成功：)')
ts = []
try:
    with open(path, encoding='utf-8', errors='ignore') as f:
        for line in f:
            m = pat.match(line)
            if m:
                ts.append(m.group(1))
except FileNotFoundError:
    pass
if ts:
    from datetime import datetime
    fmt = '%Y-%m-%d %H:%M:%S.%f'
    lo, hi = datetime.strptime(ts[0], fmt), datetime.strptime(ts[-1], fmt)
    span = (hi - lo).total_seconds()
    # 首笔与末笔之间只有 n-1 个间隔，用 n 单 / span 近似稳态吞吐
    tps = (len(ts) / span) if span > 0 else float(len(ts))
    print(f"{len(ts)}\t{span*1000:.0f}\t{tps:.1f}")
else:
    print("0\t0\t0")
PY
}

IFS=',' read -r -a ARM_LIST <<< "$ARMS"

# JWT 有效期仅 120 分钟：跨组复用上一次生成的 users.js 会因令牌过期而全部 401
# （表现为 order_rejected=全部、成交 0）。因此每组开跑前先重新登录换新令牌。
echo "############################################################"
echo "# 阶段 0：刷新压测账号令牌（${USERS} 个）"
echo "############################################################"
python3 "$SCRIPTS/prepare_data.py" "$USERS"
python3 "$SCRIPTS/gen_k6_users.py" "$DATA/users.csv" "$K6_DIR/data/users.js" "$USERS"

for arm in "${ARM_LIST[@]}"; do
  spec="$(arm_spec "$arm")"
  async="${spec%%|*}"
  buckets="${spec##*|}"

  echo
  echo "############################################################"
  echo "# 组别 $arm ：异步=$async  桶数=$buckets  （${VUS}VU × ${ITER}，item$ITEM 库存 ${STOCK}）"
  echo "############################################################"
  : > "$APP_LOG"   # 清空日志，便于按组精确量排空时间
  SECKILL_ASYNC_ENABLED="$async" \
  CONSUMER_THREADS="$CONSUMER_THREADS" \
  SECKILL_BUCKETS="$buckets" \
    bash "$SCRIPTS/reset_env.sh"

  summary="$RESULTS/bench-$arm.json"
  rm -f "$summary"
  ASYNC="$async" ITEM_ID="$ITEM" VUS="$VUS" ITERATIONS="$ITER" \
    k6 run --summary-export="$summary" \
      --summary-trend-stats="avg,min,med,max,p(50),p(90),p(99)" \
      "$K6_DIR/seckill.js"

  python3 "$SCRIPTS/k6_summary.py" "$summary" \
    --label "秒杀分桶对比 · ${arm}（异步=$async 桶数=$buckets ${VUS}VU×${ITER}）" \
    --markdown "$RESULTS/bench-$arm.md"

  echo "--- $arm 结果核对（DB 断言）---"
  set +e
  python3 "$SCRIPTS/verify_results.py" --seckill-item "$ITEM" --seckill-stock "$STOCK"
  set -e

  drain="$(drain_stats)"
  echo "--- $arm 落库排空（成功笔数 / 跨度ms / 折算tps）：$drain ---"
  printf '%s\t%s\n' "$arm" "$drain" >> "$RESULTS/bench-drain.tsv"
done

echo
echo "对比完成，原始结果在 $RESULTS/bench-*.json，汇总 md 在 $RESULTS/bench-*.md"
