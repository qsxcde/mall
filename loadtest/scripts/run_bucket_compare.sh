#!/usr/bin/env bash
# ============================================================
# 秒杀库存分桶 × MQ 的 2×2 对比（单热点 SKU）
#
#   组别： A=sync-nobucket  B=sync-bucket  C=mq-nobucket  D=mq-bucket
#          E=mq-nobucket-batch  F=mq-bucket-batch（消费端批量落库）
#   场景： item1 库存 200，200 VU × 100 轮（每 VU 固定账号，最多成交 1 单/人）
#   口径： 与 docs/benchmark/跨机压测第二轮-MQ对比.md §6.1 对齐
#          —— 限流关闭、消费线程 64（对齐同步侧有效并行度）
#
# 用法：
#   bash loadtest/scripts/run_bucket_compare.sh                 # 跑全部 4 组
#   ARMS=sync-nobucket,sync-bucket bash loadtest/scripts/run_bucket_compare.sh
#   ARMS=mq-bucket,mq-bucket-batch bash loadtest/scripts/run_bucket_compare.sh   # 批量落库 A/B
#
# 可覆盖参数：
#   BUCKETS=10          分桶组的桶数（受 Hikari 池 50 截断，见文档 §2.4）
#   VUS=200 ITER=100    负载
#   ITEM=1 STOCK=200    单热点 SKU 与其库存
#   CONSUMER_THREADS=64 削峰消费线程数
#
# 另外每组会采集 MySQL 的 Innodb_row_lock_* 累计值到 results/bench-rowlocks.tsv，
# 用于判断「pms_product 热点行的串行是否仍是瓶颈」（Stage 2 决策依据）。
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPTS="$ROOT_DIR/loadtest/scripts"
K6_DIR="$ROOT_DIR/loadtest/k6"
RESULTS="$ROOT_DIR/loadtest/results"
DATA="$ROOT_DIR/loadtest/data"
BASE_URL="${BASE_URL:-http://localhost:8080}"
PORT="${PORT:-8080}"

USERS="${USERS:-1000}"

BUCKETS="${BUCKETS:-10}"
VUS="${VUS:-200}"
ITER="${ITER:-100}"
ITEM="${ITEM:-1}"
STOCK="${STOCK:-200}"
CONSUMER_THREADS="${CONSUMER_THREADS:-64}"
BATCH_SIZE="${BATCH_SIZE:-16}"
ARMS="${ARMS:-sync-nobucket,sync-bucket,mq-nobucket,mq-bucket}"
APP_LOG="${GM_LOG:-/tmp/geekmall-app.log}"

# 组别定义：名字|是否异步|桶数|是否批量落库
arm_spec() {
  case "$1" in
    sync-nobucket)     echo "false|1|false" ;;
    sync-bucket)       echo "false|$BUCKETS|false" ;;
    mq-nobucket)       echo "true|1|false" ;;
    mq-bucket)         echo "true|$BUCKETS|false" ;;
    mq-nobucket-batch) echo "true|1|true" ;;
    mq-bucket-batch)   echo "true|$BUCKETS|true" ;;
    *) echo "未知组别：$1" >&2; return 1 ;;
  esac
}

# MySQL 行锁累计值（Innodb_row_lock_*）。批量落库是否真的打破了
# pms_product 热点行的串行，最终要由「行锁等待次数/时长」的变化来佐证。
row_lock_stats() {
  docker exec -i geek-mall-mysql mysql -uroot -proot -N -e "
SHOW GLOBAL STATUS WHERE Variable_name IN
  ('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_row_lock_time_avg',
   'Innodb_rows_inserted','Innodb_rows_updated','Innodb_commits','Innodb_row_lock_time_max');" 2>/dev/null \
    | awk '{printf "%s=%s ", $1, $2} END {print ""}'
}

# 从应用日志里量「落库排空时间」：首笔成功 → 末笔成功的墙钟跨度（毫秒）+ 成功笔数。
# 比 k6 的汇总更贴近「DB 到底多久把这批单写完」，用于换算落库 TPS。
#
# 日志有两种形态，必须都算进来，否则批量落库组会被严重少计：
#   逐条：一行一单 —— 「秒杀成功：」（同步）/「落库成功：」（异步逐条）
#   批量：一行 N 单 —— 「批量落库完成：成功=N」（消费端批量路径，每批一行）
drain_stats() {
  python3 - "$APP_LOG" <<'PY'
import re, sys
from datetime import datetime

path = sys.argv[1]
pat_single = re.compile(r'^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*(?:秒杀成功：|落库成功：)')
pat_batch = re.compile(r'^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*批量落库完成：成功=(\d+)')
events = []
try:
    with open(path, encoding='utf-8', errors='ignore') as f:
        for line in f:
            m = pat_batch.match(line)
            if m:
                events.append((m.group(1), int(m.group(2))))
                continue
            m = pat_single.match(line)
            if m:
                events.append((m.group(1), 1))
except FileNotFoundError:
    pass

if events:
    fmt = '%Y-%m-%d %H:%M:%S.%f'
    total = sum(cnt for _, cnt in events)
    lo = datetime.strptime(events[0][0], fmt)
    hi = datetime.strptime(events[-1][0], fmt)
    span = (hi - lo).total_seconds()
    tps = (total / span) if span > 0 else float(total)
    # 「批量行」= 一次事务落了 >1 单的日志行；单行走逐条路径
    batch_lines = [(ts, cnt) for ts, cnt in events if cnt > 1]
    in_batch = sum(cnt for _, cnt in batch_lines)
    avg_batch = (in_batch / len(batch_lines)) if batch_lines else 0.0
    print(f"{total}\t{span*1000:.0f}\t{tps:.1f}\t{len(batch_lines)}\t{avg_batch:.1f}\t{in_batch}\t{total - in_batch}")
else:
    print("0\t0\t0\t0\t0\t0\t0")
PY
}

