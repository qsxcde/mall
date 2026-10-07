#!/usr/bin/env bash
# ============================================================
# k6 压测全流程（与 run_tests.sh 同口径，用于消除 JMeter 压测机的 CPU 干扰）
#
# 用法：
#   bash loadtest/scripts/run_k6.sh
#   SKIP_RESET=1 SKIP_PREPARE=1 SKIP_CONCURRENCY=1 bash loadtest/scripts/run_k6.sh
#
# 可覆盖参数：
#   USERS=1000
#   STEADY_VUS=200  STEADY_ITER=100      # 场景一 持续压力
#   BURST_VUS=1000  BURST_ITER=1         # 场景二 瞬时峰值
#   READ_VUS=200    READ_ITER=100        # 读链路
#   SECKILL_ITEM=1  SECKILL_STOCK=200
#   CONCURRENCY_ITEM=2 CONCURRENCY_VUS=500
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPTS="$ROOT_DIR/loadtest/scripts"
K6_DIR="$ROOT_DIR/loadtest/k6"
RESULTS="$ROOT_DIR/loadtest/results"
DATA="$ROOT_DIR/loadtest/data"
BASE_URL="${BASE_URL:-http://localhost:8080}"

USERS="${USERS:-1000}"
STEADY_VUS="${STEADY_VUS:-200}"
STEADY_ITER="${STEADY_ITER:-100}"
BURST_VUS="${BURST_VUS:-1000}"
BURST_ITER="${BURST_ITER:-1}"
READ_VUS="${READ_VUS:-200}"
READ_ITER="${READ_ITER:-100}"
SECKILL_ITEM="${SECKILL_ITEM:-1}"
SECKILL_STOCK="${SECKILL_STOCK:-200}"
CONCURRENCY_ITEM="${CONCURRENCY_ITEM:-2}"
CONCURRENCY_STOCK="${CONCURRENCY_STOCK:-50}"
CONCURRENCY_VUS="${CONCURRENCY_VUS:-500}"

mkdir -p "$RESULTS" "$DATA"

mysql_exec() {
  docker exec -i geek-mall-mysql mysql -uroot -proot -N geek_mall -e "$1"
}

run_k6() {
  local script="$1" summary="$2"; shift 2
  rm -f "$summary"
  BASE_URL="$BASE_URL" k6 run \
    --summary-export="$summary" \
    --summary-trend-stats="avg,min,med,max,p(50),p(90),p(99)" \
    "$@" "$script"
  [[ -s "$summary" ]] || { echo "k6 未产出 summary：$summary" >&2; exit 1; }
}

# 重置秒杀活动库存 + 清掉上一轮秒杀订单，保证「订单数 == 发放库存」可独立核对
reset_seckill_stock() {
  mysql_exec "
DELETE l FROM oms_order_status_log l JOIN oms_order o ON o.order_no = l.order_no
  JOIN sys_user u ON u.id = o.user_id WHERE u.phone LIKE '139%';
DELETE r FROM inventory_rollback_log r JOIN oms_order o ON o.order_no = r.order_no
  JOIN sys_user u ON u.id = o.user_id WHERE u.phone LIKE '139%';
DELETE i FROM oms_order_item i JOIN oms_order o ON o.order_no = i.order_no
  JOIN sys_user u ON u.id = o.user_id WHERE u.phone LIKE '139%';
DELETE o FROM oms_order o JOIN sys_user u ON u.id = o.user_id WHERE u.phone LIKE '139%';
UPDATE mkt_seckill_item SET stock=$SECKILL_STOCK, total=$SECKILL_STOCK, sold=0, not_start=0 WHERE id=$SECKILL_ITEM;
UPDATE pms_product SET stock=100000, sales=0 WHERE id=(SELECT product_id FROM mkt_seckill_item WHERE id=$SECKILL_ITEM);
" >/dev/null
  docker exec geek-mall-redis redis-cli SET "mall:seckill:stock:$SECKILL_ITEM" "$SECKILL_STOCK" >/dev/null
  docker exec geek-mall-redis redis-cli DEL "mall:seckill:bought:$SECKILL_ITEM" >/dev/null
}

VERIFY_RC=0
# 返回校验退出码（不在函数内部累计），由调用方决定是「致命」还是「仅记录」
run_verify() {
  local item="$1" stock="$2"; shift 2
  set +e
  python3 "$SCRIPTS/verify_results.py" --seckill-item "$item" --seckill-stock "$stock" "$@"
  local rc=$?
  set -e
  return $rc
}

# 阶段 2 的校验是「准入门槛」：不通过直接终止
gate_verify() {
  if ! run_verify "$@"; then
    echo "并发正确性测试未通过，终止后续压测" >&2
    exit 1
  fi
}

# 阶段 3+ 的校验只做「结果核对」：失败也要把指标报告产出
soft_verify() {
  if ! run_verify "$@"; then
    VERIFY_RC=1
  fi
  return 0
}

echo "############################################################"
echo "# 阶段 0：环境重置"
echo "############################################################"
if [[ "${SKIP_RESET:-0}" != "1" ]]; then
  bash "$SCRIPTS/reset_env.sh"
else
  echo "（SKIP_RESET=1，跳过）"
fi

