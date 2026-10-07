/**
 * 图表公共工具。
 *
 * 三个自研图表（折线/柱状/环形）都要用到「色调 → 色值」的映射，
 * 折线与柱状还要用同一套 y 轴刻度算法，因此集中在此，避免各图各写一份导致刻度不一致。
 */

/** 业务色调 → 实际色值，与 CSS 变量保持同源 */
export const TONE_COLORS = {
  brand: '#1a6dff',
  green: '#109150',
  teal: '#0d9488',
  amber: '#e08b1a',
  coral: '#ff5a2e',
  violet: '#7c5cff',
  neutral: '#b9c3d4'
}

export const toneColor = (tone) => TONE_COLORS[tone] || TONE_COLORS.brand

/** 刻度候选：比纯 1/2/5/10 更密，避免「最大值 48.6 万却把轴撑到 100 万」的浪费 */
const STEPS = [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]

/**
 * 把最大值向上取整到「好看」的刻度值。
 * @param {number} value 数据最大值
 * @returns {number}
 */
export function niceMax(value) {
  if (!value || value <= 0) return 10
  const exp = Math.floor(Math.log10(value))
  const base = 10 ** exp
  const n = value / base
  const step = STEPS.find((s) => n <= s) ?? 10
  return step * base
}

/**
 * 折线路径：把点数组转成 SVG path 的 d 属性。
 * @param {{x:number,y:number}[]} points
 * @param {boolean} close 是否闭合到基线形成面积
 * @param {number} baseY 闭合时的基线 y
 */
export function toPath(points, close = false, baseY = 0) {
  if (!points.length) return ''
  const line = points.map((p, i) => `${i ? 'L' : 'M'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')
  if (!close) return line
  const first = points[0]
  const last = points[points.length - 1]
  return `${line} L${last.x.toFixed(1)},${baseY} L${first.x.toFixed(1)},${baseY} Z`
}
