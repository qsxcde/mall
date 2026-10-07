import request from './request'
import { cleanParams } from './helpers'
import { toCartItem, toLogistics, toOrder, toOrderDetail, toPage, toTimeline, toUserCoupon } from './adapters'

/** 交易：结算 / 下单 / 订单 / 支付 */
export const tradeApi = {
  /**
   * 结算试算：商品、金额、地址、配送方式、可用券、支付方式。
   *
   * 切换配送方式或优惠券后重新调用，金额始终由服务端给出，
   * 避免前端自己拼算出与下单不一致的价格。
   */
  async preOrder({ cartItemIds = [], shippingType, couponId } = {}) {
    const data = await request.post('/trade/pre-order', { cartItemIds, shippingType, couponId })
    return {
      items: (data.items || []).map(toCartItem),
      goodsAmount: Number(data.goodsAmount || 0),
      shippingFee: Number(data.shippingFee || 0),
      discount: Number(data.discount || 0),
      payTotal: Number(data.payTotal || 0),
      addresses: (data.addresses || []).map((a) => ({
        id: a.id,
        name: a.name,
        phone: a.phone,
        region: [a.province, a.city, a.district].filter(Boolean).join(' '),
        detail: a.detail,
        isDefault: a.isDefault === 1
      })),
      defaultAddressId: data.defaultAddressId,
      shippingOptions: (data.shippingOptions || []).map((o) => ({
        value: o.value,
        label: o.label,
        amount: Number(o.fee || 0),
        fee: Number(o.fee || 0) > 0 ? `+¥${Number(o.fee)}` : '免运费'
      })),
      paymentOptions: (data.paymentOptions || []).map((o) => ({ value: o.value, label: o.label })),
      coupons: (data.coupons || []).map(toUserCoupon),
      selectedShippingType: data.selectedShippingType || 'standard',
      selectedCouponId: data.selectedCouponId ?? null,
      couponNotice: data.couponNotice || ''
    }
  },

  /** 提交订单：幂等（requestId），返回订单号 */
  submit(payload) {
    return request.post('/trade/orders', payload)
  },

  cancel(orderNo, reason = '') {
    return request.post(`/trade/orders/${orderNo}/cancel`, { reason })
  },

  confirm(orderNo) {
    return request.post(`/trade/orders/${orderNo}/confirm`)
  },

  remind(orderNo) {
    return request.post(`/trade/orders/${orderNo}/remind`)
  },

  /** 仅 dev 可用的模拟发货，用于打通「收货 → 评价」链路 */
  mockShip(orderNo) {
    return request.post(`/trade/mock/ship/${orderNo}`, null, { silent: true })
  },

  /* ---------- 订单查询 ---------- */

  async orders(params = {}) {
    const data = await request.get('/orders', { params: cleanParams(params) })
    return toPage(data, toOrder)
  },

  statusCounts() {
    return request.get('/orders/status-counts')
  },

  async orderDetail(orderNo) {
    return toOrderDetail(await request.get(`/orders/${orderNo}`))
  },

  async logistics(orderNo) {
    return toLogistics(await request.get(`/orders/${orderNo}/logistics`))
  }
}

/** 支付：创建 / 查询 / 模拟支付 */
export const paymentApi = {
  create(orderNo, payMethod) {
    return request.post('/pay/create', { orderNo, payMethod })
  },

  status(tradeNo) {
    return request.get(`/pay/${tradeNo}/status`)
  },

  /** 演示用「我已支付」，接入真实渠道后由渠道回调替代 */
  mockPay(tradeNo) {
    return request.post(`/pay/${tradeNo}/mock-pay`)
  }
}

export { toTimeline }

export default tradeApi
