# 更新日志

本项目所有值得记录的变更都写在这里。

- 格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)
- 版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`
  - `主版本`：不兼容的接口/数据结构变更（需要迁移脚本配合）
  - `次版本`：向后兼容的功能新增
  - `修订号`：向后兼容的问题修复与文档修正
- 分类固定使用：新增 / 变更 / 修复 / 移除
- 发版时在 `[未发布]` 下方插入新版本小节，并打同名 tag（如 `v1.0.0`）

## [未发布]

### 新增

- **商品搜索抽象为三种可切换实现**（`mall.search.engine`）：`mysql-like`（默认）、
  `mysql-fulltext`（MySQL FULLTEXT + ngram）、`elasticsearch`（ES 8.15，只召回 id、内容回表，
  故障自动降级回 LIKE）。三实现语义一致性由 `ProductSearchContractIntegrationTest` 锁定
- 商品搜索三方案实测报告（100 万行）：`docs/benchmark/商品搜索三方案对比.md`、
  `docs/benchmark/商品搜索容量边界实测.md`
- **ArchUnit 架构约束**（`LayeredArchitectureTest`，7 条规则）：把分层纪律从口头约定变成可执行断言，
  含「业务域不得直连他域 mapper」与「域间循环依赖数 ≤ 4」
- 跨域耦合治理：新增 `MerchantOrderQueryService` / `MerchantReviewQueryService` /
  `MerchantAfterSaleQueryService` / `ProductStockService`，所有查询强制店铺隔离；
  `UserService` 补齐认证域所需的账户读写方法
- 造数与压测脚本：`gen_mock_products.py`（1 万~100 万条，自动刷新布隆过滤器）、
  `es_sync_products.py`（全量同步到 ES）、`bench_search.py`（三方案统一口径基准）

### 变更

- `MerchantAftersaleStatus` 从 `merchant/support` 下沉到 `common/enums`
  （买卖双方共享口径，消除 `aftersale → merchant` 反向依赖），`resolve` 改为接收原始字段
- `MerchantDashboardServiceImpl`（397 行）按页面拆分：概览保留原类，
  数据看板独立为 `MerchantAnalyticsAssembler`，共用日序列计算抽为 `MerchantDailySeries`
- `V14__product_search_fulltext.sql`：新增商品搜索全文索引（幂等迁移）
- Spring Boot 3.3.5 → 3.3.13；后端镜像改为非 root 运行
- 多实例 Compose 强制 `MALL_JWT_SECRET` / `REDIS_PASSWORD`，Redis 开启认证

### 修复

- 布隆过滤器预热在 80 万在售商品时 OOM 导致启动失败：`addAllRaw` 改为分批写入（每批 5000）
- ES 客户端未读取配置的连接/读取超时（默认 30s 会挂住 Tomcat 线程）：已生效为 2s/5s
- ES 搜索的排序语义：有关键词时 `_score` 曾抢占主排序键，导致 `sort=price_asc` 实际不按价格排
- 商家售后按 `orderNo` 查订单时漏加 `shop_id` 条件（越权可达）：已收敛到 `MerchantOrderQueryService`
- 支付资金一致性：生产禁止本地模拟渠道与未受开关保护的 `mock-pay`；支付成功未落账由对账任务修正；
  重复支付自动退款；退款结算事务化、失败退避重试，并按支付单串行化避免并发超退
- 安全：`X-Forwarded-For` 仅在来源命中可信代理时解析；验证码改 Lua 原子校验并增加失败锁定与每日上限；
  登录连续失败锁定；生产关闭 Swagger，CORS 收敛为具体来源白名单
- 秒杀：异步模式下 Redis 库存 key 缺失改为 fail-closed，避免按滞后的 DB 库存重建导致超卖
- 文件：上传增加魔数与像素校验；MinIO 仅公开展示型前缀，售后凭证私有 + 预签名；
  本地存储售后附件改走 HMAC 签名 URL
- 前端：结算 `requestId` 按会话复用，网络超时重试不再重复下单

### 文档

- `docs/` 新增 2 份搜索实测报告（累计 24 份），并更新分类索引
- `code-wiki` 同步结构变更：搜索引擎抽象、跨域查询服务、看板拆分、ArchUnit 规则，
  并修正测试数（629 → 668）、迁移数（13 → 14）、文档数（21 → 23）

## [1.0.0] - 2026-10-17

首个对外可引用的版本：三端齐备、高并发治理闭环、自动化验证完整。

### 新增

- 售后退款闭环：审批通过后真实调用渠道退款，支持部分退款（`refundForAfterSale`，幂等键为售后单号）；
  退货退款按「同意 → 买家寄回 → 商家确认收货」分节点释放资金
- 买家「登记已寄回商品」接口 `POST /api/v1/aftersales/{id}/returned`，打通退货退款链路
- 买家可见的售后状态细分为「处理中 / 待寄回 / 待收货 / 已完成 / 已取消 / 已拒绝」，并透出拒绝理由
- `ProdConfigGuard` 生产配置自检：`prod` profile 下校验 JWT 密钥、演示账号、mock、验证码回显、
  限流开关的**最终生效值**，不合规直接拒绝启动
- 两个前端接入 Vitest 单元测试（买家端 44 例 / 商家端 23 例），并纳入 CI，
  含 `src/icons.test.js` 图标白名单守卫
- Element Plus 与图标改为按需引入（`unplugin-vue-components` + `unplugin-auto-import`）
- 业务闭环集成测试：售后 13 例、评价 6 例、消息与收藏足迹 11 例
- 引入 JaCoCo 覆盖率门禁（LINE ≥ 60%，当前 71.1%）并纳入 CI
- 接入 Dependabot 依赖扫描（Maven / 两个前端 npm / GitHub Actions）
- 补齐开源协作骨架：LICENSE(MIT)、CONTRIBUTING、CHANGELOG、.editorconfig

### 变更

- 前端主包体积：买家端 **1269 kB → 374 kB**（gzip 410 → 136 kB）、
  商家端 **1.09 MB → 301 kB**（gzip 341 → 105 kB）
- 支付单 `refund_amount` 语义明确为**累计**已退金额
- `application-prod.yml` 加固：JWT 密钥与 Redis 口令改为无默认值（缺失即启动失败）、
  MySQL 默认 `sslMode=REQUIRED` 且关闭 `allowPublicKeyRetrieval`、
  Actuator 仅暴露 health/info/prometheus 且 `show-details=never`
- 测试规模：53 个测试类 + 1 基类，629 个后端用例 + 67 个前端用例

### 修复

- **售后审批通过后不退款**：工单显示「已完成」但资金从未退回（原实现只有订单取消/关单两条退款路径）
- **商家拒绝被显示成「已取消」**：拒绝时把买家侧 `status` 写成 2，与主动撤销无法区分，拒绝理由也不可见
- **退货退款是死路径**：`receive` 动作的前置状态写成 `wait_return`（应为 `wait_receive`），
  且没有任何接口能推进到 `wait_receive`
- 售后撤销规则修正：待寄回状态也应可撤销（商品仍在买家手里）
- 支付单 `refund_amount` 此前按本次金额覆盖写，多次退款后对账会出现合计不符
- 限流器并发槽位由「先加后判再减」改为 CAS 循环，消除在途计数瞬时超限导致的偶发测试失败
- 文档数字漂移：端点 132→133、测试类 48→53、用例 589→629、docs 报告 20→21

## [2026-10-09]

### 新增

- 支付宝沙箱支付闭环：手写 RSA2 验签 + 关单转退款 + 主动查单补偿（真沙箱联调跑通）
- 可观测性补齐：限流/队列指标、Prometheus 告警规则、Grafana 看板（P1-1）
- 商家域声明式权限闸门，买家令牌越权由 401 修正为 403

### 修复

- HTTP 指标由 Summary 改为 Histogram，修复看板延迟面板无数据
- 缓存：其余三个缓存补 `sync=true` 实现跨实例互斥重建；`homeFloors` 自调用绕过缓存问题
- 支付：签名串规则修正（`sign_type` 必须参与）、时钟漂移导致的静默漏单

## [2026-10-08]

### 新增

- 多实例部署能力：号段发号 / 限流切分 / 会话广播 / 削峰死信，含 3 实例实测
- 库存按维度分桶（商品、秒杀活动），出库与落库并行度 1→N
- 秒杀削峰 × 库存分桶 2×2 压测对比与结果归档
- 商品发布时间字段，并落库最近两年数码商品数据

### 修复

- 秒杀限流并发上限 32→256，避免误拒
- 修复缓存反序列化导致的推荐位故障

## [2026-10-07]

### 新增

- 项目初始版本：三端齐备（买家端 / 商家端 / 后端）、秒杀削峰异步化、业务级熔断降级
- 全链路 traceId 与结构化日志，慢 SQL 治理与日志脱敏降噪
- GitHub Actions 流水线（前后端构建与测试自动化）
