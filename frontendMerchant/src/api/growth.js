/**
 * 增长与财务域 API：营销活动 / 优惠券 / 评价 / 结算。
 *
 * 聚合口径全部由服务端保证（例如「实结 = 成交额 - 佣金 - 服务费 - 退款」），
 * 前端不再自行推导，避免同一笔钱在两个页面算出两个数。
 */
import request from './request'
import { adaptPage, adaptReview } from './adapters'

/* ==========================================================================
   营销
   ========================================================================== */

/**
 * 营销活动分页列表。
 * @param {object} params status/type/keyword/sort/page/size
 */
export function fetchPromoPage(params = {}) {
  return request.get('/merchant/promotion/page', { params })
}

/** 推广渠道效果（依赖广告投放回传，未接入时返回空数组） */
export function fetchPromoChannels() {
  return request.get('/merchant/promotion/channels')
}

/** 店铺优惠券列表 */
export function fetchCoupons() {
  return request.get('/merchant/coupon/list')
}

/* ==========================================================================
   评价
   ========================================================================== */

/**
 * 评价分页列表。
 * @param {object} params tab/keyword/sort/page/size
 * tab 支持：all / wait / replied / bad / good / pic
 */
export async function fetchReviewPage(params = {}) {
  const res = await request.get('/merchant/review/page', { params })
  return adaptPage(res, adaptReview)
}

/** 店铺 DSR 与评价标签 */
export function fetchReviewSummary() {
  return request.get('/merchant/review/summary')
}

/** 回复评价 */
export function replyReview(id, content) {
  return request.post('/merchant/review/reply', { id, content })
}

/** 忽略评价，返回受影响条数 */
export function ignoreReviews(ids) {
  return request.post('/merchant/review/ignore', { ids })
}

/* ==========================================================================
   财务结算
   ========================================================================== */

/** 资金总览 */
export function fetchFundSummary() {
  return request.get('/merchant/fund/summary')
}

/**
 * 结算单分页列表。
 * @param {object} params status/page/size
 */
export function fetchSettlementPage(params = {}) {
  return request.get('/merchant/settlement/page', { params })
}

/**
 * 资金流水。
 * @param {object} params type/page/size
 */
export function fetchCashFlow(params = {}) {
  return request.get('/merchant/fund/flow', { params })
}

/** 结算单明细（抽样订单） */
export function fetchSettleOrders(settlementId) {
  return request.get('/merchant/settlement/orders', { params: { id: settlementId } })
}

/**
 * 提现申请。
 *
 * @param {number} amount 提现金额（元）
 * @param {string} requestId 提现请求号（幂等键）。
 *   同一笔提现的重复提交必须复用同一个值，否则服务端无法区分
 *   「用户真想提两笔」和「同一笔被提交了两次」，会重复扣款。
 * @returns {Promise<{amount: number, fee: number, arrival: number}>}
 */
export function applyWithdraw(amount, requestId) {
  return request.post('/merchant/fund/withdraw', { amount, requestId })
}
