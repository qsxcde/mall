import { beforeEach, describe, expect, it } from 'vitest'
import { clearCheckoutRequestId, getOrCreateCheckoutRequestId } from './checkoutRequestId'

describe('checkoutRequestId', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('同一结算会话内重复获取返回同一个 id', () => {
    const first = getOrCreateCheckoutRequestId()
    const second = getOrCreateCheckoutRequestId()

    expect(first).toBeTruthy()
    expect(second).toBe(first)
  })

  it('下单成功后清除，下一次结算生成新 id', () => {
    const first = getOrCreateCheckoutRequestId()
    clearCheckoutRequestId()
    const second = getOrCreateCheckoutRequestId()

    expect(second).not.toBe(first)
  })
})
