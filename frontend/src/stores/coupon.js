import { defineStore } from 'pinia'
import { couponApi } from '@/api/marketing'
import { hasToken } from '@/api/token'
import { loadState, saveState } from './persist'

/** 优惠券：领券中心列表与我的券都来自服务端 */
export const useCouponStore = defineStore('coupon', {
  state: () => ({
    /** 领券中心模板 */
    templates: [],
    /** 当前筛选类型，领取后用于重新拉取列表 */
    activeType: 'all',
    /** 我的券（未使用） */
    mine: [],
    /** 结算页选中的券，跨页面保留选择 */
    selected: loadState('coupon:selected', '')
  }),

  getters: {
    list: (state) => state.templates,
    claimedCount: (state) => state.mine.length
  },

  actions: {
    async loadTemplates(type = this.activeType) {
      this.activeType = type || 'all'
      this.templates = await couponApi.templates(this.activeType)
    },

    /**
     * 我的券。领券中心是白名单页面，未登录也能浏览，
     * 因此这里必须先判断登录态，否则匿名访问会拿到 401 被弹去登录页。
     */
    async loadMine(status = 0) {
      if (!hasToken()) {
        this.mine = []
        return
      }
      this.mine = await couponApi.mine(status)
    },

    /** 是否已领取：直接读列表里的标记（无需额外请求） */
    isClaimed(id) {
      return !!this.templates.find((t) => t.id === Number(id))?.claimed
    },

    async claim(id) {
      await couponApi.claim(id)
      // 领取后刷新，进度条与「已领取」状态都以后端为准
      await Promise.all([this.loadTemplates(this.activeType), this.loadMine()])
    },

    select(value) {
      this.selected = value
      saveState('coupon:selected', value)
    }
  }
})
