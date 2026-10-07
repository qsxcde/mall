import request from './request'
import { cleanParams } from './helpers'
import {
  toCouponTemplate,
  toPointsGoods,
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

  /** 抢购：成功返回订单号 */
  grab(itemId, addressId) {
    return request.post(`/seckill/${itemId}/order`, { addressId })
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
