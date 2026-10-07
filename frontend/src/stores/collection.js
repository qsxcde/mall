import { defineStore } from 'pinia'
import userApi from '@/api/user'
import { hasToken } from '@/api/token'

/** 我的收藏 + 浏览足迹（服务端存储） */
export const useCollectionStore = defineStore('collection', {
  state: () => ({
    favorites: [],
    history: [],
    loaded: false
  }),

  getters: {
    favoriteCount: (state) => state.favorites.length,
    historyCount: (state) => state.history.length,
    isFavorite: (state) => (id) => state.favorites.some((p) => p.id === Number(id))
  },

  actions: {
    async load() {
      if (!hasToken()) {
        // 收藏/足迹只在登录后才有意义，未登录保持空
        this.favorites = []
        this.history = []
        return
      }
      const [favorites, history] = await Promise.all([userApi.favorites(), userApi.history()])
      this.favorites = favorites
      this.history = history
    },

    async loadFavorites() {
      this.favorites = await userApi.favorites()
    },

    async loadHistory() {
      this.history = await userApi.history()
    },

    /** 收藏切换，返回 true 表示现在处于已收藏状态 */
    async toggleFavorite(id) {
      const nowFavorite = await userApi.toggleFavorite(id)
      if (nowFavorite) {
        await this.loadFavorites()
      } else {
        this.favorites = this.favorites.filter((p) => p.id !== Number(id))
      }
      return nowFavorite
    },

    async removeFavorite(id) {
      await userApi.removeFavorite(id)
      this.favorites = this.favorites.filter((p) => p.id !== Number(id))
    },

    async clearFavorites() {
      await userApi.clearFavorites()
      this.favorites = []
    },

    /**
     * 记录浏览足迹。
     *
     * 未登录直接跳过：这笔埋点走的是需要鉴权的接口，匿名调用会拿到 401
     * 进而被拦截器重定向到登录页——逛商品详情不该被强制登录。
     */
    addHistory(id) {
      if (!hasToken()) {
        return Promise.resolve()
      }
      return userApi.addHistory(id).catch(() => {})
    },

    async removeHistory(id) {
      await userApi.removeHistory(id)
      this.history = this.history.filter((p) => p.id !== Number(id))
    },

    async clearHistory() {
      await userApi.clearHistory()
      this.history = []
    },

    reset() {
      this.favorites = []
      this.history = []
    }
  }
})
