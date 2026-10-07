import request from './request'
import { toUserProfile } from './adapters'

/** 认证相关接口，对应 LoginView / RegisterView / ForgotView */
export const authApi = {
  /** 密码登录 → { token, expiresIn, user } */
  async login({ account, password, remember }) {
    const data = await request.post('/auth/login', { account, password, remember })
    return { token: data.token, expiresIn: data.expiresIn, user: toUserProfile(data.user) }
  },

  /** 短信验证码登录（后端对未注册手机号会自动建号） */
  async smsLogin({ phone, smsCode }) {
    const data = await request.post('/auth/login/sms', { phone, smsCode })
    return { token: data.token, expiresIn: data.expiresIn, user: toUserProfile(data.user) }
  },

  register(payload) {
    return request.post('/auth/register', payload)
  },

  /** 发送验证码，scene：login / register / reset */
  sendSmsCode(phone, scene) {
    return request.post('/auth/sms-code', { phone, scene })
  },

  resetPassword(payload) {
    return request.post('/auth/password/reset', payload)
  },

  logout() {
    return request.post('/auth/logout', null, { silent: true })
  }
}

export default authApi
