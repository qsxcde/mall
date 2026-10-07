import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken } from './token'

/**
 * 统一 HTTP 封装。
 *
 * 后端约定：HTTP 状态恒为 200，业务结果在 body 里：
 *   { code: 0, msg: 'success', data: ... }
 * 因此这里把 body.data 直接解包返回，调用方拿到即是业务数据。
 */
const BASE_URL = import.meta.env.VITE_API_BASE || '/api/v1'

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

/** 并发 401 时只跳转一次，避免多个请求同时触发路由跳转 */
let redirecting = false

async function redirectToLogin() {
  if (redirecting) return
  redirecting = true
  try {
    // 先清本地登录态：否则路由守卫会认为「已登录」，把用户从登录页弹回首页
    const { useUserStore } = await import('@/stores/user')
    useUserStore().clearSession()

    // 动态导入打破 router → store → api → router 的循环依赖
    const { default: router } = await import('@/router')
    const current = router.currentRoute.value
    if (current.name !== 'login') {
      await router.replace({ name: 'login', query: { redirect: current.fullPath } })
    }
  } finally {
    setTimeout(() => {
      redirecting = false
    }, 1000)
  }
}

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
    if (body.code === 401) {
      // 令牌失效：清掉本地登录态并回登录页
      redirectToLogin()
      return Promise.reject(new ApiError(401, body.msg))
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
