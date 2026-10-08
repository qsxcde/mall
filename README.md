# Geek Mall · 极客数码商城

一个三端齐备的数码 3C 电商项目：**买家端 + 商家端 + 后端**。

功能完整度已经做到「打开就能演示」——但它真正的重点在后端的高并发治理：限流、缓存三防、秒杀削峰、幂等与状态机，以及一套可重复运行的自动化验证（含真实容器集成测试与 k6 压测）。

> 这是一个**以学习和验证为目的**的项目，不是生产系统。所有性能数字都来自本机实测，报告里标注了局限，没有做任何外推。

---

## 一、项目描述

### 1.1 包含什么

| 模块 | 目录 | 说明 |
| --- | --- | --- |
| 买家端 | `frontend/` | Vue 3 + Vite，数码商城前台（浏览 → 下单 → 支付 → 评价 → 售后全链路） |
| 商家端 | `frontendMerchant/` | Vue 3 + Vite，商家中心（商品、订单、售后、资金） |
| 后端 | `backend/` | Spring Boot 3.3.5 + JDK 21，118 个 REST 接口，统一前缀 `/api/v1` |
| 基础设施 | `infra/` | Docker Compose 编排 MySQL / Redis / MinIO，可选开可观测性栈 |
| 压测 | `loadtest/` | k6 为主、JMeter 为辅，含数据准备与结果校验脚本 |
| 文档 | `docs/` | 14 份分析报告（现状、治理清单、限流策略、压测对比等） |

### 1.2 技术栈

**后端**

| 维度 | 选型 |
| --- | --- |
| 运行时 | JDK 21 |
| 框架 | Spring Boot 3.3.5 |
| 安全 | Spring Security 6 + JWT 无状态（Redis 保存会话，支持登出踢下线） |
| 持久层 | MyBatis-Plus + MySQL 8 + Flyway（10 个版本化迁移，代码与库结构同源） |
| 缓存 | Redis 7（分级 TTL + 本地 Caffeine L1） |
| 分布式 | Redis Stream（秒杀削峰队列）、ShedLock（定时任务互斥） |
| 可观测性 | Actuator + Micrometer/Prometheus + 结构化 JSON 日志 + TraceId 全链路（跨线程 / 跨队列） |
| 接口文档 | springdoc-openapi（Swagger UI） |
| 存储 | MinIO 对象存储，可切本地磁盘 |
| 测试 | JUnit 5 + Testcontainers（真实 MySQL 8 / Redis 7 容器） |

**前端**

| 维度 | 选型 |
| --- | --- |
| 框架 | Vue 3（Composition API） |
| 构建 | Vite |
| 状态 | Pinia |
| 路由 | Vue Router（业务页面懒加载分包） |
| UI | Element Plus |
| 请求 | axios（统一拦截 `code != 0`、401 跳登录） |

### 1.3 业务域

后端按业务域分包（`backend/src/main/java/com/geekmall/modules/`），共 12 个域，每个域内部统一 `controller → service(+impl) → mapper`，配合 `entity / dto / vo / converter`：

`auth`（登录注册验证码）· `user`（资料/地址/签到）· `product`（分类/列表/搜索/详情/首页）· `cart`（购物车）· `trade`（结算/下单/状态机/物流）· `payment`（支付单/回调）· `marketing`（优惠券/秒杀/积分）· `review`（评价）· `aftersale`（售后）· `message`（消息中心）· `content`（CMS/上传）· `merchant`（商家中心）

### 1.4 高并发治理 —— 本项目的核心

这是项目投入最多的地方，也是它与普通 CRUD 电商的区别所在。

| 能力 | 落地方式 |
| --- | --- |
| **全站限流** | **118/118 端点**均有显式规则，规则集中于单一策略表；默认走本地滑动窗口（零网络往返），仅少数「限流是唯一防线」的接口走 Redis；含并发度控制与 `FAIL_OPEN`/`FAIL_CLOSED` 降级 |
| **缓存三防** | 穿透：布隆过滤器 + 空值短 TTL；击穿：Redis 互斥锁跨实例重建；雪崩：TTL 抖动 + 本地 L1 白名单 + 熔断降级 |
| **秒杀削峰** | Redis Stream 把落库从请求线程移到消费者，抢购接口改为「受理 + 结果轮询」 |
| **幂等** | 下单幂等键 + 唯一索引、支付回调幂等、优惠券领取双幂等、提现条件扣减 |
| **并发正确性** | 订单状态机 CAS、商品乐观锁、库存回退幂等日志、按 ID 排序加锁防死锁 |
| **分布式锁** | ShedLock 保证多实例下定时任务只跑一次 |

**几组实测数字**（详见各报告）：

