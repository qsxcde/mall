import { defineStore } from 'pinia'
import afterSaleApi from '@/api/aftersale'

/** 售后服务（服务端存储） */
export const useAfterSaleStore = defineStore('aftersale', {
  state: () => ({
    records: []
  }),

  getters: {
    // status 是适配层转换后的语义 key：doing / waitReturn / waitReceive / done / canceled / rejected
    doing: (state) => state.records.filter((r) => r.status === 'doing'),
    // 「已完成」把待寄回/待收货也算进去：它们同样通过了商家审核，不是失败态
    inProgress: (state) =>
      state.records.filter((r) => ['waitReturn', 'waitReceive'].includes(r.status)),
    done: (state) => state.records.filter((r) => r.status === 'done'),
    rejected: (state) => state.records.filter((r) => r.status === 'rejected')
  },

  actions: {
    async load(status) {
      this.records = await afterSaleApi.list(status)
    },

    /**
     * 拉取售后详情。
     *
     * 注意返回的是 Promise：调用方必须在自己的组件里 await 后存入 ref，
     * 不能直接 `const x = computed(() => store.find(id))`——那样拿到的是 Promise 对象，
     * 模板里 `x.amount` 会静默变成 undefined。
     */
    fetchDetail(id) {
      return afterSaleApi.detail(id)
    },

    /** 申请售后，返回新建记录（含 id，供详情页路由使用） */
    async create(payload) {
      const id = await afterSaleApi.apply({
        orderNo: payload.orderNo,
        type: payload.type,
        reason: payload.reason,
        content: payload.content || '',
        images: payload.images || [],
        phone: payload.phone || ''
      })
      const record = await afterSaleApi.detail(id)
      this.records.unshift(record)
      return record
    },

    async cancel(id) {
      await afterSaleApi.cancel(id)
      await this.load()
    },

    /**
     * 登记已寄回（退货退款 / 换货 / 维修共用）：待寄回 → 待商家收货。
     *
     * 成功后重新拉一次列表：售后列表页的「处理中」分组会随之把该单挪到「待收货」。
     */
    async markReturned(id, payload) {
      await afterSaleApi.markReturned(id, payload)
      await this.load()
    },

    reset() {
      this.records = []
    }
  }
})
