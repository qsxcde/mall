/**
 * 经营日序列 —— 全站「钱」的唯一数据源。
 *
 * 为什么单独放一个文件？
 * 经营概览的「本月营收」、数据看板的「30 天成交额」、财务结算的「各账期成交额」
 * 本质上是同一笔钱的不同切法。如果各自造数，三个页面就会互相打架
 * （概览说本月 379 万，财务说账期合计 440 万）。
 * 因此这里生成 60 天序列，各页只做不同区间的聚合。
 *
 * 同时它避免了 catalog 与 growth 之间的循环依赖。
 */
import { createRng, NOW } from './shared'

const DAY = 86400000

/** 生成 60 天日序列：成交额 / 订单数 / 访客数，带周末效应与整体上行趋势 */
function buildDailySeries(days = 60) {
  const rng = createRng(20261405)
  const list = []
  for (let i = days - 1; i >= 0; i -= 1) {
    const date = new Date(NOW.getTime() - i * DAY)
    const weekday = date.getDay()
    // 周末流量更高
    const weekendBoost = weekday === 0 || weekday === 6 ? 1.18 : 1
    // 越接近今天基数越高，制造自然的增长曲线
    const growth = 1 + (days - 1 - i) * 0.006
    const visitors = Math.round(4200 * weekendBoost * growth * (0.92 + rng() * 0.16))
    const orders = Math.round(visitors * (0.028 + rng() * 0.012))
    const amount = Math.round(orders * (620 + rng() * 260))
    list.push({
      date,
      label: `${date.getMonth() + 1}/${date.getDate()}`,
      visitors,
      orders,
      amount,
      customers: Math.round(orders * (0.62 + rng() * 0.12))
    })
  }
  return list
}

export const DAILY_SERIES = buildDailySeries(60)

/* ---------------------------- 聚合工具 ---------------------------- */

/** 按字段求和 */
export const sum = (arr, key) => arr.reduce((acc, it) => acc + it[key], 0)

/** 环比：本期 / 上期 - 1；上期为 0 时返回 0，避免出现 Infinity */
export function chainRatio(current, previous) {
  if (!previous) return 0
  return (current - previous) / previous
}

/** 取最近 n 天 */
export const lastDays = (n) => DAILY_SERIES.slice(-n)

/** 取「上一个 n 天」（用于环比基准） */
export const prevDays = (n) => DAILY_SERIES.slice(-n * 2, -n)

/** 日期 → 'MM-DD' */
export const mmdd = (d) => `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
