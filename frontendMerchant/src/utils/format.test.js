import { describe, expect, it } from 'vitest'
import { fmtDate, fmtDay, int, money, moneyShort, pad, percent } from '@/utils/format'

/**
 * 商家端格式化工具单测。
 *
 * 商家后台是「看数字做决策」的界面：金额、百分比、日期一旦算错，
 * 运营会据此误判经营状况，而界面上完全看不出错。
 */
describe('pad', () => {
  it('补零到两位', () => {
    expect(pad(1)).toBe('01')
    expect(pad(10)).toBe('10')
  })
})

describe('int', () => {
  it('四舍五入并加千分位', () => {
    expect(int(1234567)).toBe('1,234,567')
    expect(int(1234.6)).toBe('1,235')
  })

  it('空值按 0 处理', () => {
    expect(int(null)).toBe('0')
    expect(int(undefined)).toBe('0')
  })
})

describe('money', () => {
  it('保留两位小数并加千分位', () => {
    expect(money(8999)).toBe('8,999.00')
    expect(money(1234567.891)).toBe('1,234,567.89')
  })

  it('0 显示 0.00', () => {
    expect(money(0)).toBe('0.00')
  })
})

describe('moneyShort', () => {
  it('万位以上用「万」简写（指标卡空间有限）', () => {
    expect(moneyShort(73380)).toBe('¥7.34万')
  })

  it('不足一万保留完整金额', () => {
    expect(moneyShort(8999)).toBe('¥8,999.00')
  })

  it('亿位单位按需启用', () => {
    expect(moneyShort(250000000, '亿')).toBe('¥2.50亿')
  })

  it('负数也按绝对值判断量级，不出现「¥-0.50万」这种怪值', () => {
    expect(moneyShort(-15000)).toBe('¥-1.50万')
  })
})

describe('percent', () => {
  it('小数转百分比并保留指定位数', () => {
    expect(percent(0.927)).toBe('92.7%')
    expect(percent(0.927, 2)).toBe('92.70%')
  })

  it('0 与 1 的边界', () => {
    expect(percent(0)).toBe('0.0%')
    expect(percent(1)).toBe('100.0%')
  })
})

describe('fmtDate / fmtDay', () => {
  it('格式化到分钟', () => {
    expect(fmtDate('2026-10-05T10:20:30')).toBe('2026-10-05 10:20')
  })

  it('withSec 时带秒', () => {
    expect(fmtDate('2026-10-05T10:20:30', true)).toBe('2026-10-05 10:20:30')
  })

  it('空值与非法日期返回破折号（而不是 Invalid Date）', () => {
    expect(fmtDate(null)).toBe('—')
    expect(fmtDate('not-a-date')).toBe('—')
    expect(fmtDay('')).toBe('—')
  })

  it('fmtDay 只到日期', () => {
    expect(fmtDay('2026-10-05T10:20:30')).toBe('2026-10-05')
  })
})
