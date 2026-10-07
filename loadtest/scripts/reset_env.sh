#!/usr/bin/env bash
# ============================================================
# 压测环境重置：清空历史压测数据 → 重置秒杀/商品库存 → 重启后端
#
# 为什么要重启后端：
#   1) 清空 JwtAuthenticationFilter 的 Caffeine 本地令牌缓存；
#   2) SeckillStockWarmUp 会用 DB 库存重新预热 Redis 秒杀计数；
#   3) 保证每次压测从同一基线开始，结果可复现。
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
JAR="$BACKEND_DIR/target/geek-mall-server-1.0.0.jar"
LOG_FILE="${GM_LOG:-/tmp/geekmall-app.log}"

# 秒杀商品基线（与 docs/第一次压力测试.md 的说明保持一致）
ITEM1_STOCK=200      # 压力测试用：mkt_seckill_item.id=1 → pms_product.id=1
ITEM1_PRODUCT=1
ITEM2_STOCK=50       # 并发正确性测试用：mkt_seckill_item.id=2 → pms_product.id=6
ITEM2_PRODUCT=6

mysql_exec() {
  docker exec -i geek-mall-mysql mysql -uroot -proot -N geek_mall -e "$1"
}

echo "[1/5] 清理历史压测数据（139* 压测用户 + 幂等/取消用例订单）..."
mysql_exec "
DELETE l FROM oms_order_status_log l
  JOIN oms_order o ON o.order_no = l.order_no
  JOIN sys_user u ON u.id = o.user_id
 WHERE u.phone LIKE '139%';
DELETE i FROM oms_order_item i
  JOIN oms_order o ON o.order_no = i.order_no
  JOIN sys_user u ON u.id = o.user_id
 WHERE u.phone LIKE '139%';
DELETE r FROM inventory_rollback_log r
  JOIN oms_order o ON o.order_no = r.order_no
  JOIN sys_user u ON u.id = o.user_id
 WHERE u.phone LIKE '139%';
DELETE o FROM oms_order o JOIN sys_user u ON u.id = o.user_id WHERE u.phone LIKE '139%';
DELETE c FROM oms_cart_item c JOIN sys_user u ON u.id = c.user_id WHERE u.phone LIKE '139%';
-- 演示账号上的幂等/取消用例订单（request_id 前缀固定），否则重复跑用例会撞唯一索引
DELETE l FROM oms_order_status_log l JOIN oms_order o ON o.order_no = l.order_no
 WHERE o.request_id LIKE 'IDEM-%' OR o.request_id LIKE 'CANCEL-PREP-%';
DELETE i FROM oms_order_item i JOIN oms_order o ON o.order_no = i.order_no
 WHERE o.request_id LIKE 'IDEM-%' OR o.request_id LIKE 'CANCEL-PREP-%';
DELETE r FROM inventory_rollback_log r JOIN oms_order o ON o.order_no = r.order_no
 WHERE o.request_id LIKE 'IDEM-%' OR o.request_id LIKE 'CANCEL-PREP-%';
DELETE FROM oms_order WHERE request_id LIKE 'IDEM-%' OR request_id LIKE 'CANCEL-PREP-%';
" >/dev/null

echo "[2/5] 重置秒杀活动库存与商品库存..."
mysql_exec "
UPDATE mkt_seckill_item SET stock=$ITEM1_STOCK, total=$ITEM1_STOCK, sold=0, not_start=0 WHERE id=1;
UPDATE mkt_seckill_item SET stock=$ITEM2_STOCK, total=$ITEM2_STOCK, sold=0, not_start=0 WHERE id=2;
UPDATE pms_product SET stock=100000, sales=0 WHERE id=$ITEM1_PRODUCT;
UPDATE pms_product SET stock=100000, sales=0 WHERE id=$ITEM2_PRODUCT;
" >/dev/null

echo "[3/5] 清理 Redis 状态（秒杀计数 / 一人一单 / 下单幂等键）..."
docker exec geek-mall-redis sh -c "redis-cli --scan --pattern 'mall:seckill:*' | xargs -r redis-cli del" >/dev/null
# 关键：幂等键的 TTL 比订单数据长，若只删库不删键，下一次下单会「命中幂等」
# 返回一个数据库里并不存在的悬空订单号。必须一起清掉。
docker exec geek-mall-redis sh -c "redis-cli --scan --pattern 'mall:order:idempotent:*' | xargs -r redis-cli del" >/dev/null

echo "[4/5] 重启后端服务..."
pkill -f geek-mall-server-1.0.0.jar 2>/dev/null || true
sleep 3
cd "$BACKEND_DIR"
# 压测时关闭 MyBatis SQL 与 debug 日志：
# dev 默认逐条打印 SQL，会把磁盘 I/O 变成瓶颈，压出来的数字无法反映业务真实吞吐。
STORAGE_TYPE=local nohup java -jar "$JAR" --server.port=8080 \
  --logging.level.com.geekmall=info \
  --mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl \
  > "$LOG_FILE" 2>&1 &
echo "      pid=$!  日志：$LOG_FILE"

echo "[5/5] 等待健康检查通过..."
for i in $(seq 1 40); do
  if curl -sf -m 2 http://localhost:8080/actuator/health >/dev/null 2>&1; then
    echo "      服务已就绪（${i}s）"
    exit 0
  fi
  sleep 1
done
echo "      启动超时，请检查 $LOG_FILE" >&2
exit 1
