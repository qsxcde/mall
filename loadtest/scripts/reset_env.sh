#!/usr/bin/env bash
# ============================================================
# 压测环境重置：清空历史压测数据 → 重置秒杀/商品库存 → (可配)重启后端
#
# 为什么默认要重启后端：
#   1) 清空 JwtAuthenticationFilter 的 Caffeine 本地令牌缓存；
#   2) SeckillStockWarmUp 会用 DB 库存重新预热 Redis 秒杀计数；
#   3) 保证每次压测从同一基线开始，结果可复现。
#
# 可覆盖参数（环境变量）：
#   PORT=8080                 后端端口
#   MANAGE_BACKEND=1          1=停旧后端并起新后端；0=只清数据，不动后端
#   RATE_LIMIT_ENABLED=false  压测口径默认关闭限流（否则批量登录/读链路会 429）
#   SECKILL_ASYNC_ENABLED=false  秒杀削峰队列开关：true=用队列异步落库，false=同步落库
#                                （同一台脚本跑两遍即可做「用/不用削峰」A/B 对比）
#   CONSUMER_THREADS=4        削峰消费者线程数 —— 做 MQ 对比时**必须显式指定**：
#                             非MQ 侧是 seckillExecutor 的 64 线程并发落库，若异步侧只给 4 个
#                             消费线程，比的是「配置」而不是「架构」，即文档里的缺陷 D1。
#                             扫描建议：4 / 8 / 16 / 32 / 64（对齐同步侧的有效并行度）
#   SECKILL_PROFILE=single    秒杀场景档位：single=现状（item1=200、item2=50 单热点）；
#                             multi12=12 个 SKU（item101..112 → product 2..13），破解 D3
#   SECKILL_STOCK=            覆盖档位自带库存（不传则 single=200/50、multi12=200）
#   SECKILL_ITEMS=            完全自定义：itemId:productId:stock 逗号分隔（优先于上面两项）
#   LOG_LEVEL=info            业务日志级别
#   STORAGE_TYPE=local        文件存储实现（minio / local）
#   SPRING_PROFILES=          可选，如 dev
#   JAR=<path>                默认 backend/target/geek-mall-server-1.0.0.jar
#   LOG_FILE=/tmp/geekmall-app.log
#   HEALTH_TIMEOUT=40         健康检查最长等待秒数
#   FORCE_KILL_AFTER=10       优雅停止等待秒数，超时强杀
#
# 停服策略（不再只靠 jar 名匹配）：
#   1) 优先按「监听 PORT 的 PID」精确停服 —— 能覆盖 mvn / IDE 起的后端；
#   2) 再兜底按 jar 名匹配，清理其他实例；
#   3) 启动前校验端口已释放，启动后校验新进程存活且真正接管了端口（避免假成功）。
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
PORT="${PORT:-8080}"
MANAGE_BACKEND="${MANAGE_BACKEND:-1}"
JAR="${JAR:-$BACKEND_DIR/target/geek-mall-server-1.0.0.jar}"
LOG_FILE="${GM_LOG:-/tmp/geekmall-app.log}"
LOG_LEVEL="${LOG_LEVEL:-info}"
STORAGE_TYPE="${STORAGE_TYPE:-local}"
RATE_LIMIT_ENABLED="${RATE_LIMIT_ENABLED:-false}"
SECKILL_ASYNC_ENABLED="${SECKILL_ASYNC_ENABLED:-false}"
CONSUMER_THREADS="${CONSUMER_THREADS:-4}"
SECKILL_PROFILE="${SECKILL_PROFILE:-single}"
SECKILL_STOCK="${SECKILL_STOCK:-}"
SECKILL_ITEMS="${SECKILL_ITEMS:-}"
SPRING_PROFILES="${SPRING_PROFILES:-}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-40}"
FORCE_KILL_AFTER="${FORCE_KILL_AFTER:-10}"

# 监听指定端口的 PID（可能多个，一行一个）
listen_pids() {
  lsof -ti "tcp:$PORT" -sTCP:LISTEN 2>/dev/null || true
}

# 等待 PID 退出；返回 0=已退出，1=超时仍在
wait_pid_gone() {
  local pid="$1" deadline=$((SECONDS + FORCE_KILL_AFTER))
  while kill -0 "$pid" 2>/dev/null; do
    if ((SECONDS >= deadline)); then
      return 1
    fi
    sleep 0.5
  done
  return 0
}