- 秒杀削峰：用户侧 **P99 由 1880ms 降至 320ms（-83%）**，落库吞吐不变 —— 削峰只摊平脉冲，不提升吞吐
- 秒杀正确性：6 用户并发抢 3 件库存，**恰好 3 人成功、不超卖、一人一单**
- 并发取消：8 线程同时取消同一订单，**只产生 1 条状态日志**（即库存只回退一次）
- 缓存击穿：8 线程并发冷启动重建，**恰好回源 1 次**（读指标差值断言，而非靠观察）

### 1.5 质量与验证

- **44 个测试类**（+1 基类，共 555 个用例），`mvn test` 一键运行全绿；集成测试用 Testcontainers 起**真实 MySQL 8 + Redis 7**，跑真实 Flyway 迁移与真实过滤器链
- 并发正确性从「压测时观察」升级为「自动化断言」——改坏了会立刻红灯
- k6 压测体系可复现：数据准备脚本 + 结果校验脚本 + 指标渲染
- 已做过一轮死代码清理（基于全库引用扫描 + 逐项人工复核）

### 1.6 目录结构

```
.
├── backend/            # Spring Boot 后端（含 Dockerfile）
│   └── src/main/java/com/geekmall/
│       ├── common/     # 通用层：cache / ratelimit / resilience / event / result / exception ...
│       ├── config/     # 配置类
│       ├── security/   # JWT 过滤器、双端安全上下文
│       └── modules/    # 12 个业务域
├── frontend/           # 买家端（Vite: 5173）
├── frontendMerchant/   # 商家端（Vite: 5174）
├── infra/              # docker-compose：MySQL / Redis / MinIO (+ 可观测性 profile)
├── loadtest/           # k6 / JMeter 脚本、数据准备、结果
└── docs/               # 分析报告
```

---

## 二、项目启动

### 2.1 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21 | 后端编译运行 |
| Maven | 3.9+ | 构建；**仓库未提供 `mvnw`，需自行安装 Maven** |
| Node.js | 20 / 22（18 亦可） | 前端构建；`vite@6` 要求 Node `^18` / `^20` / `>=22`，Node 18 已 EOL，建议 20/22 |
| Docker | 任意较新版本 | 起中间件；**跑集成测试也需要它**；命令为 Compose V2 的 `docker compose`（非 `docker-compose`） |

> **换一台电脑时，除上面 4 个运行时外无需额外配置**：后端 `dev` 所需的 MySQL / Redis / MinIO 连接信息全部有默认值（见 `application-dev.yml`），`infra/.env` 是可选的（`docker-compose.yml` 均使用默认值兜底）。
>
> **首次启动需要联网**：`mvn` 拉取依赖、`npm install` 安装 `node_modules`（未入库）、Docker 拉取 `mysql:8.0` / `redis:7-alpine` / `minio` 镜像。
>
> **启动前确认端口空闲**：`8080`（后端）、`3307`（容器 MySQL）、`6379`（Redis）、`9000` / `9001`（MinIO）、`5173` / `5174`（前端）。容器 MySQL 默认映射到 **3307**（见 `infra/.env` 与 `application-dev.yml`），就是为了避开新机器上已装的本地 MySQL 抢占 `3306`。

### 2.2 启动基础设施

```bash
# 在仓库根目录执行：MySQL(3307) / Redis(6379) / MinIO(9000,9001)
docker compose -f infra/docker-compose.yml up -d

# （可选）同时启动 Prometheus / Grafana / Loki / Promtail
docker compose -f infra/docker-compose.yml --profile observability up -d

# 查看状态
docker compose -f infra/docker-compose.yml ps
```

**表结构与演示数据不在这里初始化**，而是由后端启动时的 Flyway 自动执行（`V1__init.sql` ~ `V10__digital_product_release_seed.sql`），保证代码与库结构始终同源。

> 若 MinIO 镜像因网络问题拉取失败：**不影响后端启动**，只是图片上传会提示失败。可让后端改用本地磁盘存储：`--mall.storage.type=local`，文件落在 `backend/uploads/`。

### 2.3 启动后端

```bash
cd backend
mvn spring-boot:run
```

默认激活 `dev` profile。同时也可以用打包方式运行：

```bash
mvn -DskipTests clean package
java -jar target/geek-mall-server-1.0.0.jar
```

启动后可访问：

| 地址 | 用途 |
| --- | --- |
| http://localhost:8080/api/v1 | 接口前缀 |
| http://localhost:8080/swagger-ui.html | 接口文档 |
| http://localhost:8080/actuator/health | 健康检查 |
| http://localhost:8080/actuator/prometheus | 指标 |

### 2.4 启动前端

两个前端可同时运行（端口已错开）：

