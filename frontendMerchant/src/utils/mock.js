/**
 * Mock 适配器（已停用，保留作参考）。
 *
 * 商家端后端接口（`/api/v1/merchant/**`）已落地，`src/api/*` 全部改为真实请求，
 * 当前工程已无任何模块引用本文件与 `src/mock/**`。
 *
 * 保留原因：`src/mock/**` 记录了各列表的出参结构与筛选口径，
 * 排查「接口返回与视图预期不一致」时可直接对照。
 * 若确认不再需要，可整体删除 `src/mock/` 与本文件。
 */

/** 模拟网络往返耗时（毫秒），让加载态可见，避免数据「瞬间闪现」 */
export const MOCK_DELAY = 240

/**
 * 深拷贝，兼容 Vue 的响应式代理与 Date。
 *
 * 为什么不用原生 `structuredClone`？
 * 视图里的弹窗状态多为 `reactive({ ids: [...] })`，读出来的是 Proxy，
 * 而 structuredClone 遇到 Proxy 会抛 DataCloneError。
 * 手写递归读取可以顺带解包代理（读取属性即自动脱壳），同时保留 Date 类型
 * —— JSON 方案会把 Date 变成字符串，导致 `deadline - now` 这类计算变成 NaN。
 *
 * @param {*} value
 * @returns {*}
 */
function deepClone(value) {
  if (value === null || typeof value !== 'object') return value
  if (value instanceof Date) return new Date(value.getTime())
  if (Array.isArray(value)) return value.map(deepClone)

  const out = {}
  for (const key of Object.keys(value)) {
    out[key] = deepClone(value[key])
  }
  return out
}

/**
 * 延迟返回工厂函数的产物。
 * 用工厂而非直接传值，是为了让大数组的拷贝发生在「请求时」而不是「模块加载时」。
 * @param {Function} factory 返回业务数据的函数
 * @param {number} [delay] 自定义延迟
 * @returns {Promise<any>}
 */
export function resolveMock(factory, delay = MOCK_DELAY) {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      try {
        resolve(deepClone(factory()))
      } catch (err) {
        reject(err)
      }
    }, delay)
  })
}