IFS=',' read -r -a ARM_LIST <<< "$ARMS"

# JWT 有效期仅 120 分钟：跨组复用上一次生成的 users.js 会因令牌过期而全部 401
# （表现为 order_rejected=全部、成交 0）。因此每组开跑前先重新登录换新令牌。
echo "############################################################"
echo "# 阶段 0：刷新压测账号令牌（${USERS} 个）"
echo "############################################################"
# prepare_data.py 要打后端接口换 JWT，后端没起就直接 Connection refused。
# 冷启动时后端要到下面的 arm 循环里才起，这里先兜底拉一次
# （后续每组仍会按各自开关重启，不影响对比口径）。
if ! curl -s --max-time 3 "http://localhost:$PORT/api/v1/cms/faqs" >/dev/null 2>&1; then
  echo "后端未就绪，先拉起一次以便换取压测令牌..."
  SECKILL_ASYNC_ENABLED=false SECKILL_BATCH_ENABLED=false \
  SECKILL_BATCH_SIZE="$BATCH_SIZE" \
  CONSUMER_THREADS="$CONSUMER_THREADS" \
  SECKILL_BUCKETS="$BUCKETS" \
    bash "$SCRIPTS/reset_env.sh"
fi
python3 "$SCRIPTS/prepare_data.py" "$USERS"
python3 "$SCRIPTS/gen_k6_users.py" "$DATA/users.csv" "$K6_DIR/data/users.js" "$USERS"

for arm in "${ARM_LIST[@]}"; do
  spec="$(arm_spec "$arm")"
  async="$(echo "$spec" | cut -d'|' -f1)"
  buckets="$(echo "$spec" | cut -d'|' -f2)"
  batch="$(echo "$spec" | cut -d'|' -f3)"

  echo
  echo "############################################################"
  echo "# 组别 $arm ：异步=$async  桶数=$buckets  批量落库=$batch  （${VUS}VU × ${ITER}，item$ITEM 库存 ${STOCK}）"
  echo "############################################################"
  : > "$APP_LOG"   # 清空日志，便于按组精确量排空时间
  SECKILL_ASYNC_ENABLED="$async" \
  SECKILL_BATCH_ENABLED="$batch" \
  SECKILL_BATCH_SIZE="$BATCH_SIZE" \
  CONSUMER_THREADS="$CONSUMER_THREADS" \
  SECKILL_BUCKETS="$buckets" \
    bash "$SCRIPTS/reset_env.sh"

  locks_before="$(row_lock_stats)"
  summary="$RESULTS/bench-$arm.json"
  rm -f "$summary"
  ASYNC="$async" ITEM_ID="$ITEM" VUS="$VUS" ITERATIONS="$ITER" \
    k6 run --summary-export="$summary" \
      --summary-trend-stats="avg,min,med,max,p(50),p(90),p(99)" \
      "$K6_DIR/seckill.js"
  locks_after="$(row_lock_stats)"

  python3 "$SCRIPTS/k6_summary.py" "$summary" \
    --label "秒杀分桶对比 · ${arm}（异步=$async 桶数=$buckets 批量=$batch ${VUS}VU×${ITER}）" \
    --markdown "$RESULTS/bench-$arm.md"

  echo "--- $arm 结果核对（DB 断言）---"
  set +e
  python3 "$SCRIPTS/verify_results.py" --seckill-item "$ITEM" --seckill-stock "$STOCK"
  set -e

  drain="$(drain_stats)"
  echo "--- $arm 落库排空（成功 / 跨度ms / tps / 批次数 / 平均批量 / 批量单数 / 逐条单数）：$drain ---"
  printf '%s\t%s\n' "$arm" "$drain" >> "$RESULTS/bench-drain.tsv"
  echo "--- $arm MySQL 行锁（前）：$locks_before"
  echo "--- $arm MySQL 行锁（后）：$locks_after"
  printf '%s\tBEFORE\t%s\n%s\tAFTER\t%s\n' "$arm" "$locks_before" "$arm" "$locks_after" >> "$RESULTS/bench-rowlocks.tsv"
  # 批量落库的参与度：批量路径到底吃下了多少单、每批多大。
  # 批量关闭的组里这些指标本就不存在，grep 无匹配会返回 1，
  # 而脚本是 set -euo pipefail —— 必须吞掉退出码，否则整轮对比在这里中断。
  batch_metrics="$(curl -s --max-time 5 "http://localhost:$PORT/actuator/prometheus" 2>/dev/null \
    | grep -E '^mall_seckill_batch' | tr '\n' '; ' || true)"
  echo "--- $arm 批量指标：${batch_metrics:-（无，批量未启用或无命中）}"
  printf '%s\t%s\n' "$arm" "${batch_metrics:-none}" >> "$RESULTS/bench-batch-metrics.tsv"
done

echo
echo "对比完成，原始结果在 $RESULTS/bench-*.json，汇总 md 在 $RESULTS/bench-*.md"
