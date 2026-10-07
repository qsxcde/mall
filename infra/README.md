# infra - 本地基础设施编排

用 Docker Compose 一键拉起极客数码商城所需的中间件。

## 目录结构

```
infra/
├── docker-compose.yml
├── .env.example                      # 环境变量模板
├── prometheus/prometheus.yml         # 指标采集配置
├── loki/loki-config.yml              # 日志存储配置
└── promtail/promtail-config.yml      # 日志采集配置
```

## 快速开始

```bash
# 1.（可选）复制环境变量，按需修改
cp infra/.env.example infra/.env

# 2. 启动基础组件：MySQL + Redis + MinIO
docker compose -f infra/docker-compose.yml up -d

# 3.（可选）同时启动可观测性组件：Prometheus + Grafana + Loki + Promtail
docker compose -f infra/docker-compose.yml --profile observability up -d

# 查看状态 / 日志
docker compose -f infra/docker-compose.yml ps
docker compose -f infra/docker-compose.yml logs -f mysql

# 停止（保留数据）
docker compose -f infra/docker-compose.yml down

# 停止并清空数据卷
docker compose -f infra/docker-compose.yml down -v
```

## 组件与端口

| 组件 | 端口 | 说明 | Profile |
| --- | --- | --- | --- |
| MySQL | 3306 | 库 `geek_mall`，账号 `root/root` | 默认 |
| Redis | 6379 | appendonly 持久化 | 默认 |
| MinIO | 9000 / 9001 | API / 控制台（`minioadmin/minioadmin`） | 默认 |
| Prometheus | 9090 | 指标采集 | observability |
| Grafana | 3000 | 可视化（`admin/admin`） | observability |
| Loki | 3100 | 日志聚合 | observability |
| Promtail | 9080 | 采集 `backend/logs/*.log` | observability |

## 数据库说明

- 数据库由 MySQL 容器自动创建（`MYSQL_DATABASE=geek_mall`）。
- **表结构与演示数据不在这里初始化**，而是由后端启动时的 Flyway 自动执行
  `backend/src/main/resources/db/migration/V1__init.sql` 与 `V2__seed.sql`。
- 这样保证「代码与库结构」始终同源，避免手工导入 SQL 造成漂移。

## 可观测性使用

1. 先启动后端（`mvn spring-boot:run`），确保 `backend/logs/geek-mall.log` 已生成。
2. 启动 observability profile，等待 Prometheus 抓取到 `geek-mall-server` 目标（State = UP）。
3. **Grafana 接入数据源**：
   - Prometheus：`http://prometheus:9090`
   - Loki：`http://loki:3100`
4. 常用查询：
   - 接口 QPS：`rate(http_server_requests_seconds_count{application="geek-mall-server"}[1m])`
   - P99 延迟：`histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))`
   - JVM 堆使用：`jvm_memory_used_bytes{area="heap"}`
   - 日志按链路聚合（Loki）：`{job="geek-mall-server"} |= "traceId"`

> Promtail 通过 `../backend/logs` 挂载读取宿主机日志；若后端跑在容器中，请改用共享卷。

## 后端容器化（可选）

```bash
cd backend
mvn -DskipTests clean package
docker build -t geek-mall-server:1.0.0 .
docker run -d --name geek-mall-server \
  --network geek-mall_geek-mall-net \
  -e MYSQL_HOST=geek-mall-mysql -e REDIS_HOST=geek-mall-redis \
  -p 8080:8080 geek-mall-server:1.0.0
```

## 注意事项

- **MinIO 镜像拉取失败时**（部分网络环境无法访问 Docker Hub 的 `minio/minio`）：
  不影响后端启动，只是图片上传会返回明确提示。此时可让后端改用本地磁盘存储：
  ```bash
  java -jar backend/target/geek-mall-server-1.0.0.jar --mall.storage.type=local
  # 或设置环境变量 STORAGE_TYPE=local
  ```
  文件会写入 `backend/uploads/`，并通过 `/uploads/**` 直接访问。
- 生产环境请务必修改 `MYSQL_ROOT_PASSWORD`、`MINIO_ROOT_PASSWORD`、`GRAFANA_PASSWORD`
  以及后端的 `MALL_JWT_SECRET`。
- 生产不建议使用 `mysql:8.0` 的 `latest` 标签，应固定小版本号。
- 本编排仅面向本地开发，未做高可用、备份与资源限制配置。
