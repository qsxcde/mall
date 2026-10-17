import { describe, expect, it } from 'vitest'
import {
  placeholderClass,
  toAfterSale,
  toCartItem,
  toOrder,
  toOrderItem,
  toPage,
  toProduct,
  toTimeline
} from '@/api/adapters'

/**
 * 适配层单测。
 *
 * 这一层是「后端字段 → 视图字段」的唯一收敛点：后端改字段名、加状态值，
 * 坏掉的不是某一页，而是所有用错字段的页面。此前完全没有测试，
 * 只能靠手工点页面发现 —— 这里把契约固定下来。
 */
describe('toProduct', () => {
  it('把字符串数字归一为 Number，避免模板里出现 "199.00" + 1 的拼接', () => {
    const p = toProduct({ id: 7, title: '手机', price: '199.00', sales: '12' })
    expect(p.price).toBe(199)
    expect(p.sales).toBe(12)
  })

  it('缺失或非法价格回落到 0，而不是 NaN（NaN 会渲染成 "¥NaN"）', () => {
    expect(toProduct({ id: 1 }).price).toBe(0)
    expect(toProduct({ id: 1, price: 'abc' }).price).toBe(0)
  })

  it('oldPrice 为 null 时保持 null：有「原价划线」与「没有原价」是两种展示', () => {
    expect(toProduct({ id: 1, price: 10, oldPrice: null }).oldPrice).toBeNull()
    expect(toProduct({ id: 1, price: 10, oldPrice: '20' }).oldPrice).toBe(20)
  })

  it('rating 缺省为 5，避免空星', () => {
    expect(toProduct({ id: 1 }).rating).toBe(5)
  })

  it('入参为 null 时返回 null，而不是抛错（列表里可能有脏数据）', () => {
    expect(toProduct(null)).toBeNull()
  })
})

describe('placeholderClass', () => {
  it('同一 ID 永远得到同一配色（否则列表每次渲染颜色都在跳）', () => {
    expect(placeholderClass(42)).toBe(placeholderClass(42))
  })

  it('结果始终落在 c1~c6 之间', () => {
    for (let id = 0; id < 50; id += 1) {
      expect(placeholderClass(id)).toMatch(/^c[1-6]$/)
    }
  })

  it('负数与非法值不会越界（Math.abs / fallback 0）', () => {
    expect(placeholderClass(-3)).toMatch(/^c[1-6]$/)
    expect(placeholderClass('abc')).toMatch(/^c[1-6]$/)
    expect(placeholderClass(null)).toMatch(/^c[1-6]$/)
  })
})

describe('toPage', () => {
  it('缺省分页参数用默认值填充', () => {
    const page = toPage({})
    expect(page).toEqual({ list: [], total: 0, page: 1, pageSize: 8 })
  })

  it('对 list 逐项应用 mapper', () => {
    const page = toPage({ list: [{ id: 1, price: '5' }], total: 1 }, toProduct)
    expect(page.list[0].price).toBe(5)
    expect(page.total).toBe(1)
  })
})

describe('toOrder', () => {
  it('按后端状态码映射出展示 key 与文案', () => {
    const order = toOrder({ no: 'GM1', status: 1 })
    expect(order.status).toBe('ship')
    expect(order.statusText).toBe('待发货')
    expect(order.statusCls).toBe('ship')
  })

  it('未知状态码回落到 unknown，不抛错（后端加状态时前端不至于白屏）', () => {
    const order = toOrder({ no: 'GM1', status: 99 })
    expect(order.status).toBe('unknown')
  })

  it('按钮可用性完全取自后端，前端不自行推导规则', () => {
    const order = toOrder({ no: 'GM1', status: 0, canCancel: true, canPay: true })
    expect(order.canCancel).toBe(true)
    expect(order.canPay).toBe(true)
    // 后端没给的字段一律 false，避免出现「后端说不能、前端却显示按钮」
    expect(order.canReview).toBe(false)
    expect(order.canAfterSale).toBe(false)
  })

  it('列表扁平字段与 items 同时存在，兼容新旧模板', () => {
    const order = toOrder({
      no: 'GM1',
      status: 2,
      productId: 9,
      productTitle: '耳机',
      spec: '黑色',
      price: '99',
      qty: 2,
      items: [{ productId: 9, title: '耳机', price: '99', qty: 2 }]
    })
    expect(order.product.title).toBe('耳机')
    expect(order.items).toHaveLength(1)
    expect(order.items[0].price).toBe(99)
  })

  it('入参为 null 返回 null', () => {
    expect(toOrder(null)).toBeNull()
  })
})

describe('toOrderItem', () => {
  it('qty 缺省为 1，避免数量显示成 0', () => {
    expect(toOrderItem({ productId: 1, price: 10 }).qty).toBe(1)
  })
})

describe('toAfterSale', () => {
  it('商家拒绝优先于数字状态：status=2 也可能是「商家拒绝」而非「已取消」', () => {
    const item = toAfterSale({
      id: 1,
      status: 2,
      merchantStatus: 'rejected',
      rejectReason: '已激活'
    })
    expect(item.status).toBe('rejected')
    expect(item.statusCode).toBe(2)
    expect(item.rejectReason).toBe('已激活')
  })

  it('买家撤销（merchantStatus 为空 + status=2）→ canceled', () => {
    expect(toAfterSale({ id: 1, status: 2 }).status).toBe('canceled')
  })

  it('退货流程的中间态映射为 waitReturn / waitReceive', () => {
    expect(toAfterSale({ id: 1, status: 0, merchantStatus: 'wait_return' }).status).toBe(
      'waitReturn'
    )
    expect(toAfterSale({ id: 1, status: 0, merchantStatus: 'wait_receive' }).status).toBe(
      'waitReceive'
    )
  })

  it('商家已同意但买家还没寄回时，撤销按钮可用性由后端 canCancel 决定', () => {
    const item = toAfterSale({ id: 1, status: 0, merchantStatus: 'wait_return', canCancel: true })
    expect(item.canCancel).toBe(true)
  })

  it('canCancel 缺省为 false（未给就是不可操作，避免误删）', () => {
    expect(toAfterSale({ id: 1, status: 0 }).canCancel).toBe(false)
  })

  it('statusText / statusTip 原样透传，前端不硬编码文案', () => {
    const item = toAfterSale({ id: 1, status: 0, statusText: '待寄回', statusTip: '请寄回商品' })
    expect(item.statusText).toBe('待寄回')
    expect(item.statusTip).toBe('请寄回商品')
  })
})

describe('toCartItem', () => {
  it('id 保持商品 ID 语义，另存 cartItemId（= 后端行 id）供服务端操作', () => {
    const item = toCartItem({ id: 99, productId: 11, price: '1' })
    expect(item.id).toBe(11)
    expect(item.cartItemId).toBe(99)
    expect(item.productId).toBe(11)
  })

  it('后端行 id 缺失时 cartItemId 为 undefined，调用方据此能识别出「未同步到服务端」', () => {
    expect(toCartItem({ productId: 11 }).cartItemId).toBeUndefined()
  })
})

describe('toTimeline', () => {
  it('非数组入参返回空数组，不抛错', () => {
    expect(toTimeline(null)).toEqual([])
    expect(toTimeline(undefined)).toEqual([])
    expect(toTimeline('x')).toEqual([])
  })

  it('done 归一为布尔值：后端可能给 0/1', () => {
    const steps = toTimeline([{ text: '已发货', done: 1 }, { text: '待收货' }])
    expect(steps[0].done).toBe(true)
    expect(steps[1].done).toBe(false)
  })
})
