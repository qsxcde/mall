import { loadState, saveState } from '@/stores/persist'

/**
 * 令牌读写的唯一入口。
 *
 * 为什么要单独抽出来：令牌是「JSON 字符串」存在 localStorage 里的
 * （saveState 会 JSON.stringify），所以 `localStorage.getItem(...)` 永远是真值，
 * 清空后也是 '"' 而不是 null。各处直接用 getItem 判断登录态会出错，
 * 统一走这里就不会踩坑。
 */
export const TOKEN_KEY = 'user:token'

export const getToken = () => loadState(TOKEN_KEY, '')

export const hasToken = () => !!getToken()

export const setToken = (token) => saveState(TOKEN_KEY, token || '')

export const clearToken = () => saveState(TOKEN_KEY, '')
