# 极客数码商城（Vue 3 + Element Plus 重构版）

原静态多页站点已重构为
**Vue 3 + Vite + Element Plus + Vue Router + Pinia** 的单页应用。

## 技术栈

- Vue 3（`<script setup>` 组合式 API）
- Vite 6
- Element Plus（含 `@element-plus/icons-vue`，中文语言包）
- Vue Router 4（动态路由、路由传参、路由守卫）
- Pinia（用户 / 购物车 / 优惠券，localStorage 持久化）

## 快速开始

```bash
npm install
npm run dev      # 本地开发，默认 http://localhost:5173
npm run build    # 生产构建，产物在 dist/
npm run preview  # 预览构建产物
```

## 目录结构

```
src/
├── main.js                 # 入口：注册 Element Plus / Pinia / Router
├── App.vue                 # 根组件（路由出口）
├── router/index.js         # 路由表 + 全局守卫（登录校验 / 标题）
├── layouts/
│   ├── MainLayout.vue      # 主布局：顶栏 + 头部 + 内容 + 页脚
│   └── AuthLayout.vue      # 登录页独立布局
├── components/
│   ├── AppTopbar.vue       # 顶部工具条
│   ├── AppHeader.vue       # 头部：导航 / 搜索 / 购物车
│   ├── AppFooter.vue       # 页脚
│   ├── ProductCard.vue     # 商品卡片（支持关键词高亮）
│   └── CountdownTimer.vue  # 倒计时组件
├── views/                  # 页面
│   ├── HomeView.vue        # 首页
│   ├── CategoryView.vue    # 分类（筛选 / 排序 / 分页）
│   ├── ProductView.vue     # 商品详情（规格 / 参数 / 评价）
│   ├── SearchView.vue      # 搜索结果
│   ├── SeckillView.vue     # 限时秒杀
│   ├── NewProductView.vue  # 新品首发
│   ├── CouponView.vue      # 领券中心
│   ├── CartView.vue        # 购物车
│   ├── CheckoutView.vue    # 确认订单
│   ├── PaymentView.vue     # 收银台
│   ├── OrdersView.vue      # 订单中心（我的订单，按状态筛选）
│   ├── order/              # 订单详情 / 物流跟踪 / 发表评价
│   ├── aftersale/          # 售后申请 / 售后详情
│   ├── UserCenterView.vue  # 个人中心（资料/优惠券/地址/安全/售后）
│   ├── MemberView.vue      # 会员中心
│   ├── FavoritesView.vue   # 收藏夹
│   ├── HistoryView.vue     # 浏览足迹
│   ├── MessagesView.vue    # 消息中心
│   ├── MyReviewsView.vue   # 我的评价
│   ├── PointsMallView.vue  # 积分商城
│   ├── LoginView.vue       # 登录（注册 / 找回密码见 auth/）
│   ├── auth/               # 注册 / 找回密码
│   ├── AboutView.vue / HelpView.vue / PolicyView.vue
├── stores/                 # Pinia
│   ├── user.js             # 登录态 / 资料 / 收货地址
│   ├── cart.js             # 购物车
│   ├── coupon.js           # 优惠券领取状态
│   ├── aftersale.js        # 售后记录
│   ├── collection.js       # 收藏 + 浏览历史
│   ├── message.js          # 消息中心
│   └── persist.js          # localStorage 持久化助手
├── data/constants.js       # 纯前端 UI 常量（订单状态映射、表单选项等）
└── styles/
    ├── base.css            # 全局基础 / 顶栏 / 头部 / 页脚 / EP 主题变量
    └── pages.css           # 各页面样式
```

## 路由与跳转

| 路径 | 页面 | 说明 |
| --- | --- | --- |
| `/` | 首页 | |
| `/category?cat=&sub=` | 分类 | 查询参数传参 |
| `/product/:id` | 商品详情 | 动态路由 + 参数 |
| `/search?q=` | 搜索 | 查询参数传参 |
| `/seckill` `/newproduct` `/coupon` | 秒杀 / 新品 / 领券 | |
| `/cart` `/checkout` `/payment` | 购物车 → 结算 → 支付 | |
| `/orders` | 我的订单（订单中心） | `requiresAuth`；`?status=` 状态筛选 |
| `/orders/:no` | 订单详情 | 动态路由；订单信息 / 金额 / 进度时间轴 |
| `/orders/:no/logistics` | 物流跟踪 | 运单信息 + 物流轨迹时间线 |
| `/orders/:no/review` | 发表评价 | 多维评分 / 图文评价 |
| `/aftersale/apply` | 申请售后 | 退/换/修表单，`?order=` `?type=` |
| `/aftersale/:id` | 售后详情 | 售后信息 + 处理进度 |
| `/favorites` | 我的收藏 | `requiresAuth`；商品详情可收藏 |
| `/history` | 浏览历史 | `requiresAuth`；浏览商品自动记录 |
| `/points` | 积分商城 | `requiresAuth`；积分兑换扣减积分 |
| `/messages` | 消息中心 | `requiresAuth`；未读角标 / 分类筛选 |
| `/my-reviews` | 我的评价 | `requiresAuth` |
| `/forgot` | 找回密码 | AuthLayout；手机验证 → 重置密码 |
| `/user` | 个人中心 | `requiresAuth`；`?tab=profile/coupon/address/security/aftersale` |
| `/member` | 会员中心 | `requiresAuth` 需登录 |
| `/login` | 登录 | 独立布局；底部「立即注册」跳 `/register` |
| `/register` | 注册 | 独立布局；注册成功返回登录页并回填手机号 |
| `/about` `/help` `/policy` | 文档页 | |

- 所有跳转统一使用 `router.push / replace`，不再有任何 `window.location` / 手动 history 操作。
- 全局前置守卫：受保护路由未登录时跳转 `/login?redirect=...`，登录后自动回跳并设置页面标题。
- 旧静态地址（`/PageHome.html`、`/index.html`、`/register.html`）已做重定向兼容。

## 状态管理

- `useCartStore`：购物车增删改查、勾选、全选、合计；跨页面与刷新后保留。
- `useUserStore`：登录态、个人资料、收货地址管理。
- `useCouponStore`：领券状态与结算页选中券。
- `useAfterSaleStore`：售后记录（申请页新建 → 详情页/个人中心读取）。
- `useCollectionStore`：我的收藏 + 浏览历史。
- `useMessageStore`：消息中心（未读计数 / 标记已读）。
