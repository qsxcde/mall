#!/usr/bin/env bash
# ============================================================
# 一键执行：环境重置 → 压测数据准备 → 并发正确性测试 → 压力测试 → 指标统计
#
# 用法：
#   bash loadtest/scripts/run_tests.sh            # 全流程
#   SKIP_RESET=1 bash ... run_tests.sh            # 跳过重置，直接压测
#   USERS=1000 STEADY_THREADS=200 STEADY_LOOPS=100 ...   # 覆盖压测参数
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPTS="$ROOT_DIR/loadtest/scripts"
JMETER_DIR="$ROOT_DIR/loadtest/jmeter"
RESULTS="$ROOT_DIR/loadtest/results"
DATA="$ROOT_DIR/loadtest/data"
JMETER_IMAGE="${JMETER_IMAGE:-geek-mall-jmeter:5.6.3}"

USERS="${USERS:-1000}"
# 场景一：持续压力（贴近真实秒杀洪峰），用于统计 QPS/TPS/P50/P90/P99
STEADY_THREADS="${STEADY_THREADS:-200}"
STEADY_LOOPS="${STEADY_LOOPS:-100}"
STEADY_RAMP="${STEADY_RAMP:-5}"
# 场景二：瞬时峰值冲击（一次性齐发），用于验证限流/舱壁的快速失败
BURST_THREADS="${BURST_THREADS:-1000}"
BURST_LOOPS="${BURST_LOOPS:-1}"
BURST_RAMP="${BURST_RAMP:-1}"
SECKILL_ITEM="${SECKILL_ITEM:-1}"
SECKILL_STOCK="${SECKILL_STOCK:-200}"
CONCURRENCY_ITEM="${CONCURRENCY_ITEM:-2}"
CONCURRENCY_STOCK="${CONCURRENCY_STOCK:-50}"
# 读链路
READ_THREADS="${READ_THREADS:-200}"
READ_LOOPS="${READ_LOOPS:-100}"
READ_RAMP="${READ_RAMP:-5}"

mkdir -p "$RESULTS" "$DATA"

# 确保 JMeter 镜像存在（本地构建，见 loadtest/docker/Dockerfile）
if ! docker image inspect "$JMETER_IMAGE" >/dev/null 2>&1; then
  echo "构建 JMeter 镜像 $JMETER_IMAGE ..."
  docker build -t "$JMETER_IMAGE" "$ROOT_DIR/loadtest/docker"
fi

# 容器内路径 /loadtest/xxx -> 宿主机路径 $ROOT_DIR/loadtest/xxx
host_path() { echo "$ROOT_DIR/loadtest${1#/loadtest}"; }

run_jmeter() {
  local jmx="$1" jtl="$2"; shift 2
  local jtl_host; jtl_host="$(host_path "$jtl")"
  mkdir -p "$(dirname "$jtl_host")"
  rm -f "$jtl_host" "${jtl_host%.jtl}.log"
  local rc=0
  docker run --rm \
    --add-host host.docker.internal:host-gateway \
    -v "$ROOT_DIR/loadtest":/loadtest \
    "$JMETER_IMAGE" \
    -n -t "$jmx" -l "$jtl" -j "${jtl%.jtl}.log" "$@" || rc=$?
  if [[ ! -s "$jtl_host" ]]; then
    echo "JMeter 未产出结果文件（exit=${rc}），见 ${jtl_host%.jtl}.log" >&2
    exit 1
  fi
  echo "  JMeter 退出码=${rc}，结果文件 $jtl_host"
}