```bash
# 买家端 → http://localhost:5173
cd frontend && npm install && npm run dev

# 商家端 → http://localhost:5174
cd frontendMerchant && npm install && npm run dev
```

两端都在 `vite.config.js` 中把 `/api` 代理到 `http://localhost:8080`，浏览器视角同源，无跨域问题。后端不在默认地址时可用环境变量覆盖：`VITE_API_TARGET=http://其他地址`。

### 2.5 演示账号

`dev` profile 下启动时自动创建（口令均为 `123456`）：

| 端 | 账号 | 密码 |
| --- | --- | --- |
| 买家端 | `13800000000` | `123456` |
| 商家端 | `merchant` | `123456` |

dev 环境还有两个便利开关（生产必须关闭）：短信验证码会在接口响应与日志中回显（`mall.sms.expose-code=true`），并注册了模拟发货等联调接口（`mall.mock.enabled=true`）。

### 2.6 运行测试

```bash
cd backend
mvn test
```

集成测试会用 Testcontainers 拉起真实的 MySQL 8 与 Redis 7 容器并执行真实 Flyway 迁移，**因此本机 Docker 需处于运行状态**，首次执行会拉取镜像。

### 2.7 运行压测（可选）

```bash
# 需要先安装 k6（brew install k6）
bash loadtest/scripts/run_k6_order.sh        # 下单链路 + 秒杀洪峰同轮并发
bash loadtest/scripts/run_tests.sh           # 全量：重置环境 → 造账号 → 并发正确性 → 压测 → 渲染指标
```

脚本会重置环境、批量创建压测账号并渲染指标到 `loadtest/results/`。

> 压测账号数据（`loadtest/data/users.csv` 及其派生的 `loadtest/k6/data/users.js`）**含 JWT，不入库**，已加入 `.gitignore`；首次压测前需先跑 `prepare_data.py`（`run_tests.sh` 已包含该步）现场换取令牌。

> ⚠️ 压测账号的 JWT **有效期只有 2 小时**。若拿到的是旧账号数据，所有请求会因 401 被统计成「业务拒绝」，看起来很像「已抢光」——遇到大批量失败先检查 token 是否过期。

### 2.8 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 接口返回 401 | 登录态失效或令牌过期；前端拦截器会自动跳登录页 |
| 接口返回 429 | 触发了限流（防刷接口为 `FAIL_CLOSED`）；规则见 `docs/spec/接口限流策略说明.md` |
| 提示连接不上 MySQL/Redis | 中间件未启动：`docker compose -f infra/docker-compose.yml up -d` |
| 宿主机 3307 / 6379 端口被占用 | 本机已装 MySQL / Redis 与容器冲突：停掉本地服务，或改 `.env` 里的 `MYSQL_PORT` / `REDIS_PORT` 并同步后端环境变量 |
| 启动报 `Access denied for user 'root'@'localhost'` | 后端连到了**本机**的 MySQL 而不是容器（3306 被本地实例占用）：确认容器端口为 3307，且 `application-dev.yml` 的默认端口未被改回 3306 |
| 前端 `npm run dev` 报 Node 版本不支持 | `vite@6` 要求 Node `^18` / `^20` / `>=22`，升级到 Node 20/22 |
| `mvn test` 失败且报容器相关错误 | Docker 未运行，或 Testcontainers 版本过低（项目已固定为 1.21.4） |
| 图片上传失败 | MinIO 未就绪；改用 `--mall.storage.type=local` |
| 压测大面积失败 | 大概率是压测账号 token 过期（见 2.7 提示） |

---

## 三、未来展望

### 3.1 已经打好的地基

功能层面已经闭环（浏览 → 下单 → 支付 → 评价 → 售后，外加商家端），治理层面限流、缓存三防、削峰、幂等均已落地并有自动化验证。**所以下一步的价值不在「再铺页面」，而在把已有能力做深、并被真实地考验。**

### 3.2 下一步：按 ROI 排序

**① 真正提升吞吐：批量落库与库存分桶**（优先级最高）

这不是拍脑袋的方向，而是压测**明确指出的瓶颈**：削峰把用户侧 P99 降了 83%，但落库吞吐仍是 30~40 单/秒，与同步模式相当——因为单热点 SKU 的瓶颈是**数据库行锁串行**，跟请求从哪来无关。

- **批量落库**：一个事务合并插入 N 单，把 N 次行锁竞争压成 1 次
- **库存分桶**：把热点商品的可售库存拆成 N 个独立桶，把一把行锁拆成 N 把

这两件事是「提吞吐」，与削峰（「降延迟、保护系统」）是正交的，缺了它们，秒杀场景的吞吐上限就被 DB 锁死。

