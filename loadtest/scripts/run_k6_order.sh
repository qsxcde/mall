#!/usr/bin/env bash
# ============================================================
# 补测「下单链路」：
#   场景 A —— 普通下单链路（非热点 SKU）持续压测，补齐治理清单「TPS ≥ 300」
#   场景 B —— 秒杀瞬时洪峰 × 普通下单 同轮并发，验证「秒杀不拖垮普通下单接口」
#
# 用法：
#   bash loadtest/scripts/run_k6_order.sh
#   SKIP_RESET=1 SKIP_PREPARE=1 bash loadtest/scripts/run_k6_order.sh
#
# 可覆盖参数：
#   ORDER_VUS=200  ORDER_ITER=50          # 场景 A：200 × 50 = 10000 单
#   NORMAL_RATE=150 DURATION=30s           # 场景 B：普通下单 150 单/秒，持续 30s
#   SECKILL_START=12s SECKILL_VUS=1000     # 场景 B：第 12 秒放 1000 并发秒杀洪峰
#   SECKILL_ITEM=1 SECKILL_STOCK=200
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPTS="$ROOT_DIR/loadtest/scripts"
K6_DIR="$ROOT_DIR/loadtest/k6"
RESULTS="$ROOT_DIR/loadtest/results"
DATA="$ROOT_DIR/loadtest/data"
BASE_URL="${BASE_URL:-http://localhost:8080}"

USERS="${USERS:-1000}"
ORDER_VUS="${ORDER_VUS:-200}"
ORDER_ITER="${ORDER_ITER:-50}"
NORMAL_RATE="${NORMAL_RATE:-150}"
DURATION="${DURATION:-30s}"
SECKILL_START="${SECKILL_START:-12s}"
SECKILL_VUS="${SECKILL_VUS:-1000}"
SECKILL_ITEM="${SECKILL_ITEM:-1}"
SECKILL_STOCK="${SECKILL_STOCK:-200}"

mkdir -p "$RESULTS" "$DATA"

# 校验退出码（场景 A：下单一致性；场景 B：秒杀不超卖）
ORDER_RC=0
MIX_RC=0

mysql_exec() {
  docker exec -i geek-mall-mysql mysql -uroot -proot -N geek_mall -e "$1"
}

# 清理压测用户的下单数据 + 把商品库存/销量重置到可压测水位
reset_order_data() {
  mysql_exec "
DELETE l FROM oms_order_status_log l JOIN oms_order o ON o.order_no = l.order_no
  JOIN sys_user u ON u.id = o.user_id WHERE u.phone LIKE '139%';
DELETE r FROM inventory_rollback_log r JOIN oms_order o ON o.order_no = r.order_no
  JOIN sys_user u ON u.id = o.user_id WHERE u.phone LIKE '139%';
DELETE i FROM oms_order_item i JOIN oms_order o ON o.order_no = i.order_no
  JOIN sys_user u ON u.id = o.user_id WHERE u.phone LIKE '139%';
DELETE o FROM oms_order o JOIN sys_user u ON u.id = o.user_id WHERE u.phone LIKE '139%';
DELETE c FROM oms_cart_item c JOIN sys_user u ON u.id = c.user_id WHERE u.phone LIKE '139%';
UPDATE pms_product SET stock=1000000, sales=0 WHERE deleted=0;
" >/dev/null
  docker exec geek-mall-redis sh -c "redis-cli --scan --pattern 'mall:order:idempotent:*' | xargs -r redis-cli del" >/dev/null
}

reset_seckill_stock() {
  mysql_exec "
UPDATE mkt_seckill_item SET stock=$SECKILL_STOCK, total=$SECKILL_STOCK, sold=0, not_start=0 WHERE id=$SECKILL_ITEM;
" >/dev/null
  docker exec geek-mall-redis redis-cli SET "mall:seckill:stock:$SECKILL_ITEM" "$SECKILL_STOCK" >/dev/null
  docker exec geek-mall-redis redis-cli DEL "mall:seckill:bought:$SECKILL_ITEM" >/dev/null
}

