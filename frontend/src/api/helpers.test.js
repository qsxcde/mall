import { describe, expect, it } from 'vitest'
import { cleanParams } from '@/api/helpers'

describe('cleanParams', () => {
  it('去掉 null / undefined / 空串，避免后端收到无意义条件', () => {
    expect(cleanParams({ a: 1, b: null, c: undefined, d: '', e: 0 })).toEqual({ a: 1, e: 0 })
  })

  it('保留 0 与 false：它们是有意义的业务值（status=0 是「待付款」）', () => {
    expect(cleanParams({ status: 0, checked: false })).toEqual({ status: 0, checked: false })
  })

  it('无参调用返回空对象', () => {
    expect(cleanParams()).toEqual({})
  })

  it('不修改入参（避免调用方的对象被悄悄改写）', () => {
    const input = { a: 1, b: null }
    cleanParams(input)
    expect(input).toEqual({ a: 1, b: null })
  })
})
