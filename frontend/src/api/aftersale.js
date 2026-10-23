import request from './request'
import { cleanParams } from './helpers'
import { toAfterSale } from './adapters'

/** 售后：申请 / 列表 / 详情 / 取消 / 登记已寄回 */
export const afterSaleApi = {
  /** 申请售后，返回售后单 ID（对应前端路由 /aftersale/:id） */
  apply(payload) {
    return request.post('/aftersales', payload)
  },

  /** status：0 处理中 / 1 已完成 / 2 已取消，不传返回全部 */
  async list(status) {
    const list = await request.get('/aftersales', { params: cleanParams({ status }) })
    return (list || []).map(toAfterSale)
  },

  async detail(id) {
    return toAfterSale(await request.get(`/aftersales/${id}`))
  },

  cancel(id) {
    return request.post(`/aftersales/${id}/cancel`)
  },

  /**
   * 登记已寄回（退货退款 / 换货 / 维修共用）：待寄回 → 待商家收货。
   *
   * 缺了这个入口，买家寄回后状态永远推进不到 wait_receive，
   * 商家端「确认收货」就成了不可达路径（详见项目完整度报告）。
   *
   * @param {object} [payload] 退货物流（快递公司 returnExpress / 运单号 returnWaybill，均选填）
   */
  markReturned(id, payload) {
    return request.post(`/aftersales/${id}/returned`, payload || {})
  }
}

export default afterSaleApi
