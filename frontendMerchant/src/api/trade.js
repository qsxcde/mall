/**
 * 交易域 API：订单 / 发货 / 售后。
 *
 * 过滤、排序、分页全部由服务端完成，这一层只做两件事：
 *   1. 透传查询参数（参数名与后端 DTO 字段一一对应）
 *   2. 用 adapters 把出参整成视图可直接渲染的形状
 */
import request from './request'
import { adaptAftersale, adaptOrder, adaptPage, adaptShipment } from './adapters'

/* ==========================================================================
   时效
   ========================================================================== */

/**
 * 距发货超时的剩余小时数；非待发货返回 null。
 *
 * 优先用服务端的计算值（服务端以 pay_time + 24h 为准，不受客户端时钟影响）；
 * 兜底时才用 deadline 现算，保证接口字段缺失时列表不至于显示空白。
 */
export const hoursLeft = (s) => {
  if (!s) return null
  if (s.hoursLeft != null) return s.hoursLeft
  if (s.status !== 'wait' || !s.deadline) return null
  return (new Date(s.deadline) - Date.now()) / 3600000
}

/** 是否进入超时预警（≤6 小时） */
export const isLate = (s) => {
  const h = hoursLeft(s)
  return h != null && h <= 6
}

/* ==========================================================================
   订单
   ========================================================================== */

/**
 * 订单分页列表。
 * @param {object} params status/keyword/range/payWay/amountRange/sort/page/size
 */
export async function fetchOrderPage(params = {}) {
  const res = await request.get('/merchant/order/page', { params })
  return adaptPage(res, adaptOrder)
}

/** 订单详情 */
export function fetchOrderDetail(id) {
  return request.get('/merchant/order/detail', { params: { id } })
}

/** 发货：待发货 → 待收货，同时写入快递公司与运单号。返回受影响订单数。 */
export function shipOrders(ids, payload = {}) {
  return request.post('/merchant/order/ship', { ids, ...payload })
}

/** 关闭订单（待付款 / 待发货可关闭）。返回受影响订单数。 */
export function closeOrders(ids, reason = '') {
  return request.post('/merchant/order/close', { ids, reason })
}

/** 保存商家备注 */
export function saveOrderNote(id, note) {
  return request.post('/merchant/order/note', { id, note })
}

/* ==========================================================================
   发货
   ========================================================================== */

/**
 * 发货单分页列表。
 * @param {object} params status/keyword/express/warehouse/sort/page/size
 */
export async function fetchShipmentPage(params = {}) {
  const res = await request.get('/merchant/shipment/page', { params })
  return adaptPage(res, adaptShipment)
}

/** 承运商 / 发货仓下拉选项 */
export function fetchShipmentFilters() {
  return request.get('/merchant/shipment/filters')
}

/** 打单：待打单 → 已打单，并生成运单号。返回受影响单数。 */
export function printShipments(ids) {
  return request.post('/merchant/shipment/print', { ids })
}

/** 确认发货：已打单 → 已发货。返回受影响单数。 */
export function deliverShipments(ids) {
  return request.post('/merchant/shipment/deliver', { ids })
}

/* ==========================================================================
   售后
   ========================================================================== */

/**
 * 售后工单分页列表。
 * @param {object} params status/type/keyword/sort/page/size
 */
export async function fetchAftersalePage(params = {}) {
  const res = await request.get('/merchant/aftersale/page', { params })
  return adaptPage(res, adaptAftersale)
}

/** 售后工单详情 */
export function fetchAftersaleDetail(id) {
  return request.get('/merchant/aftersale/detail', { params: { id } })
}

/**
 * 处理售后工单。
 *
 * @param {string[]} ids 工单号
 * @param {'approve'|'reject'|'receive'|'reaudit'} action
 * @param {object} payload 可携带 { refundAmount, rejectReason, address }
 * @returns {Promise<number>} 受影响工单数
 */
export function resolveAftersale(ids, action, payload = {}) {
  return request.post('/merchant/aftersale/resolve', { ids, action, ...payload })
}
