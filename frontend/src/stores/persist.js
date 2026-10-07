// 轻量 localStorage 持久化助手：确保路由跳转/刷新后状态不丢失
const PREFIX = 'geek-mall:'

export function loadState(key, fallback) {
  try {
    const raw = localStorage.getItem(PREFIX + key)
    return raw ? JSON.parse(raw) : fallback
  } catch (e) {
    return fallback
  }
}

export function saveState(key, value) {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value))
  } catch (e) {
    /* 忽略隐私模式等写入异常 */
  }
}
