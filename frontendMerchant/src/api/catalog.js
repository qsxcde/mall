/**
 * 商品域与经营数据 API：导航角标 / 经营概览 / 数据看板 / 商品列表。
 *
 * 过滤、排序、分页、聚合全部在服务端完成，视图只负责把参数传下来、把结果渲染出去。
 * 分页接口返回 { list, total, tabs, stats, … }，其中 tabs/stats 会被
 * useTableQuery 收进 extras，视图按需取用。
 */
import request from './request'

/* ==========================================================================
   经营数据
   ========================================================================== */

/**
 * 侧栏导航角标。
 * 服务端集中计算，保证「不管在哪个页面，角标数值都一致」。
 */
export function fetchNavBadges() {
  return request.get('/merchant/nav/badges')
}

/** 经营概览 */
export function fetchOverview() {
  return request.get('/merchant/overview')
}

/** 数据看板 */
export function fetchAnalytics() {
  return request.get('/merchant/analytics')
}

/** 库存预警商品 */
export function fetchStockAlerts() {
  return request.get('/merchant/product/stock-alerts')
}

/** 近 N 天日序列 */
export function fetchDailySeries(days = 30) {
  return request.get('/merchant/analytics/daily', { params: { days } })
}

/* ==========================================================================
   商品列表
   ========================================================================== */

/** 库存状态归类，供筛选与徽标复用（纯函数，与接口无关） */
export function stockState(product) {
  if (product.stock === 0) return 'out'
  if (product.stock <= product.safeStock) return 'warn'
  if (product.stock > product.safeStock * 2) return 'plenty'
  return 'normal'
}

/**
 * 商品分页列表。
 * @param {object} params status/cat/brand/stock/keyword/sort/page/size
 */
export function fetchProductPage(params = {}) {
  return request.get('/merchant/product/page', { params })
}

/** 筛选下拉：分类 / 品牌 */
export function fetchProductFilters() {
  return request.get('/merchant/product/filters')
}

/** 保存商品（新增或编辑），返回商品 ID */
export function saveProduct(payload) {
  return request.post('/merchant/product/save', payload)
}

/** 变更商品状态（上下架 / 移入回收站 / 恢复），返回受影响条数 */
export function updateProductStatus(ids, status) {
  return request.post('/merchant/product/status', { ids, status })
}

/**
 * 批量改价。
 * @param {string[]} ids 商品 ID
 * @param {'pct'|'amount'} mode 按百分比 / 按固定金额
 * @param {number} value 数值（pct 模式 -10 表示降价 10%）
 */
export function batchUpdatePrice(ids, mode, value) {
  return request.post('/merchant/product/batch-price', { ids, mode, value })
}