# 校验结果不中断流程（失败也要把指标报告产出），最终以退出码体现
VERIFY_RC=0
run_verify() {
  set +e
  python3 "$SCRIPTS/verify_results.py" --seckill-item "$1" --seckill-stock "$2"
  local rc=$?
  set -e
  [[ $rc -ne 0 ]] && VERIFY_RC=$rc
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
echo "# 阶段 1：准备压测用户与令牌（$USERS 个）"
echo "############################################################"
if [[ "${SKIP_PREPARE:-0}" != "1" ]]; then
  python3 "$SCRIPTS/prepare_data.py" "$USERS"
else
  echo "（SKIP_PREPARE=1，跳过，复用 data/users.csv）"
fi

echo
echo "固定账号（演示用户）令牌用于幂等/取消用例..."
FIXED_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"13800000000","password":"123456"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
echo "  令牌长度：${#FIXED_TOKEN}"

echo
echo "############################################################"
echo "# 阶段 2：并发正确性测试（幂等 / 取消 / 秒杀防超卖）"
echo "############################################################"
if [[ "${SKIP_CONCURRENCY:-0}" != "1" ]]; then
run_jmeter /loadtest/jmeter/concurrency-test.jmx /loadtest/results/concurrency.jtl \
  -Jhost=host.docker.internal -Jport=8080 \
  -JfixedToken="$FIXED_TOKEN" -JfixedAddressId=1 -JidemRequestId=IDEM-FIXED \
  -JseckillItemId="$CONCURRENCY_ITEM" -Jusers=/loadtest/data/users.csv

echo
set +e
python3 "$SCRIPTS/verify_results.py" \
  --seckill-item "$CONCURRENCY_ITEM" --seckill-stock "$CONCURRENCY_STOCK"
CONC_RC=$?
set -e
if [[ $CONC_RC -ne 0 ]]; then
  echo "并发正确性测试未全部通过，终止后续压测" >&2
  exit $CONC_RC
fi

echo
echo "并发正确性测试全部通过 ✅  进入压力测试"
echo
else
  echo "（SKIP_CONCURRENCY=1，跳过）"
fi

echo "############################################################"
echo "# 阶段 3：重置压力测试用秒杀商品（item=${SECKILL_ITEM}, stock=${SECKILL_STOCK}）"
echo "############################################################"

# 重置秒杀活动库存：DB + Redis（直接以 DB 值覆盖 Redis，无需重启应用）
# 同时清掉上一轮压测产生的订单，保证每个场景的「订单数 == 发放库存」可独立核对
reset_seckill_stock() {
  docker exec -i geek-mall-mysql mysql -uroot -proot -N geek_mall -e "
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

# 校验结果不中断流程（失败也要把指标报告产出），最终以退出码体现
reset_seckill_stock
PRODUCT_ID=$(docker exec -i geek-mall-mysql mysql -uroot -proot -N geek_mall -e \
  "SELECT product_id FROM mkt_seckill_item WHERE id=$SECKILL_ITEM;" | tr -d '\r')
echo "  product_id=$PRODUCT_ID"

echo
echo "############################################################"
echo "# 阶段 4A：持续压力测试（${STEADY_THREADS} 并发 × ${STEADY_LOOPS} 轮）"
echo "############################################################"
run_jmeter /loadtest/jmeter/seckill-stress.jmx /loadtest/results/seckill-stress.jtl \
  -Jhost=host.docker.internal -Jport=8080 \
  -JitemId="$SECKILL_ITEM" -Jthreads="$STEADY_THREADS" -Jloops="$STEADY_LOOPS" -Jramp="$STEADY_RAMP" \
  -Jusers=/loadtest/data/users.csv

echo
run_verify "$SECKILL_ITEM" "$SECKILL_STOCK"

echo
echo "############################################################"
echo "# 阶段 4B：瞬时峰值冲击（${BURST_THREADS} 并发一次性齐发）"
echo "############################################################"
reset_seckill_stock
run_jmeter /loadtest/jmeter/seckill-stress.jmx /loadtest/results/seckill-burst.jtl \
  -Jhost=host.docker.internal -Jport=8080 \
  -JitemId="$SECKILL_ITEM" -Jthreads="$BURST_THREADS" -Jloops="$BURST_LOOPS" -Jramp="$BURST_RAMP" \
  -Jusers=/loadtest/data/users.csv

echo
echo "############################################################"
echo "# 阶段 4C：读链路压力测试（首页 + 商品详情，验证缓存收益）"
echo "############################################################"
# 先预热缓存，避免把「首次穿透 + 缓存重建」计入稳态指标
curl -s -m 5 http://localhost:8080/api/v1/home/floors >/dev/null || true
for pid in $(seq 1 13); do curl -s -m 5 "http://localhost:8080/api/v1/products/${pid}" >/dev/null || true; done
run_jmeter /loadtest/jmeter/read-stress.jmx /loadtest/results/read-stress.jtl \
  -Jhost=host.docker.internal -Jport=8080 \
  -Jthreads="$READ_THREADS" -Jloops="$READ_LOOPS" -Jramp="$READ_RAMP"

echo
echo "############################################################"
echo "# 阶段 5：结果核验 + 指标统计"
echo "############################################################"
run_verify "$SECKILL_ITEM" "$SECKILL_STOCK"

echo
python3 "$SCRIPTS/report_metrics.py" "$RESULTS/seckill-stress.jtl" \
  --label "场景一 · 持续压力（${STEADY_THREADS} 并发 × ${STEADY_LOOPS} 轮）" \
  --markdown "$RESULTS/metrics-steady.md"

echo
python3 "$SCRIPTS/report_metrics.py" "$RESULTS/seckill-burst.jtl" \
  --label "场景二 · 瞬时峰值冲击（${BURST_THREADS} 并发一次性齐发）" \
  --markdown "$RESULTS/metrics-burst.md"

echo
python3 "$SCRIPTS/report_metrics.py" "$RESULTS/read-stress.jtl" \
  --label "读链路 · 首页 + 商品详情（${READ_THREADS} 并发 × ${READ_LOOPS} 轮）" \
  --markdown "$RESULTS/metrics-read.md"

echo
echo "全部完成。原始结果：$RESULTS/"
exit $VERIFY_RC
