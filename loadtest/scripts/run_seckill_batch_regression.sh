#!/usr/bin/env bash
# ============================================================
# 批量落库回归（mq-bucket-batch）
#
# 这是 2026-10-24《批量落库对比》给「真正该做的事」列的第 ③ 项：
# 把 mq-bucket-batch 固化成可重复执行的回归，锁住三件事不退化：
#   ① 成交数正确（200/200，不超卖、不少卖）；
#   ② 批量参与度 ≥ 90%（参数被改回 16/64 时立刻红灯）；
#   ③ 落库 TPS ≥ 基线（默认 60，对应逐条 48.5 的 +24%）。
#
# 用法：
#   bash loadtest/scripts/run_seckill_batch_regression.sh
#   VUS=200 ITER=100 MIN_TPS=70 bash loadtest/scripts/run_seckill_batch_regression.sh
#
# 可覆盖参数：
#   CONSUMER_THREADS=8   消费线程（实测最优组合的一半：8 线程 / 批 128）
#   BATCH_SIZE=128       单批订单数
#   BUCKETS=10           秒杀库存桶数
#   VUS / ITER / STOCK   负载与库存
#   MIN_TPS=60           落库 TPS 下限
#   MIN_BATCH_RATIO=90   批量单数占比下限（%）
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPTS="$ROOT_DIR/loadtest/scripts"
RESULTS="$ROOT_DIR/loadtest/results"

CONSUMER_THREADS="${CONSUMER_THREADS:-8}"
BATCH_SIZE="${BATCH_SIZE:-128}"
BUCKETS="${BUCKETS:-10}"
VUS="${VUS:-200}"
ITER="${ITER:-100}"
STOCK="${STOCK:-200}"
MIN_TPS="${MIN_TPS:-60}"
MIN_BATCH_RATIO="${MIN_BATCH_RATIO:-90}"

cd "$ROOT_DIR"
echo "=== 批量落库回归：${CONSUMER_THREADS} 线程 / 批 ${BATCH_SIZE} / ${BUCKETS} 桶 / ${VUS}VU×${ITER} ==="
ARMS=mq-bucket-batch \
BUCKETS="$BUCKETS" \
CONSUMER_THREADS="$CONSUMER_THREADS" \
BATCH_SIZE="$BATCH_SIZE" \
VUS="$VUS" \
ITER="$ITER" \
STOCK="$STOCK" \
  bash "$SCRIPTS/run_bucket_compare.sh"

# run_bucket_compare.sh 写入的排空行：
#   arm / 成功 / 跨度ms / tps / 批次数 / 平均批量 / 批量单数 / 逐条单数
line="$(grep '^mq-bucket-batch' "$RESULTS/bench-drain.tsv" | tail -1)"
if [[ -z "$line" ]]; then
  echo "FAIL: 未找到 mq-bucket-batch 的排空记录" >&2
  exit 1
fi

total="$(awk '{print $2}' <<< "$line")"
tps="$(awk '{print $4}' <<< "$line")"
in_batch="$(awk '{print $7}' <<< "$line")"
single="$(awk '{print $8}' <<< "$line")"
ratio=0
if [[ "$total" -gt 0 ]]; then
  ratio=$(( in_batch * 100 / total ))
fi

status="PASS"
if [[ "$total" -ne "$STOCK" ]]; then
  echo "FAIL: 成交 $total != 库存 $STOCK（正确性回归失败）" >&2
  status="FAIL"
fi
if awk "BEGIN {exit !($tps < $MIN_TPS)}"; then
  echo "FAIL: 落库 TPS $tps < $MIN_TPS" >&2
  status="FAIL"
fi
if [[ "$ratio" -lt "$MIN_BATCH_RATIO" ]]; then
  echo "FAIL: 批量占比 ${ratio}% < ${MIN_BATCH_RATIO}%（逐条回退 ${single} 单）" >&2
  status="FAIL"
fi

summary="$RESULTS/batch-regression-$(date +%Y%m%d-%H%M).md"
{
  echo "# 批量落库回归（$(date '+%Y-%m-%d %H:%M')）"
  echo
  echo "| 指标 | 实测 | 门槛 | 结果 |"
  echo "| --- | ---: | ---: | --- |"
  echo "| 成交数 | $total | $STOCK | $([[ "$total" -eq "$STOCK" ]] && echo PASS || echo FAIL) |"
  echo "| 落库 TPS | $tps | ≥ $MIN_TPS | $(awk "BEGIN {print ($tps >= $MIN_TPS) ? \"PASS\" : \"FAIL\"}") |"
  echo "| 批量占比 | ${ratio}% | ≥ ${MIN_BATCH_RATIO}% | $([[ "$ratio" -ge "$MIN_BATCH_RATIO" ]] && echo PASS || echo FAIL) |"
  echo "| 逐条回退 | ${single} 单 | — | — |"
  echo
  echo "参数：${CONSUMER_THREADS} 线程 / 批 ${BATCH_SIZE} / ${BUCKETS} 桶 / ${VUS}VU×${ITER}"
  echo "原始结果：\`bench-mq-bucket-batch.json\` / \`bench-drain.tsv\`"
  echo
  echo "**总体：$status**"
} > "$summary"

cat "$summary"
[[ "$status" == "PASS" ]]
