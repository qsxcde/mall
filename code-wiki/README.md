# Geek Mall · Code Wiki

本目录是 **Geek Mall（极客数码商城）** 仓库的代码维基（Code Wiki），面向首次接触本项目的开发者，
系统性地说明项目架构、模块职责、关键类与函数、依赖关系以及运行方式。

> 生成基线：仓库当前工作区（2026-10）。代码以仓库实际文件为准，本文档如与代码不一致，以代码为准。

---

## 一、项目速览

| 维度 | 说明 |
| --- | --- |
| 项目定位 | 三端齐备的数码 3C 电商学习项目：**买家端 + 商家端 + 后端**，重点在后端高并发治理 |
| 后端 | Spring Boot 3.3.5 + JDK 21 + MyBatis-Plus + MySQL 8 + Redis 7，133 个 REST 接口，统一前缀 `/api/v1` |
| 前端 | Vue 3 + Vite + Pinia + Element Plus，买家端（5173）与商家端（5174）两个独立工程 |
| 基础设施 | Docker Compose 编排 MySQL / Redis / MinIO，`observability` profile 额外起 Prometheus / Grafana / Loki / Promtail |
| 质量保障 | 629 个测试用例（Testcontainers 真实容器集成测试）+ k6/JMeter 压测体系 + GitHub Actions CI |
| 核心能力 | 全站限流、缓存三防、秒杀削峰、幂等与状态机、库存分桶、分布式锁、全链路 TraceId |

## 二、文档导航

| 文档 | 内容 | 回答的问题 |
| --- | --- | --- |
| [1-项目整体架构](./1-项目整体架构.md) | 系统拓扑、分层架构、核心请求链路、高并发治理总览 | 这个项目长什么样？请求怎么流转？ |
| [2-后端模块说明](./2-后端模块说明.md) | `common` / `config` / `security` / 13 个业务域的职责与文件组织 | 后端每个包是干什么的？ |
| [3-关键类与函数说明](./3-关键类与函数说明.md) | 限流、缓存、熔断、秒杀、状态机、幂等、分桶等核心类的方法级说明 | 核心逻辑写在哪些类的哪些方法里？ |
| [4-前端模块说明](./4-前端模块说明.md) | 买家端 / 商家端的路由、页面、Store、API 层、组件与构建配置 | 前端怎么组织的？ |
| [5-依赖关系](./5-依赖关系.md) | 技术依赖、模块间调用关系、数据依赖、外部中间件依赖 | 谁依赖谁？改一处会波及哪里？ |
| [6-运行与测试指南](./6-运行与测试指南.md) | 环境要求、启动步骤、测试、压测、CI、常见问题 | 怎么把它跑起来？ |

## 三、仓库顶层结构

```
.
├── backend/             # Spring Boot 后端（含 Dockerfile、Flyway 迁移、629 个测试）
├── frontend/            # 买家端（Vue 3，Vite 端口 5173）
├── frontendMerchant/    # 商家端（Vue 3，Vite 端口 5174）
├── infra/               # docker-compose 编排 + Prometheus/Grafana/Loki provisioning
├── loadtest/            # k6 / JMeter 压测脚本、数据准备、结果校验、实测结果
├── scripts/             # run-backend.sh、check-frontend-style.sh
├── docs/                # 20 份分析报告（planning / concurrency / benchmark / spec / governance）
├── .github/workflows/   # CI（后端 Spotless + mvn test；双前端 ESLint + build）
└── code-wiki/           # 本文档
```

## 四、阅读建议

- **只想把项目跑起来** → 直接看 [6-运行与测试指南](./6-运行与测试指南.md)
- **想快速理解全局** → [1-项目整体架构](./1-项目整体架构.md) → [2-后端模块说明](./2-后端模块说明.md)
- **要改高并发相关代码** → [3-关键类与函数说明](./3-关键类与函数说明.md) + `docs/concurrency/`、`docs/spec/接口限流策略说明.md`
- **想了解项目实测结论** → `docs/` 下的报告与 `loadtest/results/`，本文档只描述"代码是怎么写的"，实测数字以报告为准
