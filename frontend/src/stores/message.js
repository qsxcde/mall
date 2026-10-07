import { defineStore } from 'pinia'
import messageApi from '@/api/message'
import { hasToken } from '@/api/token'

/** 站内消息（服务端存储，已读状态按用户维度记录） */
export const useMessageStore = defineStore('message', {
  state: () => ({
    list: [],
    /** 未读数单独维护，顶栏红点无需加载整个列表 */
    unreadCount: 0
  }),

  getters: {
    unread: (state) => state.unreadCount,
    byType: (state) => (type) => (type === 'all' ? state.list : state.list.filter((m) => m.type === type))
  },

  actions: {
    async load() {
      this.list = await messageApi.list()
      this.unreadCount = this.list.filter((m) => !m.read).length
    },

    /** 顶栏只用未读数，单独请求更轻 */
    async loadUnread() {
      if (!hasToken()) {
        this.unreadCount = 0
        return
      }
      this.unreadCount = await messageApi.unreadCount()
    },

    async markRead(id) {
      const message = this.list.find((m) => m.id === Number(id))
      if (!message || message.read) return
      message.read = true
      this.unreadCount = Math.max(0, this.unreadCount - 1)
      await messageApi.markRead(id)
    },

    async markAllRead() {
      this.list.forEach((m) => {
        m.read = true
      })
      this.unreadCount = 0
      await messageApi.markAllRead()
    }
  }
})