echo
echo "############################################################"
echo "# 阶段 1：准备压测用户 + 生成 k6 账号模块（${USERS} 个）"
echo "############################################################"
if [[ "${SKIP_PREPARE:-0}" != "1" ]]; then
  python3 "$SCRIPTS/prepare_data.py" "$USERS"
  python3 "$SCRIPTS/gen_k6_users.py" "$DATA/users.csv" "$K6_DIR/data/users.js" "$USERS"
else
  echo "（SKIP_PREPARE=1，跳过）"
fi

FIXED_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"account":"13800000000","password":"123456"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
echo "固定账号令牌长度：${#FIXED_TOKEN}"

echo
echo "############################################################"
echo "# 阶段 2：并发正确性测试（k6）"
echo "############################################################"
if [[ "${SKIP_CONCURRENCY:-0}" != "1" ]]; then
  echo "--- 2.1 同一 requestId 并发下单 100 次（P1-4）---"
  SCENARIO=idempotent FIXED_TOKEN="$FIXED_TOKEN" \
    run_k6 "$K6_DIR/concurrency.js" "$RESULTS/k6-concurrency-idem.summary.json"
  gate_verify 1 0 --skip-seckill --skip-cancel

  echo "--- 2.2 同一订单并发取消 100 次（P0-4）---"
  SCENARIO=cancel FIXED_TOKEN="$FIXED_TOKEN" \
    run_k6 "$K6_DIR/concurrency.js" "$RESULTS/k6-concurrency-cancel.summary.json"
  gate_verify 1 0 --skip-seckill

  echo "--- 2.3 并发秒杀防超卖（P0-2 / P2-6）---"
  mysql_exec "
UPDATE mkt_seckill_item SET stock=$CONCURRENCY_STOCK, total=$CONCURRENCY_STOCK, sold=0, not_start=0 WHERE id=$CONCURRENCY_ITEM;
UPDATE pms_product SET stock=100000, sales=0 WHERE id=(SELECT product_id FROM mkt_seckill_item WHERE id=$CONCURRENCY_ITEM);
" >/dev/null
  docker exec geek-mall-redis redis-cli SET "mall:seckill:stock:$CONCURRENCY_ITEM" "$CONCURRENCY_STOCK" >/dev/null
  docker exec geek-mall-redis redis-cli DEL "mall:seckill:bought:$CONCURRENCY_ITEM" >/dev/null
  SCENARIO=oversell VUS="$CONCURRENCY_VUS" ITEM_ID="$CONCURRENCY_ITEM" \
    run_k6 "$K6_DIR/concurrency.js" "$RESULTS/k6-concurrency-oversell.summary.json"
  gate_verify "$CONCURRENCY_ITEM" "$CONCURRENCY_STOCK"

  echo "并发正确性测试全部通过 ✅"
else
  echo "（SKIP_CONCURRENCY=1，跳过）"
fi

echo
echo "############################################################"
echo "# 阶段 3：场景一 秒杀持续压力（${STEADY_VUS} VU × ${STEADY_ITER} 轮）"
echo "############################################################"
reset_seckill_stock
ITEM_ID="$SECKILL_ITEM" VUS="$STEADY_VUS" ITERATIONS="$STEADY_ITER" \
  run_k6 "$K6_DIR/seckill.js" "$RESULTS/k6-seckill-steady.summary.json"
soft_verify "$SECKILL_ITEM" "$SECKILL_STOCK"

echo
echo "############################################################"
echo "# 阶段 4：场景二 秒杀瞬时峰值（${BURST_VUS} VU × ${BURST_ITER} 轮）"
echo "############################################################"
reset_seckill_stock
ITEM_ID="$SECKILL_ITEM" VUS="$BURST_VUS" ITERATIONS="$BURST_ITER" \
  run_k6 "$K6_DIR/seckill.js" "$RESULTS/k6-seckill-burst.summary.json"
soft_verify "$SECKILL_ITEM" "$SECKILL_STOCK"

echo
echo "############################################################"
echo "# 阶段 5：读链路压力（${READ_VUS} VU × ${READ_ITER} 轮）"
echo "############################################################"
curl -s -m 5 "$BASE_URL/api/v1/home/floors" >/dev/null || true
for pid in $(seq 1 13); do curl -s -m 5 "$BASE_URL/api/v1/products/${pid}" >/dev/null || true; done
VUS="$READ_VUS" ITERATIONS="$READ_ITER" \
  run_k6 "$K6_DIR/read.js" "$RESULTS/k6-read.summary.json"

echo
echo "############################################################"
echo "# 阶段 6：指标渲染"
echo "############################################################"
python3 "$SCRIPTS/k6_summary.py" "$RESULTS/k6-seckill-steady.summary.json" \
  --label "场景一 · 秒杀持续压力（k6 ${STEADY_VUS} VU × ${STEADY_ITER} 轮）" \
  --markdown "$RESULTS/k6-metrics-steady.md"

echo
python3 "$SCRIPTS/k6_summary.py" "$RESULTS/k6-seckill-burst.summary.json" \
  --label "场景二 · 秒杀瞬时峰值（k6 ${BURST_VUS} VU 一次性齐发）" \
  --markdown "$RESULTS/k6-metrics-burst.md"

echo
python3 "$SCRIPTS/k6_summary.py" "$RESULTS/k6-read.summary.json" \
  --label "读链路 · 首页 + 商品详情（k6 ${READ_VUS} VU × ${READ_ITER} 轮）" \
  --markdown "$RESULTS/k6-metrics-read.md"

echo
echo "全部完成。原始结果：$RESULTS/"
exit $VERIFY_RC
