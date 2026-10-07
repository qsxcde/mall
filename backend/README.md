# 极客数码商城 - 后端服务（backend）

Spring Boot 3.3 + JDK 21 + MyBatis-Plus + MySQL 8 + Redis 7 的后端脚手架，接口与前端
`src/views` 各页面一一对应，统一前缀 `/api/v1`。

## 技术栈

| 维度 | 选型 |
| --- | --- |
| 运行时 | JDK 21 |
| 框架 | Spring Boot 3.3.5 |
| 安全 | Spring Security 6 + JWT（无状态，Redis 保存会话） |
| 持久层 | MyBatis-Plus 3.5.7 + MySQL 8 + Flyway（自动建表 + 种子数据） |
| 缓存 | Redis 7（验证码 / 登录会话 / 秒杀库存预留） |
| 文档 | springdoc-openapi（Swagger UI） |
| 可观测性 | Actuator + Micrometer/Prometheus + TraceId 日志链路 |

## 快速开始

### 1. 启动基础设施

```bash
# 在仓库根目录执行
docker compose -f infra/docker-compose.yml up -d
```

会启动 MySQL(3306) / Redis(6379) / MinIO(9000,9001)。首次启动会初始化数据库
`geek_mall`，表结构与演示数据在应用启动时由 Flyway 自动执行。

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run
```

- 服务地址：http://localhost:8080
- 接口文档：http://localhost:8080/swagger-ui.html
- 健康检查：http://localhost:8080/actuator/health
- 指标：http://localhost:8080/actuator/prometheus

### 3. 演示账号

dev 环境启动时会自动创建（见 `DemoDataInitializer`）：

```
账号：13800000000
密码：123456
```

dev 环境下短信验证码会在接口响应中回显（`mall.sms.expose-code=true`），同时打印在日志里。

## 目录结构

```
backend/
├── pom.xml
├── Dockerfile
└── src/main/
    ├── java/com/geekmall/
    │   ├── GeekMallApplication.java
    │   ├── common/                  # 通用层
    │   │   ├── result/              # Result / PageResult / ResultCode
    │   │   ├── exception/           # BizException / GlobalExceptionHandler
    │   │   ├── enums/               # OrderStatus（订单状态机）
    │   │   ├── constant/            # RedisKeys / SecurityConstants
    │   │   ├── filter/              # TraceIdFilter
    │   │   └── util/                # JwtUtil
    │   ├── config/                  # Security / MyBatis-Plus / CORS / Redis / OpenAPI
    │   ├── security/                # JWT 过滤器、登录用户上下文、401/403 处理
    │   └── modules/                 # 按业务域分包
    │       ├── auth/                # ✅ 已实现：注册 / 登录 / 验证码 / 找回密码
    │       ├── user/                # ✅ 已实现：资料 / 地址 / 签到 / 演示账号初始化
    │       ├── product/             # ✅ 已实现：分类 / 列表 / 搜索 / 详情 / 首页楼层
    │       ├── cart/                # ✅ 已实现：购物车增删改查 / 勾选 / 汇总
    │       ├── trade/               # ✅ 已实现：结算试算 / 幂等下单 / 订单状态机 / 订单查询 / 物流 / 超时关闭
    │       ├── payment/             # ✅ 已实现：创建支付 / 查询 / 渠道回调（幂等 + 金额校验）
    │       ├── marketing/           # ✅ 已实现：领券中心 / 秒杀(Redis Lua 预扣) / 积分商城
    │       ├── review/              # ✅ 已实现：发表评价 / 我的评价 / 商品评价
    │       ├── aftersale/           # ✅ 已实现：申请售后 / 列表 / 详情 / 取消
    │       ├── message/             # ✅ 已实现：消息中心 + 订单状态变更自动投递（事务事件解耦）
    │       └── content/             # ✅ 已实现：FAQ / 政策 / 关于我们 / 图片上传（MinIO 或本地磁盘）
    └── resources/
        ├── application.yml          # 公共配置
        ├── application-dev.yml      # 本地环境
        ├── application-prod.yml     # 生产环境
        ├── logback-spring.xml       # 日志（含 traceId）
        └── db/migration/
            ├── V1__init.sql         # 建表脚本（20 张表）
            ├── V2__seed.sql         # 演示数据
            └── V3__payment.sql      # 支付单表
