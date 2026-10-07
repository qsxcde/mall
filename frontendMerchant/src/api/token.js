/**
 * 商家端令牌读写。
 *
 * 与 C 端前台刻意使用不同的 key（前台是 mall_token），
 * 这样两个工程在同一浏览器里可以同时登录、互不覆盖。
 */
const TOKEN_KEY = 'merchant_token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

export function hasToken() {
  return Boolean(getToken())
}