# 等待端口释放
wait_port_free() {
  for _ in $(seq 1 20); do
    if [[ -z "$(listen_pids)" ]]; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

# 精确停止后端：先按端口找 PID，再兜底按 jar 名清理
stop_backend() {
  local pids
  pids="$(listen_pids)"
  if [[ -n "$pids" ]]; then
    echo "      端口 $PORT 被占用，按 PID 停止：$(echo "$pids" | tr '\n' ' ')"
    local pid
    for pid in $pids; do
      kill "$pid" 2>/dev/null || true
    done
    for pid in $pids; do
      if ! wait_pid_gone "$pid"; then
        echo "      PID $pid 未在 ${FORCE_KILL_AFTER}s 内退出，强制 kill -9"
        kill -9 "$pid" 2>/dev/null || true
      fi
    done
  else
    echo "      端口 $PORT 无监听进程"
  fi

  # 兜底：清理按 jar 名匹配的其他实例（别的端口 / 未监听成功的僵尸进程）
  pkill -f "$(basename "$JAR")" 2>/dev/null || true
}

# 秒杀库存基线不再硬编码在脚本里，改由 prepare_seckill_scenario.py 按 profile 落库：
#   single（默认）→ item1=200（压力测试主场景）、item2=50（并发正确性场景）
#   multi12        → 12 个 SKU 各 SECKILL_STOCK，用于破解「单热点 SKU 掩盖收益」
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

echo "[2/5] 准备秒杀场景数据（profile=$SECKILL_PROFILE${SECKILL_STOCK:+, stock=$SECKILL_STOCK}${SECKILL_ITEMS:+, items=$SECKILL_ITEMS}）..."
SCENARIO_ARGS=(--profile "$SECKILL_PROFILE")
if [[ -n "$SECKILL_STOCK" ]]; then
  SCENARIO_ARGS+=(--stock "$SECKILL_STOCK")
fi
if [[ -n "$SECKILL_ITEMS" ]]; then
  SCENARIO_ARGS+=(--items "$SECKILL_ITEMS")
fi
python3 "$SCRIPT_DIR/prepare_seckill_scenario.py" "${SCENARIO_ARGS[@]}"

echo "[3/5] 清理 Redis 状态（秒杀计数 / 一人一单 / 下单幂等键）..."
docker exec geek-mall-redis sh -c "redis-cli --scan --pattern 'mall:seckill:*' | xargs -r redis-cli del" >/dev/null
# 关键：幂等键的 TTL 比订单数据长，若只删库不删键，下一次下单会「命中幂等」
# 返回一个数据库里并不存在的悬空订单号。必须一起清掉。
docker exec geek-mall-redis sh -c "redis-cli --scan --pattern 'mall:order:idempotent:*' | xargs -r redis-cli del" >/dev/null

if [[ "$MANAGE_BACKEND" != "1" ]]; then
  echo "[4/5] MANAGE_BACKEND=0：仅重置数据，不重启后端"
  echo "      提醒：请自行重启后端，否则 Caffeine 令牌缓存不会清、Redis 秒杀计数不会预热"
  echo "[5/5] 跳过健康检查"
  echo "完成（未接管后端）。"
  exit 0
fi

echo "[4/5] 停止旧后端并启动新后端..."

if [[ ! -f "$JAR" ]]; then
  echo "      找不到 $JAR" >&2
  echo "      请先打包：cd backend && mvn -DskipTests package" >&2
  exit 1
fi

stop_backend

# 启动前必须确认端口已释放，否则新进程会因端口占用启动失败（旧进程仍在时健康检查还会假通过）
if ! wait_port_free; then
  echo "      端口 $PORT 仍被占用：$(listen_pids | tr '\n' ' ')，停止失败" >&2
  exit 1
fi

cd "$BACKEND_DIR"

# 压测时关闭 MyBatis SQL 与 debug 日志：
# dev 默认逐条打印 SQL，会把磁盘 I/O 变成瓶颈，压出来的数字无法反映业务真实吞吐。
# 限流开关做成可配：压测默认关闭，专门验证限流时用 RATE_LIMIT_ENABLED=true 打开。
ARGS=(
  "--server.port=$PORT"
  "--logging.level.com.geekmall=$LOG_LEVEL"
  "--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
  "--mall.rate-limit.enabled=$RATE_LIMIT_ENABLED"
  "--mall.seckill.async.enabled=$SECKILL_ASYNC_ENABLED"
  # 消费线程数显式下发：做 MQ 对比时用来对齐两组的有效并行度（缺陷 D1）
  "--mall.seckill.async.consumer-threads=$CONSUMER_THREADS"
)
if [[ -n "$SPRING_PROFILES" ]]; then
  ARGS+=("--spring.profiles.active=$SPRING_PROFILES")
fi

STORAGE_TYPE="$STORAGE_TYPE" nohup java -jar "$JAR" "${ARGS[@]}" > "$LOG_FILE" 2>&1 &
NEW_PID=$!
echo "      pid=$NEW_PID  端口=$PORT  限流=$RATE_LIMIT_ENABLED  秒杀削峰=$SECKILL_ASYNC_ENABLED  消费线程=$CONSUMER_THREADS  日志：$LOG_FILE"

echo "[5/5] 等待健康检查通过（并确认新进程真的接管了端口）..."
for i in $(seq 1 "$HEALTH_TIMEOUT"); do
  # 新进程已死就没必要再等，直接看日志定位原因
  if ! kill -0 "$NEW_PID" 2>/dev/null; then
    echo "      新进程已退出（pid=$NEW_PID），最近日志：" >&2
    tail -n 40 "$LOG_FILE" >&2 || true
    exit 1
  fi
  if curl -sf -m 2 "http://localhost:$PORT/actuator/health" >/dev/null 2>&1; then
    OWNER="$(listen_pids | head -n1)"
    if [[ "$OWNER" != "$NEW_PID" ]]; then
      echo "      健康检查通过，但端口 $PORT 的监听者($OWNER)不是新进程($NEW_PID)，判定为假成功" >&2
      tail -n 40 "$LOG_FILE" >&2 || true
      exit 1
    fi
    echo "      服务已就绪（${i}s），pid=$NEW_PID 已接管端口 $PORT"
    exit 0
  fi
  sleep 1
done
echo "      启动超时（${HEALTH_TIMEOUT}s），请检查 $LOG_FILE" >&2
tail -n 40 "$LOG_FILE" >&2 || true
exit 1