```

每个业务域内部统一分层：`controller → service(+impl) → mapper`，配合 `entity / dto / vo / converter`。

## 已实现接口

| 模块 | 方法 | 路径 | 说明 |
| --- | --- | --- | --- |
| 认证 | POST | `/api/v1/auth/login` | 密码登录 |
| 认证 | POST | `/api/v1/auth/login/sms` | 短信登录（未注册自动建号） |
| 认证 | POST | `/api/v1/auth/register` | 注册 |
| 认证 | POST | `/api/v1/auth/sms-code` | 发送验证码（60s 冷却） |
| 认证 | POST | `/api/v1/auth/password/reset` | 重置密码 |
| 认证 | POST | `/api/v1/auth/logout` | 登出（令牌立即失效） |
| 用户 | GET/PUT | `/api/v1/user/profile` | 资料查询 / 编辑 |
| 用户 | GET/POST | `/api/v1/user/addresses` | 地址列表 / 新增 |
| 用户 | PUT/DELETE | `/api/v1/user/addresses/{id}` | 编辑 / 删除 |
| 用户 | PUT | `/api/v1/user/addresses/{id}/default` | 设为默认 |
| 用户 | GET/POST | `/api/v1/user/sign-in` | 签到状态 / 每日签到 |
| 商品 | GET | `/api/v1/products` | 列表（分类/品牌/价格/排序/分页） |
| 商品 | GET | `/api/v1/products/search` | 搜索（关键词） |
| 商品 | GET | `/api/v1/products/{id}` | 详情 |
| 商品 | GET | `/api/v1/products/{id}/recommend` | 看了又看 |
| 分类 | GET | `/api/v1/categories/tree` | 分类树 |
| 首页 | GET | `/api/v1/home/floors` | 首页楼层聚合 |
| 购物车 | GET/POST | `/api/v1/cart/items` | 列表 / 加入 |
| 购物车 | PUT | `/api/v1/cart/items/{id}/qty` | 改数量 |
| 购物车 | PUT | `/api/v1/cart/items/{id}/checked` | 改勾选 |
| 购物车 | DELETE | `/api/v1/cart/items/{id}` | 删除单项 |
| 购物车 | PUT/DELETE | `/api/v1/cart/checked` | 全选 / 删除已勾选 |
| 购物车 | GET | `/api/v1/cart/summary` | 汇总（角标 / 结算栏） |
| 交易 | POST | `/api/v1/trade/pre-order` | 结算试算（金额/地址/配送/可用券/支付方式） |
| 交易 | POST | `/api/v1/trade/orders` | **幂等下单**（返回订单号） |
| 交易 | POST | `/api/v1/trade/orders/{no}/cancel` | 取消订单（回滚库存 + 回退券） |
| 交易 | POST | `/api/v1/trade/orders/{no}/confirm` | 确认收货 |
| 交易 | POST | `/api/v1/trade/orders/{no}/remind` | 提醒发货 |
| 订单 | GET | `/api/v1/orders` | 我的订单（状态筛选 + 关键词 + 分页） |
| 订单 | GET | `/api/v1/orders/status-counts` | 各状态订单计数 |
| 订单 | GET | `/api/v1/orders/{no}` | 订单详情（含进度时间轴、物流摘要） |
| 订单 | GET | `/api/v1/orders/{no}/logistics` | 物流跟踪 |
| 支付 | POST | `/api/v1/pay/create` | 创建支付单 |
| 支付 | GET | `/api/v1/pay/{tradeNo}/status` | 查询支付状态（前端轮询） |
| 支付 | POST | `/api/v1/pay/{tradeNo}/mock-pay` | 模拟支付成功（演示用） |
| 支付 | POST | `/api/v1/pay/callback` | 渠道异步回调（白名单接口） |
| 交易 | POST | `/api/v1/trade/orders/{no}/ship` | **仅 dev**：模拟商家发货（打通收货/评价链路） |
| 优惠券 | GET | `/api/v1/coupons/templates` | 领券中心列表（白名单，未登录可浏览） |
| 优惠券 | POST | `/api/v1/coupons/claim/{templateId}` | 领取（库存条件更新 + 唯一索引双幂等） |
| 优惠券 | GET | `/api/v1/coupons/mine` | 我的优惠券 |
| 秒杀 | GET | `/api/v1/seckill/sessions` | 场次列表（白名单） |
| 秒杀 | GET | `/api/v1/seckill/items` | 场次商品（白名单） |
| 秒杀 | POST | `/api/v1/seckill/{itemId}/order` | 立即抢购（Redis Lua 预扣 + 一人一单） |
| 积分 | GET | `/api/v1/points/goods` | 积分商品（带「积分是否足够」） |
| 积分 | POST | `/api/v1/points/exchange/{goodsId}` | 积分兑换（扣积分 + 扣库存同事务） |
| 积分 | GET | `/api/v1/points/records` | 我的兑换记录 |
| 评价 | POST | `/api/v1/reviews` | 发表评价（提交后订单流转为「已完成」） |
| 评价 | GET/DELETE | `/api/v1/user/reviews`、`/{id}` | 我的评价 / 删除 |
| 评价 | GET | `/api/v1/products/{id}/reviews` | 商品评价列表（白名单） |
| 售后 | POST | `/api/v1/aftersales` | 申请售后（仅待评价/已完成且无处理中售后） |
| 售后 | GET | `/api/v1/aftersales` | 我的售后列表（按状态筛选） |
| 售后 | GET | `/api/v1/aftersales/{id}` | 售后详情（含处理进度时间轴） |
| 售后 | POST | `/api/v1/aftersales/{id}/cancel` | 取消售后申请 |
| 消息 | GET | `/api/v1/messages` | 消息列表（按类型筛选） |
| 消息 | GET | `/api/v1/messages/unread-count` | 未读数（顶栏红点） |
| 消息 | POST | `/api/v1/messages/{id}/read` | 标记单条已读 |
| 消息 | POST | `/api/v1/messages/read-all` | 全部标记已读 |
| 内容 | GET | `/api/v1/cms/about` | 关于我们（统计实时取自数据库） |
| 内容 | GET | `/api/v1/cms/faqs` | 帮助中心 FAQ |
| 内容 | GET | `/api/v1/cms/policies` | 政策条款 |
| 文件 | POST | `/api/v1/files?biz=avatar\|review\|aftersale` | 图片上传 |

## 统一响应

```json
{ "code": 0, "msg": "success", "data": { } }
```

- `code = 0` 表示成功，其余为业务错误码（见 `ResultCode`）。
- 未登录返回 `code = 401`（HTTP 状态仍为 200），前端拦截器据此跳转登录页。
- 每个响应头都带 `X-Trace-Id`，与日志中的 `[traceId]` 对应，便于排障。

## 配置说明

| 配置项 | 说明 | 默认 |
| --- | --- | --- |
| `MALL_JWT_SECRET` | JWT 密钥，长度需 ≥ 32 字节 | 内置开发值，生产必须覆盖 |
| `MYSQL_HOST/PORT/DB/USER/PASSWORD` | 数据库连接 | localhost:3306/geek_mall |
| `REDIS_HOST/PORT` | Redis 连接 | localhost:6379 |
| `mall.init-demo-user` | 是否初始化演示账号 | dev=true |
| `mall.sms.expose-code` | 是否回显验证码 | dev=true，生产必须 false |
| `mall.mock.enabled` | 是否注册模拟发货等联调接口 | dev=true，生产必须 false |
| `mall.storage.type` | 文件存储实现：`minio` 对象存储 / `local` 本地磁盘 | minio |
| `mall.storage.local-dir` | local 模式的落盘目录 | ./uploads |
| `MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET` | 对象存储连接信息 | localhost:9000 |

## 后续待实现（骨架已建好包边界）

按 `modules/*/package-info.java` 中的说明逐个补齐即可：

前端 `src/views` 下的**所有页面均已具备对应后端接口**。剩下的都是运营后台与增强项：

1. **P0 交易闭环** ✅ 已完成：`trade` 下单 + 订单状态机 + `payment` 支付回调
2. **P1 营销与评价** ✅ 已完成：领券中心、秒杀（Redis Lua 预扣 + 一人一单 + 失败补偿）、积分商城、评价
3. **P2 售后与内容** ✅ 已完成：售后申请/详情/取消、消息中心（订单状态变更自动投递）、CMS 接口、图片上传
4. **P3 后台与增强**：
   - **商家后台**：发货、售后审核/驳回、订单列表管理（需引入 ROLE_ADMIN 与后台模块）
   - **运营后台**：商品/分类/优惠券/秒杀场次的增删改，FAQ 与政策维护
   - 三方登录（微信/QQ/支付宝）、在线客服/工单
   - 短信与 App 推送通道、消息模板化
   - 商品评分回写（评价后更新 `pms_product.rating`）、秒杀抢购结果轮询接口
   - 真实物流轨迹接入；订单超时改用延迟队列替代轮询扫描

详细设计与取舍（秒杀是否需要 MQ、可观测性分档、中间件全景）见 `docs/后端脚手架搭建方案.md`。

## 前端联调

在 `vite.config.js` 中配置代理，避免跨域：

```js
server: {
  port: 5173,
  proxy: {
    '/api': { target: 'http://localhost:8080', changeOrigin: true }
  }
}
```

随后把前端 `src/data/shop.js` 的静态导入替换为 `src/api/` 下的 axios 调用即可。
