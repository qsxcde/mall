/**
 * 通用格式化工具。
 * 全部为纯函数，不含副作用，便于在组件与 Mock 层复用。
 */

/** 补零：1 -> '01' */
export const pad = (n) => (n < 10 ? `0${n}` : `${n}`)

/** 千分位整数：1234567 -> '1,234,567' */
export const int = (n) => `${Math.round(Number(n) || 0)}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')

/** 金额（保留两位小数 + 千分位）：8999 -> '8,999.00' */
export const money = (n) =>
  (Number(n) || 0).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')

/** 金额简写：73380 -> '¥7.34万'（用于指标卡这类空间有限的场景） */
export function moneyShort(n, unit = '万') {
  const v = Number(n) || 0
  if (unit === '亿' && Math.abs(v) >= 1e8) return `¥${(v / 1e8).toFixed(2)}亿`
  if (Math.abs(v) >= 1e4) return `¥${(v / 1e4).toFixed(2)}万`
  return `¥${money(v)}`
}

/** 百分比：0.927 -> '92.7%' */
export const percent = (n, digits = 1) => `${(Number(n) * 100).toFixed(digits)}%`

/** 日期时间：'2026-10-05 10:20' */
export function fmtDate(d, withSec = false) {
  if (!d) return '—'
  const t = d instanceof Date ? d : new Date(d)
  if (Number.isNaN(t.getTime())) return '—'
  const base = `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())} ${pad(t.getHours())}:${pad(t.getMinutes())}`
  return withSec ? `${base}:${pad(t.getSeconds())}` : base
}

/** 仅日期：'2026-10-05' */
export function fmtDay(d) {
  if (!d) return '—'
  const t = d instanceof Date ? d : new Date(d)
  if (Number.isNaN(t.getTime())) return '—'
  return `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())}`
}

/** 仅时间：'10:20' */
export function fmtTime(d) {
  if (!d) return '—'
  const t = d instanceof Date ? d : new Date(d)
  if (Number.isNaN(t.getTime())) return '—'
  return `${pad(t.getHours())}:${pad(t.getMinutes())}`
}

/** 当天零点时间戳，用于按「日」比较 */
export const dayStart = (d) => {
  const t = d instanceof Date ? d : new Date(d)
  return new Date(t.getFullYear(), t.getMonth(), t.getDate()).getTime()
}

/** 两个时间的间隔（小时，保留一位） */
export const hoursBetween = (a, b) => (new Date(b) - new Date(a)) / 3600000

/** 手机号脱敏：13800138000 -> '138****8000' */
export const maskPhone = (p) => `${p}`.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2')

/** 超时描述：-2.4 -> '已超时 2.4h'，2 -> '剩 2.0 小时' */
export function deadlineText(hours) {
  if (hours == null) return '—'
  if (hours < 0) return `已超时 ${Math.abs(hours).toFixed(1)}h`
  if (hours < 1) return `剩 ${Math.round(hours * 60)} 分钟`
  return `剩 ${hours.toFixed(1)} 小时`
}

/** 截断长文本 */
export const ellipsis = (s, len = 18) =>
  `${s || ''}`.length > len ? `${`${s}`.slice(0, len)}…` : `${s || ''}`
