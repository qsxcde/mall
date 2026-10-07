import request from './request'
import { cleanParams } from './helpers'
import { toAddress, toProduct, toProducts, toUserProfile } from './adapters'

/** 用户中心 / 地址 / 积分 / 会员 / 收藏 / 足迹 */
export const userApi = {
  async profile() {
    return toUserProfile(await request.get('/user/profile'))
  },

  updateProfile(payload) {
    return request.put('/user/profile', payload)
  },

  async addresses() {
    const list = await request.get('/user/addresses')
    return (list || []).map(toAddress)
  },

  addAddress(payload) {
    return request.post('/user/addresses', payload)
  },

  updateAddress(id, payload) {
    return request.put(`/user/addresses/${id}`, payload)
  },

  removeAddress(id) {
    return request.delete(`/user/addresses/${id}`)
  },

  setDefaultAddress(id) {
    return request.put(`/user/addresses/${id}/default`)
  },

  signIn() {
    return request.post('/user/sign-in')
  },

  signStatus() {
    return request.get('/user/sign-in')
  },

  /* ---------- 我的收藏 ---------- */

  async favorites() {
    return toProducts(await request.get('/user/favorites'))
  },

  async toggleFavorite(productId) {
    return request.post(`/user/favorites/${productId}/toggle`)
  },

  removeFavorite(productId) {
    return request.delete(`/user/favorites/${productId}`)
  },

  clearFavorites() {
    return request.delete('/user/favorites')
  },

  /* ---------- 浏览足迹 ---------- */

  async history() {
    const list = await request.get('/user/history')
    return (list || []).map((vo) => ({ ...toProduct(vo), time: vo.viewTime || '' }))
  },

  addHistory(productId) {
    return request.post(`/user/history/${productId}`, null, { silent: true })
  },

  removeHistory(productId) {
    return request.delete(`/user/history/${productId}`)
  },

  clearHistory() {
    return request.delete('/user/history')
  },

  /* ---------- 会员 ---------- */

  memberInfo(params) {
    return request.get('/member/info', { params: cleanParams(params) })
  }
}

export default userApi
