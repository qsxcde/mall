import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// 只关心 store 编排（调哪个接口、成功后是否刷新列表），接口本身用桩替代
vi.mock('@/api/aftersale', () => ({
  default: {
    list: vi.fn(),
    detail: vi.fn(),
    apply: vi.fn(),
    cancel: vi.fn(),
    markReturned: vi.fn()
  }
}))

import afterSaleApi from '@/api/aftersale'
import { useAfterSaleStore } from '@/stores/aftersale'

describe('售后 store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('登记已寄回：调用 /aftersales/{id}/returned（退货物流透传），并刷新列表', async () => {
    afterSaleApi.markReturned.mockResolvedValue(undefined)
    afterSaleApi.list.mockResolvedValue([{ id: 7, status: 'waitReceive' }])

    const store = useAfterSaleStore()
    const payload = { returnExpress: '顺丰速运', returnWaybill: 'SF1234567890' }
    await store.markReturned(7, payload)

    expect(afterSaleApi.markReturned).toHaveBeenCalledWith(7, payload)
    // 列表必须重新拉：售后页的「待寄回 / 待收货」分组依赖它
    expect(afterSaleApi.list).toHaveBeenCalledTimes(1)
    expect(store.records).toHaveLength(1)
    expect(store.inProgress.map((r) => r.id)).toEqual([7])
  })

  it('登记已寄回失败时不再刷新列表（避免把错误状态渲染成已提交）', async () => {
    afterSaleApi.markReturned.mockRejectedValue(new Error('工单状态已变更，请刷新后重试'))

    const store = useAfterSaleStore()
    await expect(store.markReturned(7)).rejects.toThrow('工单状态已变更')

    expect(afterSaleApi.list).not.toHaveBeenCalled()
  })

  it('取消售后后刷新列表', async () => {
    afterSaleApi.cancel.mockResolvedValue(undefined)
    afterSaleApi.list.mockResolvedValue([])

    const store = useAfterSaleStore()
    await store.cancel(3)

    expect(afterSaleApi.cancel).toHaveBeenCalledWith(3)
    expect(afterSaleApi.list).toHaveBeenCalledTimes(1)
  })

  it('待寄回 / 待收货都算「处理中」，待寄回不在 doing 里（doing 只表示商家审核中）', async () => {
    afterSaleApi.list.mockResolvedValue([
      { id: 1, status: 'doing' },
      { id: 2, status: 'waitReturn' },
      { id: 3, status: 'waitReceive' },
      { id: 4, status: 'done' },
      { id: 5, status: 'rejected' }
    ])

    const store = useAfterSaleStore()
    await store.load()

    expect(store.doing.map((r) => r.id)).toEqual([1])
    expect(store.inProgress.map((r) => r.id)).toEqual([2, 3])
    expect(store.done.map((r) => r.id)).toEqual([4])
    expect(store.rejected.map((r) => r.id)).toEqual([5])
  })
})
