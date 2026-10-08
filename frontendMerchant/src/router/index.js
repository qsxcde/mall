import { createRouter, createWebHistory } from 'vue-router'
import MerchantLayout from '@/layouts/MerchantLayout.vue'
import { hasToken } from '@/api/token'
import { useMerchantStore } from '@/stores/merchant'

/**
 * 商家中心路由表。
 *
 * 约定：
 * - 除登录页外，全部为 MerchantLayout 的子路由，共享侧栏与顶栏
 * - 组件懒加载，按需分包
 * - meta.title 供浏览器标题与面包屑共用
 * - meta.group / meta.label 供侧栏导航自动生成
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '商家登录', public: true }
  },
  {
    path: '/',
    component: MerchantLayout,
    redirect: '/overview',
    children: [
      {
        path: 'overview',
        name: 'overview',
        component: () => import('@/views/OverviewView.vue'),
        meta: { title: '经营概览', group: '经营', label: '经营概览' }
      },
      {
        path: 'analytics',
        name: 'analytics',
        component: () => import('@/views/AnalyticsView.vue'),
        meta: { title: '数据看板', group: '经营', label: '数据看板' }
      },
      {
        path: 'products',
        name: 'products',
        component: () => import('@/views/ProductListView.vue'),
        meta: { title: '商品管理', group: '商品', label: '商品管理', badge: 'productWarn' }
      },
      {
        path: 'inventory',
        name: 'inventory',
        component: () => import('@/views/InventoryBucketView.vue'),
        meta: { title: '库存分桶', group: '商品', label: '库存分桶' }
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/OrderListView.vue'),
        meta: { title: '订单管理', group: '交易', label: '订单管理', badge: 'orderPending' }
      },
      {
        path: 'shipping',
        name: 'shipping',
        component: () => import('@/views/ShippingDeskView.vue'),
        meta: { title: '发货中心', group: '交易', label: '发货中心', badge: 'shippingLate' }
      },
      {
        path: 'aftersale',
        name: 'aftersale',
        component: () => import('@/views/AfterSaleView.vue'),
        meta: { title: '售后管理', group: '交易', label: '售后管理', badge: 'aftersale' }
      },
      {
        path: 'finance',
        name: 'finance',
        component: () => import('@/views/FinanceSettleView.vue'),
        meta: { title: '财务结算', group: '财务', label: '财务结算' }
      },
      {
        path: 'marketing',
        name: 'marketing',
        component: () => import('@/views/MarketingView.vue'),
        meta: { title: '营销中心', group: '增长', label: '营销中心', badge: 'marketing' }
      },
      {
        path: 'reviews',
        name: 'reviews',
        component: () => import('@/views/ReviewView.vue'),
        meta: { title: '评价管理', group: '增长', label: '评价管理', badge: 'reviewWait' }
      }
    ]
  },
  // 兜底：未匹配的地址回经营概览（未登录时会被守卫再转到登录页）
  { path: '/:pathMatch(.*)*', redirect: '/overview' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/**
 * 全局前置守卫。
 *
 * - 未登录访问业务页 → 记下来源地址，跳登录页
 * - 已登录访问登录页 → 直接放行到概览，避免重复登录
 * - 进业务页前确保店铺资料已加载（刷新页面时 store 是空的）
 */
router.beforeEach(async (to) => {
  const logged = hasToken()

  if (to.meta.public) {
    return logged ? { path: '/overview' } : true
  }
  if (!logged) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  const store = useMerchantStore()
  try {
    await store.loadProfile()
  } catch (e) {
    // 资料拉取失败多为令牌失效，拦截器已负责清令牌并跳登录，这里只需中止本次导航
    return false
  }
  return true
})

// 动态标题
router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - 极客商家中心` : '极客商家中心'
})

export default router
