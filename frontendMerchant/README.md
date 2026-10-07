# 极客商家中心 · Merchant Hub

极客数码商城**商家端后台**。基于 Vue 3 + Element Plus，覆盖经营、商品、交易、财务、增长五大模块，共 9 个业务页面。

与 C 端前台（`../frontend`）共用同一套接口约定与工程规范，但视觉上刻意区分：
前台做生意（明亮、促销感），后台管生意（沉稳、报表感）。

---

## 快速开始

```bash
npm install
npm run dev      # http://localhost:5174
npm run build    # 产物输出到 dist/
npm run preview  # 预览构建产物
```

> 端口固定 **5174**（`strictPort`），避免与 C 端前台的 5173 冲突，两个工程可同时运行。

---

## 技术栈

| 关注点 | 选型 | 说明 |
|---|---|---|
| 框架 | Vue 3.5（`<script setup>`） | 全面使用组合式 API |
| UI 库 | Element Plus 2.9 | zh-cn 语言包，图标全局注册 |
| 状态 | Pinia 2.3 | 仅一个 store，承载店铺/用户/角标/通知 |
| 路由 | Vue Router 4.5 | 全部懒加载，`meta.title` 驱动标题与面包屑 |
| 构建 | Vite 6 | `@` 别名指向 `src` |
| HTTP | axios 1.20 | 统一 `{ code, msg, data }` 解包 |
| 样式 | 原生 CSS + CSS 变量 | 不引入预处理器；EP 主题通过覆写 CSS 变量实现 |
| 图表 | **自研 SVG** | 折线/柱状/环形/迷你走势，不引入 ECharts（见下文说明） |

---

## 目录结构

```
frontendMerchant/
├── index.html                 # Vite 入口
├── vite.config.js             # 别名、端口、/api 代理
├── jsconfig.json              # 编辑器路径提示
├── prototype/                 # 原始 9 份 HTML 设计稿（单文件、零依赖）
└── src/
    ├── main.js                # 应用装配（EP 图标、语言包、样式加载顺序）
    ├── App.vue                # 仅承载 router-view
    ├── router/index.js        # 路由表（9 个业务页 + 兜底）
    │
    ├── styles/
    │   ├── tokens.css         # 设计令牌（唯一的颜色/字体/尺寸来源）
    │   ├── base.css           # reset、画布质感、排版工具类、布局原语
    │   └── element.css        # Element Plus 主题覆写
    │
    ├── layouts/
    │   ├── MerchantLayout.vue # 侧栏 + 顶栏 + 内容区 + 路由过渡
    │   ├── SideNav.vue        # 5 分组导航（el-menu）+ 动态角标
    │   └── TopBar.vue         # 面包屑 / 全局搜索 / 通知 / 用户
    │
    ├── components/
    │   ├── PageHeader.vue          # 页头（眉标 + 标题 + 说明 + 操作区）
    │   ├── StatStrip.vue           # 指标条（兼容概览卡与 KPI 卡两种密度）
    │   ├── CountUp.vue             # 数字滚动
    │   ├── StatusTag.vue           # 业务状态标签（tone → 设计令牌）
    │   ├── ProductThumb.vue        # 商品缩略图占位
    │   ├── UserAvatar.vue          # 买家头像占位
    │   ├── BulkBar.vue             # 批量操作条
    │   ├── EmptyHint.vue           # 空态
    │   ├── OrderDetailBlocks.vue   # 订单详情五区块（展开行与抽屉复用）
    │   ├── WaybillCard.vue         # 电子面单
    │   └── charts/
    │       ├── LineAreaChart.vue   # 折线/面积（十字准星 + 跟随气泡）
    │       ├── BarChart.vue        # 柱状
    │       ├── DonutChart.vue      # 环形
    │       └── SparkLine.vue       # 迷你走势
    │
    ├── composables/
    │   ├── useTableQuery.js   # 列表页通用逻辑：筛选 / 请求 / 分页 / 选中
    │   ├── useCountUp.js      # 数字滚动动画
    │   └── useElementSize.js  # ResizeObserver 尺寸观测（图表用）
    │
    ├── api/
    │   ├── request.js         # axios 封装（已就绪，可直连接口）
    │   ├── catalog.js         # 商品域 + 概览 + 看板 + 导航角标
    │   ├── trade.js           # 订单 / 发货 / 售后
    │   └── growth.js          # 营销 / 评价 / 结算
    │
    ├── mock/
    │   ├── shared.js          # 商品池、买家、地区等基础字典 + 伪随机源
    │   ├── series.js          # 60 天经营日序列（全站「钱」的唯一来源）
    │   ├── catalog.js         # 商品 / 概览 / 看板
    │   ├── trade.js           # 订单 / 发货 / 售后
    │   └── growth.js          # 营销 / 评价 / 结算
    │
    ├── stores/merchant.js     # 店铺、用户、导航角标、通知、全局搜索词
    ├── utils/
    │   ├── format.js          # 金额/日期/百分比等格式化纯函数
    │   ├── dict.js            # 业务字典（状态、类型、Tab 定义）
    │   ├── chart.js           # 图表公共算法（刻度、色板、路径）
    │   └── mock.js            # Mock 适配器
    └── views/                 # 9 个业务页面
        ├── OverviewView.vue      经营概览
        ├── AnalyticsView.vue     数据看板
        ├── ProductListView.vue   商品管理
        ├── OrderListView.vue     订单管理
        ├── ShippingDeskView.vue  发货中心
        ├── AfterSaleView.vue     售后管理
        ├── FinanceSettleView.vue 财务结算
        ├── MarketingView.vue     营销中心
        └── ReviewView.vue        评价管理
```