count_metric() {
  python3 -c "
import json,sys
d=json.load(open('$1'))
v=d.get('metrics',{}).get('$2') or {}
print(int(v.get('count',0)))"
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
echo "# 阶段 1：准备压测账号（${USERS} 个）"
echo "############################################################"
if [[ "${SKIP_PREPARE:-0}" != "1" ]]; then
  python3 "$SCRIPTS/prepare_data.py" "$USERS"
  python3 "$SCRIPTS/gen_k6_users.py" "$DATA/users.csv" "$K6_DIR/data/users.js" "$USERS"
else
  echo "（SKIP_PREPARE=1，跳过）"
fi

echo
echo "############################################################"
echo "# 阶段 2：场景 A · 普通下单链路（非热点 SKU，${ORDER_VUS} VU × ${ORDER_ITER} 轮）"
echo "############################################################"
reset_order_data
rm -f "$RESULTS/k6-order.summary.json"
VUS="$ORDER_VUS" ITERATIONS="$ORDER_ITER" k6 run \
  --summary-export="$RESULTS/k6-order.summary.json" \
  --summary-trend-stats="avg,min,med,max,p(50),p(90),p(99)" \
  "$K6_DIR/order.js"

EXPECT_ORDERS="$(count_metric "$RESULTS/k6-order.summary.json" order_success)"
echo "工具侧成功建单：$EXPECT_ORDERS"
set +e
python3 "$SCRIPTS/verify_order_results.py" --expect-orders "$EXPECT_ORDERS"
ORDER_RC=$?
set -e
[[ $ORDER_RC -ne 0 ]] && echo "（场景 A 校验未通过，仍继续执行场景 B）"

echo
echo "############################################################"
echo "# 阶段 3：场景 B · 秒杀洪峰 × 普通下单 同轮并发"
echo "############################################################"
reset_order_data
reset_seckill_stock
rm -f "$RESULTS/k6-mixed.summary.json" "$RESULTS/k6-mixed.csv"
DURATION="$DURATION" NORMAL_RATE="$NORMAL_RATE" SECKILL_START="$SECKILL_START" \
SECKILL_VUS="$SECKILL_VUS" ITEM_ID="$SECKILL_ITEM" k6 run \
  --summary-export="$RESULTS/k6-mixed.summary.json" \
  --summary-trend-stats="avg,min,med,max,p(50),p(90),p(99)" \
  --out "csv=$RESULTS/k6-mixed.csv" \
  "$K6_DIR/order-vs-seckill.js"

set +e
python3 "$SCRIPTS/verify_results.py" --seckill-item "$SECKILL_ITEM" \
  --seckill-stock "$SECKILL_STOCK" --skip-cancel --skip-idempotent
MIX_RC=$?
set -e

echo
echo "############################################################"
echo "# 阶段 4：指标渲染"
echo "############################################################"
python3 "$SCRIPTS/k6_summary.py" "$RESULTS/k6-order.summary.json" \
  --label "场景 A · 普通下单链路（非热点 SKU，k6 ${ORDER_VUS} VU × ${ORDER_ITER} 轮）" \
  --markdown "$RESULTS/k6-metrics-order.md"

echo
python3 "$SCRIPTS/k6_summary.py" "$RESULTS/k6-mixed.summary.json" \
  --label "场景 B · 秒杀洪峰 × 普通下单（k6，${DURATION}）" \
  --markdown "$RESULTS/k6-metrics-mixed.md"

echo
python3 "$SCRIPTS/order_timeline.py" "$RESULTS/k6-mixed.csv" \
  --label "秒杀洪峰期间普通下单链路时间线" \
  --markdown "$RESULTS/k6-mixed-timeline.md"

echo
echo "全部完成。原始结果：$RESULTS/"
if [[ $ORDER_RC -ne 0 || $MIX_RC -ne 0 ]]; then exit 1; fi
exit 0
