import request from './request'
import { toCartItem } from './adapters'

/** 购物车：服务端存储，登录后跨设备一致 */
export const cartApi = {
  async list() {
    const list = await request.get('/cart/items')
    return (list || []).map(toCartItem)
  },

  /** 加入购物车（同商品同规格后端会自动累加） */
  async add(productId, qty = 1, spec = '') {
    return request.post('/cart/items', { productId, qty, spec })
  },

  async addBatch(items) {
    return request.post('/cart/items/batch', items)
  },

  async updateQty(cartItemId, qty) {
    return request.put(`/cart/items/${cartItemId}/qty`, { qty })
  },

  async updateChecked(cartItemId, checked) {
    return request.put(`/cart/items/${cartItemId}/checked`, { checked })
  },

  async remove(cartItemId) {
    return request.delete(`/cart/items/${cartItemId}`)
  },

  async clear() {
    return request.delete('/cart/items')
  },

  async checkAll(checked) {
    return request.put('/cart/checked', { checked })
  },

  async clearChecked() {
    return request.delete('/cart/checked')
  },

  async summary() {
    return request.get('/cart/summary')
  }
}

export default cartApi
