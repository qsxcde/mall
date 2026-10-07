import request from './request'
import { cleanParams } from './helpers'
import { toAfterSale } from './adapters'

/** 售后：申请 / 列表 / 详情 / 取消 */
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
  }
}

export default afterSaleApi
