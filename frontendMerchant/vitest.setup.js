/**
 * Vitest 全局 setup。
 *
 * jsdom 不实现部分浏览器 API，而 store / 适配层可能间接触达它们
 * （例如 matchMedia 在响应式工具里、localStorage 在持久化 store 里）。
 * 这里统一兜底，避免每个测试文件各写一遍。
 */

// useNarrow / useElementSize 会用到 matchMedia
if (!window.matchMedia) {
  window.matchMedia = (query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false
  })
}

// 每个用例结束后清空本地存储，防止「登录态」跨用例泄漏
afterEach(() => {
  try {
    window.localStorage.clear()
    window.sessionStorage.clear()
  } catch {
    // 某些环境下 storage 不可用，忽略即可
  }
})
