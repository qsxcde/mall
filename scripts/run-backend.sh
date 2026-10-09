#!/usr/bin/env bash
# ============================================================
# 用 infra/.env 作为唯一配置来源启动后端（本地 java -jar 场景）。
#
# 为什么推荐用它而不是直接 java -jar：
#   1) 显式 source infra/.env（不依赖进程工作目录，也不依赖 Spring 对 .env 的导入判定）
#   2) 启动前回显生效的支付渠道 —— 「密钥填了但渠道没切」是本次联调踩过的坑
#
# docker compose 读的也是同一个 infra/.env，两边不会漂移。
#
# 用法：
#   bash scripts/run-backend.sh
#   SERVER_PORT=8081 bash scripts/run-backend.sh
#   bash scripts/run-backend.sh --mall.seckill.async.enabled=true
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${JAR:-$ROOT/backend/target/geek-mall-server-1.0.0.jar}"
ENV_FILE="${ENV_FILE:-$ROOT/infra/.env}"
PORT="${SERVER_PORT:-8080}"

if [[ ! -f "$JAR" ]]; then
  echo "找不到 $JAR" >&2
  echo "请先构建：cd backend && mvn -DskipTests package" >&2
  exit 1
fi

if [[ -f "$ENV_FILE" ]]; then
  # set -a：source 进来的变量自动 export 给子进程
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
  echo "[run-backend] 已加载 $ENV_FILE"
  echo "[run-backend] 支付渠道 mall.payment.channel=${MALL_PAYMENT_CHANNEL:-（未设置，默认 local）}"
else
  echo "[run-backend] 未找到 $ENV_FILE，走 application.yml 默认值（本地支付渠道）"
fi

# 显式传 --server.port：命令行参数优先级最高，避免被环境变量里的 SERVER__PORT 之类盖掉
# 切到 backend/ 再启动：logback 的日志路径是相对路径，这样日志稳定落在 backend/logs/（与 .gitignore 一致）
cd "$ROOT/backend"
exec java -jar "$JAR" "--server.port=$PORT" "$@"
