import axios from 'axios'
import { ElMessage } from 'element-plus'
import { clearToken, getToken } from './token'

/**
 * 统一 HTTP 封装（与 C 端前台保持同一套约定）。
 *
 * 后端约定：HTTP 状态恒为 200，业务结果在 body 里：
 *   { code: 0, msg: 'success', data: ... }
 * 因此这里把 body.data 直接解包返回，调用方拿到即是业务数据。
 *
 * 未登录时后端同样返回 HTTP 200 + code 401（见 RestAuthenticationEntryPoint），
 * 所以这里用「业务码」而不是 HTTP 状态来判断登录态，两个分支都要兜住。
 */
const BASE_URL = import.meta.env.VITE_API_BASE || '/api/v1'

/** 业务错误码：未登录 / 登录已过期 */
const UNAUTHORIZED = 401

/** 防止并发请求同时触发跳转，导致 redirect 参数互相覆盖 */
let redirecting = false

/** 登录失效：清令牌 + 记录来源地址，跳登录页。 */
function handleUnauthorized() {
  clearToken()
  if (redirecting) return
  const { pathname, search, hash } = window.location
  if (pathname === '/login') return
  redirecting = true
  const from = encodeURIComponent(pathname + search + hash)
  window.location.replace(`/login?redirect=${from}`)
}

/** 统一的接口异常，便于调用方按 code 分支处理 */
export class ApiError extends Error {
  constructor(code, msg) {
    super(msg || '请求失败')
    this.name = 'ApiError'
    this.code = code
  }
}

const request = axios.create({
  baseURL: BASE_URL,
  timeout: 20000
})

request.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const body = response.data
    // 非标准响应（文件流等）原样返回
    if (!body || typeof body !== 'object' || !('code' in body)) {
      return body
    }
    if (body.code === 0) {
      return body.data
    }
    if (body.code === UNAUTHORIZED) {
      handleUnauthorized()
      return Promise.reject(new ApiError(body.code, body.msg))
    }
    if (!response.config.silent) {
      ElMessage.error(body.msg || '请求失败')
    }
    return Promise.reject(new ApiError(body.code, body.msg))
  },
  (error) => {
    const config = error.config || {}
    let msg = '网络异常，请稍后重试'
    if (error.code === 'ECONNABORTED') {
      msg = '请求超时，请稍后重试'
    } else if (error.response?.status === 401) {
      handleUnauthorized()
      return Promise.reject(error)
    } else if (error.response?.status === 404) {
      msg = '接口不存在，请确认后端已启动'
    } else if (error.response?.status >= 500) {
      msg = '服务异常，请稍后重试'
    }
    if (!config.silent) {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

export default request