---

## 架构约定

### 1. 分层：视图只消费 api，不碰 mock

```
views/  →  api/  →  mock/       （当前）
views/  →  api/  →  request.js  （接入后端后）
```

视图从不 import `@/mock/*`。所有过滤、排序、分页都发生在 `api/` 层，模拟服务端行为——
真实接口就绪时，只需把 `resolveMock(...)` 换成 `request.get(...)`，**视图与组件零改动**。

### 2. 全局状态只有一份角标

侧栏角标（商品预警、待发货、超时、售后、营销、评价）集中在 `stores/merchant.js` 加载一次，
顶栏通知文案也由同一份角标拼装。因此不会出现「通知说 3 笔、角标显示 0 笔」的矛盾。
页面内的写操作完成后调用 `store.loadBadges(true)` 即可刷新全局。

### 3. 设计令牌是唯一的颜色来源

`styles/tokens.css` 定义全部颜色/字体/圆角/阴影。`styles/element.css` 把 Element Plus 的
CSS 变量指向这些令牌，组件内部结构不动——升级 EP 版本不易破版。
业务状态色通过 `StatusTag` 的 `tone` 属性映射，视图层不出现任何色值。

### 4. 列表页逻辑收口在 `useTableQuery`

订单、商品、发货、售后、营销、评价六个列表页只声明「参数 + 渲染」，
筛选联动、竞态保护、分页、全选/半选态统一由 composable 处理。

### 5. 数据口径自洽

全站金额都从 `mock/series.js` 的 60 天日序列聚合而来：

- 经营概览「本月营收」= 最近 30 天成交额之和
- 数据看板「30 天成交额」= 同一区间
- 财务结算的 6 个账期成交额 = 该区间按 5 天切分

因此三个页面的钱**对得上**。结算单内部也保证
`本期实结 = 成交额 − 平台佣金 − 支付服务费 − 退款扣减`，任意一行都能手工复核。

### 6. 图表为什么自研

折线/柱状/环形三个图表都是手写 SVG，没有引入 ECharts：

- 视觉完全可控（描线动画、分段揭示、气泡翻转都能精确实现）
- 包体极小，且不需要 `preserveAspectRatio="none"` 那种会把描边拉粗的自适应技巧
- 图表通过 `ResizeObserver` 测量容器后用**真实像素坐标**绘制，缩放窗口是干净重绘

代价是缺少坐标轴交互与大数据量优化。若后续需要复杂图表，建议按图替换为 ECharts，不影响其他部分。

### 7. 构建产物与进一步瘦身

构建结果（`npm run build`）：

| chunk | 体积 | gzip |
|---|---|---|
| `element-plus` | 1.09 MB | 341 kB |
| `vue` | 112 kB | 44 kB |
| `index`（应用逻辑） | 46 kB | 19 kB |
| 各业务视图（懒加载，9 个） | 2 ~ 25 kB | 1 ~ 8 kB |

Element Plus 采用**全量引入**，是刻意接受的取舍：换来实现简单、组件样式零遗漏。
若需要进一步压缩，可按需引入：

```bash
npm i -D unplugin-vue-components unplugin-auto-import
```

```js
// vite.config.js
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

plugins: [
  vue(),
  AutoImport({ resolvers: [ElementPlusResolver()] }),
  Components({ resolvers: [ElementPlusResolver()] })
]
```

同时移除 `main.js` 里的 `app.use(ElementPlus)` 与 `element-plus/dist/index.css` 引入，
并把全局图标注册改为按需 import。预计可省下 50%~60% 的 EP 体积，
但需要逐页回归确认组件样式未丢失。

---

## 接入真实后端

1. 启动后端（`backend/`），Vite 已配置 `/api` 代理到 `http://localhost:8080`
   （可通过 `VITE_API_TARGET` 覆盖）。
2. 把 `src/api/*.js` 中每个函数的 `resolveMock(() => ...)` 换成对应 `request.*` 调用，
   例如：

   ```js
   // 改造前
   export function fetchOrderPage(params = {}) {
     return resolveMock(() => { /* 本地过滤分页 */ })
   }

   // 改造后
   export function fetchOrderPage(params = {}) {
     return request.get('/merchant/order/page', { params })
   }
   ```

3. 抽掉 `src/mock/` 与 `utils/mock.js`。

> 注意：商家端接口目前**尚未落地**。`backend/` 只有 C 端买家侧接口
> （`AfterSaleController` 仅覆盖「申请/列表/详情/取消」），商家侧的订单履约、
> 结算账户、电子面单对接等都需要新建 API。

---

## 桌面端响应式

按需求只适配桌面端，双档断点：

| 断点 | 变化 |
|---|---|
| ≥ 1560px | 完整布局：指标条 5 列、商品网格 4 列 |
| 1440 ~ 1560px | 指标条 3 列、商品网格 3 列、筛选栏隐藏文字标签 |
| 1240 ~ 1440px | 发货中心双栏收为单栏、看板详情区单列 |
| 1024 ~ 1240px | 指标条 2 列、商品网格 2 列 |

同时通过 `prefers-reduced-motion` 关闭全部动画，尊重系统偏好。

---

## 设计稿对照

`prototype/` 保留了全部 9 份单文件 HTML 设计稿（零依赖，可直接双击打开），
与 `src/views/` 一一对应，便于逐页比对还原度。
