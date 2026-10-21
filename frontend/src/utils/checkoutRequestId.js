const STORAGE_KEY = 'checkout:requestId'

function createRequestId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '')
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

/**
 * 结算会话的下单幂等键。
 *
 * 必须在「进入结算页」时生成并持久化，而不是每次点击提交都新建：
 * 请求已到达后端但响应丢失时，用户再次点击才能命中后端的 requestId 幂等。
 */
export function getOrCreateCheckoutRequestId() {
  try {
    const existing = sessionStorage.getItem(STORAGE_KEY)
    if (existing) {
      return existing
    }
    const created = createRequestId()
    sessionStorage.setItem(STORAGE_KEY, created)
    return created
  } catch (e) {
    // 隐私模式等场景 sessionStorage 不可用：退化为本次会话内复用同一个 id
    return createRequestId()
  }
}

/** 下单成功后清除，下一次结算使用新的幂等键。 */
export function clearCheckoutRequestId() {
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch (e) {
    /* ignore */
  }
}
