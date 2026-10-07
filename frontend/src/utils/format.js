/** 金额格式化：1234.5 → 1,234.50 */
export const fmtMoney = (n) =>
  Number(n || 0).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')

/** 只保留日期部分：2026-10-05 12:30:00 → 2026-10-05 */
export const fmtDate = (value) => (value ? String(value).slice(0, 10) : '')

/** 时间轴节点展示用文案：没有时间时用占位符 */
export const fmtTime = (value, placeholder = '') => value || placeholder

/** 秒 → mm:ss */
export const fmtCountdown = (seconds) => {
  const total = Math.max(0, Number(seconds) || 0)
  const m = String(Math.floor(total / 60)).padStart(2, '0')
  const s = String(total % 60).padStart(2, '0')
  return `${m}:${s}`
}
