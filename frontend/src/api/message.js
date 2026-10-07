import request from './request'
import { cleanParams } from './helpers'
import { toMessage } from './adapters'

/** 站内消息 */
export const messageApi = {
  /** type：all / order / logistics / coupon / system */
  async list(type) {
    const list = await request.get('/messages', { params: cleanParams({ type }) })
    return (list || []).map(toMessage)
  },

  unreadCount() {
    return request.get('/messages/unread-count')
  },

  markRead(id) {
    return request.post(`/messages/${id}/read`)
  },

  markAllRead() {
    return request.post('/messages/read-all')
  }
}

export default messageApi
