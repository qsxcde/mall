# 贡献指南

感谢你花时间看这个项目。在动手之前，请先了解它的定位。

## 一、项目定位

这是一个**以学习和验证为目的**的三端电商项目（买家端 + 商家端 + 后端），重点在后端高并发治理与可重复运行的自动化验证，**不是生产系统**。

因此这里的贡献原则是：

- 追求**可验证**：任何性能结论都要有可复现的脚本与归档结果，不做外推
- 追求**可解释**：高并发手段要有注释说明「为什么这么选」，而不是堆配置
- 不追求功能堆砌：宁可把一条链路做扎实

## 二、环境要求

| 工具 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 21 | 后端 |
| Maven | 3.9+ | 后端构建 |
| Node.js | 20 | 两个前端 |
| Docker | 任意较新版本 | 起 MySQL/Redis/MinIO，以及集成测试（Testcontainers） |

> 集成测试会由 Testcontainers 自动拉起真实的 MySQL 8 / Redis 7 容器，**Docker 必须处于运行状态**，否则测试会失败。

## 三、本地启动

```bash
# 1. 起基础设施（MySQL / Redis / MinIO）
docker compose -f infra/docker-compose.yml up -d

# 2. 起后端
bash scripts/run-backend.sh

# 3. 起买家端 / 商家端（分别开两个终端）
cd frontend && npm ci && npm run dev
cd frontendMerchant && npm ci && npm run dev
```

数据库结构由 Flyway 在启动时自动迁移，无需手工执行 SQL。

## 四、提交前必须通过的门禁

CI（`.github/workflows/ci.yml`）会执行下列检查，本地请先跑一遍再提交。

**后端**

```bash
cd backend
mvn spotless:apply     # 自动修格式（见下方说明）
mvn test               # 全量测试，当前 589 个用例
mvn jacoco:check       # 覆盖率门禁：LINE ≥ 60%
```

**前端**（`frontend/` 与 `frontendMerchant/` 各跑一遍）

```bash
npm run lint
npm run format
npm run build
```

**跨前端格式检查**（CI 用它做 ratchet 校验，需在仓库根执行）

```bash
bash scripts/check-frontend-style.sh origin/main frontend
bash scripts/check-frontend-style.sh origin/main frontendMerchant
```

### 关于「ratchet（渐进式收敛）」

后端 Spotless 与前端 Prettier 都配置了 **ratchet**：只要求**本次改动过的文件**符合格式，历史遗留文件不受影响。

这意味着：**改动某个旧文件后，该文件会被纳入格式约束**，需要跑一次 `mvn spotless:apply`（前端 `npm run format`）把它收敛。这是有意的设计——避免一次性全库重排冲掉 git blame，同时保证被碰过的代码逐步统一。

## 五、提交信息规范

本项目使用 [Conventional Commits](https://www.conventionalcommits.org/)，**描述用中文**，与既有历史保持一致：

```
<type>(<scope>): <简要描述>

<为什么这么改，而不是改了什么>
```

| type | 含义 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `perf` | 性能优化 |
| `docs` | 文档 |
| `test` | 测试 |
| `chore` | 构建/工具/杂项 |

常用 scope：`seckill`、`inventory`、`payment`、`cache`、`cluster`、`observability`、`loadtest`、`ci`。

**提交要按语义拆分**：一个提交只做一件事。修复与文档对齐不要混在同一个提交里。

## 六、文档约定

`docs/` 下有两类文档，处理方式不同：

- **现状类**（`spec/` 等）：描述系统当前状态，**代码变了就要同步更新**
- **快照类**（`planning/`、`benchmark/`、`concurrency/` 下带日期的报告）：是某个时间点的记录，**保持原值不改**，其数字在当时是准确的

新增报告请带上日期（如 `xxx-2026-10-17.md`）。

## 七、Pull Request 检查清单

- [ ] 本地已跑通第四节的全部门禁
- [ ] 提交按语义拆分，信息符合第五节规范
- [ ] 新增 HTTP 接口已在 `RateLimitPolicies` 中显式声明限流规则（覆盖度测试会强制校验）
- [ ] 新增前端图标已登记到 `vite.config.js` 的 `ICON_NAMES`（`src/icons.test.js` 会强制校验）
- [ ] 涉及当前状态的文档已同步更新
- [ ] 性能相关改动附带可复现的压测脚本与结果归档

## 八、发版流程

采用[语义化版本](https://semver.org/lang/zh-CN/)，当前版本见 `CHANGELOG.md` 顶部。

1. 把 `[未发布]` 小节改写为 `[x.y.z] - YYYY-MM-DD`，并在其上方重新插入空的 `[未发布]`
2. 确认三类门禁全绿：后端 `mvn test` + `jacoco:check` + `spotless:check`；
   前端 `npm run test` + `npm run lint` + `npm run build`
3. 同步受影响的数字（端点总数、测试用例数、文档份数），`CONTRIBUTING.md` 的
   「文档约定」要求现状类文档随代码更新
4. 打 tag 并推送：

   ```bash
   git tag -a v1.0.0 -m "v1.0.0"
   git push origin v1.0.0
   ```

版本号怎么定：

| 变更内容 | 版本位 |
| --- | --- |
| 新增接口/页面/能力，向后兼容 | 次版本 +1 |
| 修缺陷、改文档、调性能，接口不变 | 修订号 +1 |
| 删改已有接口语义、需要数据迁移（如新增 Flyway 且不兼容旧数据） | 主版本 +1 |
