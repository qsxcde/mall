/**
 * 商家端认证 API。
 *
 * 与买家侧接口（/auth/**）完全独立：登录后签发的是带 `scope=merchant` 的令牌，
 * 只能访问 /merchant/** 接口。
 */
import request from './request'
import { clearToken, setToken } from './token'

/**
 * 商家登录。
 * 成功后立即落库令牌，供 request 拦截器后续自动携带。
 * @param {{account: string, password: string}} payload
 */
export async function login(payload) {
  const res = await request.post('/merchant/auth/login', payload)
  if (res?.token) setToken(res.token)
  return res
}

/** 当前店铺信息。 */
export function fetchShop() {
  return request.get('/merchant/auth/shop')
}

/** 当前登录商家资料。 */
export function fetchProfile() {
  return request.get('/merchant/auth/profile')
}

/**
 * 登出：服务端删除会话（失败也不阻断），前端无论如何都要清掉本地令牌。
 */
export async function logout() {
  try {
    await request.post('/merchant/auth/logout')
  } catch (e) {
    /* 会话已过期等情况忽略，本地清理更重要 */
  } finally {
    clearToken()
  }
}
