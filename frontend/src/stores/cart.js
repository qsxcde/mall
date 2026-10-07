import { defineStore } from 'pinia'
import cartApi from '@/api/cart'

/**
 * 购物车：数据源改为服务端（登录后跨设备一致），不再本地持久化。
 *
 * 为减少页面改动，对外保持原有方法签名（add / remove / setQty / toggleAll ...），
 * 页面传的 id 仍是「商品 ID」，由 store 内部换算成购物车项主键。
 */
export const useCartStore = defineStore('cart', {
  state: () => ({
    // 每项：{ id(商品ID), cartItemId, title, price, spec, c, qty, checked }
    items: [],
    loading: false
  }),

  getters: {
    itemCount: (state) => state.items.length,
    totalQty: (state) => state.items.reduce((s, i) => s + i.qty, 0),
    checkedItems: (state) => state.items.filter((i) => i.checked),
    checkedQty() {
      return this.checkedItems.reduce((s, i) => s + i.qty, 0)
    },
    checkedAmount() {
      return this.checkedItems.reduce((s, i) => s + i.price * i.qty, 0)
    },
    allChecked: (state) => state.items.length > 0 && state.items.every((i) => i.checked)
  },

  actions: {
    async load() {
      this.loading = true
      try {
        this.items = await cartApi.list()
      } finally {
        this.loading = false
      }
    },

    /** 登录态变化后重置（退出登录要清空，避免串号） */
    reset() {
      this.items = []
    },

    /** 加入购物车：后端对「同商品 + 同规格」会自动累加 */
    async add(product, qty = 1, extra = {}) {
      await cartApi.add(product.id ?? product.productId, qty, extra.spec || product.spec || '')
      await this.load()
    },

    async addBatch(list) {
      await cartApi.addBatch(list)
      await this.load()
    },

    async remove(id) {
      const item = this.items.find((i) => i.id === id)
      if (!item) return
      await cartApi.remove(item.cartItemId)
      await this.load()
    },

    async setQty(id, qty) {
      const item = this.items.find((i) => i.id === id)
      if (!item) return
      const next = Math.max(1, Number(qty) || 1)
      item.qty = next
      await cartApi.updateQty(item.cartItemId, next)
    },

    async toggle(id, checked) {
      const item = this.items.find((i) => i.id === id)
      if (!item) return
      item.checked = checked
      await cartApi.updateChecked(item.cartItemId, checked)
    },

    /** 表格多选变化：只提交真正变动的行，避免全量请求 */
    async syncChecked(rows) {
      const selected = new Set(rows.map((r) => r.id))
      const changed = this.items.filter((i) => i.checked !== selected.has(i.id))
      this.items.forEach((i) => {
        i.checked = selected.has(i.id)
      })
      await Promise.all(changed.map((i) => cartApi.updateChecked(i.cartItemId, i.checked)))
    },

    async toggleAll(checked) {
      this.items.forEach((i) => {
        i.checked = checked
      })
      await cartApi.checkAll(checked)
    },

    async clearChecked() {
      await cartApi.clearChecked()
      await this.load()
    },

    async clear() {
      await cartApi.clear()
      this.items = []
    }
  }
})
