import request from './request'
import { cleanParams } from './helpers'
import { toPage, toReview } from './adapters'

/** 评价：发表 / 我的评价 / 商品评价 */
export const reviewApi = {
  /** 三维评分 + 图文，提交后订单会自动流转为「已完成」 */
  submit(payload) {
    return request.post('/reviews', payload)
  },

  async mine() {
    const list = await request.get('/user/reviews')
    return (list || []).map(toReview)
  },

  remove(id) {
    return request.delete(`/user/reviews/${id}`)
  },

  async byProduct(productId, params = {}) {
    const data = await request.get(`/products/${productId}/reviews`, { params: cleanParams(params) })
    return toPage(data, toReview)
  }
}

export default reviewApi
