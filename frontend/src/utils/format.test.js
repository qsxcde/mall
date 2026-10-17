import { describe, expect, it } from 'vitest'
import { fmtCountdown, fmtDate, fmtMoney, fmtTime } from '@/utils/format'

/**
 * 格式化工具单测。
 *
 * 这些函数被几十处模板直接调用，一旦出错是「满屏显示错误」级别的影响，
 * 而且它们的边界（null / 0 / 负数 / 小数进位）最容易被忽略。
 */
describe('fmtMoney', () => {
  it('保留两位小数并加千分位', () => {
    expect(fmtMoney(1234.5)).toBe('1,234.50')
    expect(fmtMoney(1234567.891)).toBe('1,234,567.89')
  })

  it('0 显示为 0.00，而不是空', () => {
    expect(fmtMoney(0)).toBe('0.00')
  })

  it('null / undefined / 空串都按 0 处理（避免 "¥undefined"）', () => {
    expect(fmtMoney(null)).toBe('0.00')
    expect(fmtMoney(undefined)).toBe('0.00')
    expect(fmtMoney('')).toBe('0.00')
  })

  it('字符串数字也能格式化', () => {
    expect(fmtMoney('88.8')).toBe('88.80')
  })

  it('负数保留负号并正确分组', () => {
    expect(fmtMoney(-1234.5)).toBe('-1,234.50')
  })
})

describe('fmtDate', () => {
  it('截取日期部分', () => {
    expect(fmtDate('2026-10-05 12:30:00')).toBe('2026-10-05')
  })

  it('空值返回空串', () => {
    expect(fmtDate(null)).toBe('')
    expect(fmtDate('')).toBe('')
  })
})

describe('fmtTime', () => {
  it('有时间返回时间，无时间返回占位符', () => {
    expect(fmtTime('2026-10-05')).toBe('2026-10-05')
    expect(fmtTime('', '—')).toBe('—')
    expect(fmtTime(null)).toBe('')
  })
})

describe('fmtCountdown', () => {
  it('秒数转 mm:ss 并补零', () => {
    expect(fmtCountdown(65)).toBe('01:05')
    expect(fmtCountdown(5)).toBe('00:05')
    expect(fmtCountdown(0)).toBe('00:00')
  })

  it('负数夹到 0，不出现 -01:-5 这种脏值（订单过期后倒计时会跑成负数）', () => {
    expect(fmtCountdown(-30)).toBe('00:00')
  })

  it('超过一小时的秒数按分钟累积显示', () => {
    expect(fmtCountdown(3725)).toBe('62:05')
  })

  it('非法值按 0 处理', () => {
    expect(fmtCountdown('abc')).toBe('00:00')
    expect(fmtCountdown(null)).toBe('00:00')
  })
})