**② 多实例部署验证**（优先级高）

目前所有并发验证都在单实例下完成，而项目里几处关键设计**只有多实例才考验得出来**：

- 缓存互斥锁是否真的跨实例只回源一次
- 本地 L1 在多实例下的一致性窗口（当前靠白名单收窄来限制影响）
- Redis Stream 消费者组在多实例下的分工与 rebalance
- ShedLock 定时任务互斥

**③ 商品域进阶**

多规格 SKU（当前是单商品模式）、Elasticsearch 搜索与联想（当前是数据库 LIKE）、服务筛选。这是业务完整度上最明显的空白。

**④ 可观测性从「有配置」走到「有看板」**

`infra/` 里已经编排好 Prometheus + Grafana + Loki + Promtail，但只有配置、没有看板与告警。缓存命中率、限流触发次数、消费队列滞后、熔断状态这些指标已在代码里埋好（Micrometer），把它们画出来才能在日常就发现问题，而不是等压测。

> **进展**：日志侧已先行落地——结构化 JSON、traceId 跨线程/跨队列贯通、慢 SQL 阈值化、重复异常收敛、敏感信息脱敏，
> 并有自动化断言守住（见 `docs/spec/日志规范.md`）。看板、告警与限流/秒杀队列指标仍未落地，详见 `docs/spec/可观测性现状分析与改进方案.md`。

### 3.3 更远的想法

- **业务补全**：真实支付渠道、真实物流轨迹、真实短信通道（当前均为 mock）
- **稳定性纵深**：缓存层已有独立熔断器，但下游调用（如 MinIO）还没有业务级熔断保护，可考虑统一到 Resilience4j；订单超时关闭目前靠轮询扫描，可改为延迟队列/时间轮
- **工程化**：✅ 已补 CI（`.github/workflows/ci.yml`：后端 `mvn test`（含 Testcontainers 集成测试）+ 双前端 `vite build`）；✅ 压测账号令牌（`users.csv` / 派生 `users.js`）与 `node_modules` / `dist` 已移出仓库并加入 `.gitignore`；待办：把 k6 压测纳入 CI 回归

### 3.4 一句话

**功能已经够用了，接下来值得投入的是「让已有的高并发设计真的被压出问题来」**——多实例部署、批量落库、看板告警，这三件事做完，这个项目就从「写了很多并发代码」变成「验证过并发设计」。

---

## 四、文档索引

`docs/` 下的报告已按主题归档到子目录（总入口见 `docs/文档分类索引.md`），都是对代码的实际审查与实测记录：

| 目录 | 文档 | 内容 |
| --- | --- | --- |
| `docs/planning/` | `练手项目聚焦范围与框架就绪度报告.md` | **总纲**：功能就绪度、能力矩阵、缺口与优先级 |
| `docs/planning/` | `功能点分析报告.md` | 前端功能点清单与缺口清单（需求基线） |
| `docs/planning/` | `后端脚手架搭建方案.md` | 后端分层与中间件选型设计 |
| `docs/concurrency/` | `高并发功能模块全景分析.md` | 全站高并发点普查 |
| `docs/concurrency/` | `高并发处理现状报告.md` | 高并发处理手段现状盘点 |
| `docs/concurrency/` | `高并发现状核查报告.md` | 对已有结论的独立复核 |
| `docs/concurrency/` | `高并发问题治理清单.md` | 待治理项清单与优先级 |
| `docs/concurrency/` | `秒杀异步化分析.md` | 削峰方案的设计取舍 |
| `docs/concurrency/` | `秒杀削峰压测对比报告.md` | 削峰的实测收益与代价（含完整复现步骤） |
| `docs/benchmark/` | `第一次压力测试.md` | 首轮 JMeter + k6 压测全记录 |
| `docs/benchmark/` | `测试补充项与MQ对比压测方案.md` | 测试缺口梳理与 MQ / 非MQ 对比压测方案 |
| `docs/spec/` | `接口限流策略说明.md` | 全站 118 个端点的限流规则明细与分层取舍 |
| `docs/spec/` | `日志规范.md` | 日志级别语义、字段字典、链路上下文三边界、脱敏规则与噪音治理 |
| `docs/spec/` | `可观测性现状分析与改进方案.md` | 观测能力盘点（日志/指标/追踪/告警）、盲区诊断、改进方向与分阶段落地计划 |
| `docs/governance/` | `死代码清理报告.md` | 死代码排查与复核结论 |

各子工程另有自己的 README：`backend/README.md`（接口清单与配置项）、`infra/README.md`（编排与可观测性用法）、`frontend/README.md`、`frontendMerchant/README.md`、`loadtest/B机压测操作指南.md`（跨机压测操作）。
