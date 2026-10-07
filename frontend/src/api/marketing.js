import request from './request'
import { cleanParams } from './helpers'
import {
  toCouponTemplate,
  toPointsGoods,
  toSeckillGrabResult,
  toSeckillItem,
  toSeckillSession,
  toUserCoupon
} from './adapters'

/** 优惠券：领券中心 / 领取 / 我的券 */
export const couponApi = {
  /**
   * 领券中心列表。
   *
   * 注意：'all' 是前端的 Tab 概念，不能直接透传给后端——后端是按 type 精确过滤的，
   * 传 'all' 会得到空列表。这里统一转成「不传该参数」。
   */
  async templates(type) {
    const list = await request.get('/coupons/templates', {
      params: cleanParams({ type: type === 'all' ? '' : type })
    })
    return (list || []).map(toCouponTemplate)
  },

  claim(templateId) {
    return request.post(`/coupons/claim/${templateId}`)
  },

  /** status：0 未使用 / 1 已使用 / 2 已过期，不传返回全部 */
  async mine(status) {
    const list = await request.get('/coupons/mine', { params: cleanParams({ status }) })
    return (list || []).map(toUserCoupon)
  }
}

/** 秒杀 */
export const seckillApi = {
  async sessions() {
    const list = await request.get('/seckill/sessions')
    return (list || []).map(toSeckillSession)
  },

  async items(sessionId) {
    const list = await request.get('/seckill/items', { params: cleanParams({ sessionId }) })
    return (list || []).map(toSeckillItem)
  },

  /**
   * 抢购。
   *
   * 返回值取决于后端的 `mall.seckill.async.enabled`：
   * - 关闭（默认）：直接返回待付款订单号（GM 开头）
   * - 开启（削峰）：返回 32 位抢购请求号，需再调 `grabResult` 轮询最终结果
   * 两种模式成功码都是 0，前端按返回值格式区分即可，无需额外的模式开关。
   */
  grab(itemId, addressId) {
    return request.post(`/seckill/${itemId}/order`, { addressId })
  },

  /**
   * 轮询抢购结果（仅削峰模式需要）。
   *
   * silent：轮询期间的瞬时失败不弹提示 —— 否则一次网络抖动会连弹多条错误，
   * 也会把「排队中」这种并非失败的中间态渲染成报错。
   */
  async grabResult(requestId) {
    const res = await request.get(`/seckill/result/${encodeURIComponent(requestId)}`, { silent: true })
    return toSeckillGrabResult(res)
  }
}

/** 积分商城 */
export const pointsApi = {
  async goods() {
    const list = await request.get('/points/goods')
    return (list || []).map(toPointsGoods)
  },

  exchange(goodsId) {
    return request.post(`/points/exchange/${goodsId}`)
  },

  records() {
    return request.get('/points/records')
  }
}

export default { couponApi, seckillApi, pointsApi }
