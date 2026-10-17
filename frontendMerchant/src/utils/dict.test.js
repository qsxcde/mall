import { describe, expect, it } from 'vitest'
import { pick, AFTERSALE_STATUS, ORDER_STATUS, PRODUCT_STATUS } from '@/utils/dict'

/**
 * 业务字典单测。
 *
 * 字典是「后端状态码 → 界面文案/颜色」的唯一映射点。它一旦缺项，
 * 页面不会报错，只会显示成灰色「—」，运营看到的是「状态未知」而不是「功能坏了」——
 * 这种沉默失败正是最需要测试兜住的地方。
 */
describe('pick', () => {
  it('命中字典时返回对应项', () => {
    expect(pick(ORDER_STATUS, 'wait_ship').text).toBe('待发货')
  })

  it('未知 key 回落到 neutral 占位，而不是 undefined（否则模板读 .text 会报错）', () => {
    const item = pick(ORDER_STATUS, 'some_new_status')
    expect(item.tone).toBe('neutral')
    expect(item.text).toBe('some_new_status')
  })

  it('key 为 null 时显示破折号，而不是 "null"', () => {
    expect(pick(ORDER_STATUS, null).text).toBe('—')
  })
})

describe('字典完整性', () => {
  it('每个字典项都必须有 text 与 tone（视图只认这两个字段）', () => {
    for (const [name, dict] of Object.entries({ ORDER_STATUS, PRODUCT_STATUS, AFTERSALE_STATUS })) {
      for (const [key, item] of Object.entries(dict)) {
        expect(item.text, `${name}.${key} 缺少 text`).toBeTruthy()
        expect(item.tone, `${name}.${key} 缺少 tone`).toBeTruthy()
      }
    }
  })

  it('售后状态必须区分「商家拒绝 rejected」与「买家撤销 canceled」', () => {
    // 这两者合并会导致：买家看到「已取消」，但其实是商家驳回了他
    expect(AFTERSALE_STATUS.rejected).toBeDefined()
    expect(AFTERSALE_STATUS.canceled).toBeDefined()
    expect(AFTERSALE_STATUS.rejected.text).not.toBe(AFTERSALE_STATUS.canceled.text)
  })

  it('退货退款的两个中间态都有独立文案', () => {
    expect(AFTERSALE_STATUS.wait_return.text).toContain('退货')
    expect(AFTERSALE_STATUS.wait_receive.text).toContain('收货')
  })
})
