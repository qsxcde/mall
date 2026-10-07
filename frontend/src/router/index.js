import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

import MainLayout from '@/layouts/MainLayout.vue'
import AuthLayout from '@/layouts/AuthLayout.vue'

const routes = [
  {
    path: '/',
    component: MainLayout,
    children: [
      { path: '', name: 'home', component: () => import('@/views/HomeView.vue'), meta: { title: '首页' } },
      { path: 'category', name: 'category', component: () => import('@/views/CategoryView.vue'), meta: { title: '商品分类' } },
      // 动态路由：商品详情
      { path: 'product/:id', name: 'product', component: () => import('@/views/ProductView.vue'), meta: { title: '商品详情' } },
      { path: 'search', name: 'search', component: () => import('@/views/SearchView.vue'), meta: { title: '搜索结果' } },
      { path: 'seckill', name: 'seckill', component: () => import('@/views/SeckillView.vue'), meta: { title: '限时秒杀' } },
      { path: 'newproduct', name: 'newproduct', component: () => import('@/views/NewProductView.vue'), meta: { title: '新品首发' } },
      { path: 'coupon', name: 'coupon', component: () => import('@/views/CouponView.vue'), meta: { title: '领券中心' } },
      { path: 'cart', name: 'cart', component: () => import('@/views/CartView.vue'), meta: { title: '购物车' } },
      { path: 'checkout', name: 'checkout', component: () => import('@/views/CheckoutView.vue'), meta: { title: '确认订单', requiresAuth: true } },
      { path: 'payment', name: 'payment', component: () => import('@/views/PaymentView.vue'), meta: { title: '收银台', requiresAuth: true } },
      // 订单中心：只承载「我的订单」，支持 ?status= 状态筛选
      { path: 'orders', name: 'orders', component: () => import('@/views/OrdersView.vue'), meta: { title: '我的订单', requiresAuth: true } },
      // 订单详情 / 物流跟踪 / 发表评价（动态路由 :no）
      { path: 'orders/:no', name: 'order-detail', component: () => import('@/views/order/OrderDetailView.vue'), meta: { title: '订单详情', requiresAuth: true } },
      { path: 'orders/:no/logistics', name: 'logistics', component: () => import('@/views/order/LogisticsView.vue'), meta: { title: '物流跟踪', requiresAuth: true } },
      { path: 'orders/:no/review', name: 'review', component: () => import('@/views/order/ReviewView.vue'), meta: { title: '发表评价', requiresAuth: true } },
      // 个人中心：资料 / 优惠券 / 地址 / 安全 / 售后，支持 ?tab= 定位面板
      { path: 'user', name: 'user', component: () => import('@/views/UserCenterView.vue'), meta: { title: '个人中心', requiresAuth: true } },
      // 售后申请 / 售后详情（动态路由 :id）
      { path: 'aftersale/apply', name: 'aftersale-apply', component: () => import('@/views/aftersale/ApplyView.vue'), meta: { title: '申请售后', requiresAuth: true } },
      { path: 'aftersale/:id', name: 'aftersale-detail', component: () => import('@/views/aftersale/DetailView.vue'), meta: { title: '售后详情', requiresAuth: true } },
      // 账户侧：收藏 / 浏览历史 / 积分商城 / 消息中心 / 我的评价
      { path: 'favorites', name: 'favorites', component: () => import('@/views/FavoritesView.vue'), meta: { title: '我的收藏', requiresAuth: true } },
      { path: 'history', name: 'history', component: () => import('@/views/HistoryView.vue'), meta: { title: '浏览历史', requiresAuth: true } },
      { path: 'points', name: 'points', component: () => import('@/views/PointsMallView.vue'), meta: { title: '积分商城', requiresAuth: true } },
      { path: 'messages', name: 'messages', component: () => import('@/views/MessagesView.vue'), meta: { title: '消息中心', requiresAuth: true } },
      { path: 'my-reviews', name: 'my-reviews', component: () => import('@/views/MyReviewsView.vue'), meta: { title: '我的评价', requiresAuth: true } },
      { path: 'member', name: 'member', component: () => import('@/views/MemberView.vue'), meta: { title: '会员中心', requiresAuth: true } },
      { path: 'about', name: 'about', component: () => import('@/views/AboutView.vue'), meta: { title: '关于我们' } },
      { path: 'help', name: 'help', component: () => import('@/views/HelpView.vue'), meta: { title: '帮助中心' } },
      { path: 'policy', name: 'policy', component: () => import('@/views/PolicyView.vue'), meta: { title: '退换货政策' } }
    ]
  },
  {
    path: '/',
    component: AuthLayout,
    children: [
      { path: 'login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { title: '登录' } },
      { path: 'register', name: 'register', component: () => import('@/views/auth/RegisterView.vue'), meta: { title: '注册' } },
      { path: 'forgot', name: 'forgot', component: () => import('@/views/auth/ForgotView.vue'), meta: { title: '找回密码' } }
    ]
  },
  // 兼容旧静态页地址
  { path: '/PageHome.html', redirect: '/' },
  { path: '/index.html', redirect: '/' },
  { path: '/register.html', redirect: '/login' },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

// 全局前置守卫：登录校验 + 动态标题
router.beforeEach((to) => {
  const user = useUserStore()
  document.title = to.meta.title ? `${to.meta.title} - 极客数码` : '极客数码商城'

  if (to.meta.requiresAuth && !user.isLoggedIn) {
    // 未登录 → 跳登录页并携带回跳地址
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if ((to.name === 'login' || to.name === 'register') && user.isLoggedIn) {
    return { path: to.query.redirect || '/' }
  }
  return true
})

export default router
