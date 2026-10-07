import { defineStore } from 'pinia'
import authApi from '@/api/auth'
import userApi from '@/api/user'
import { clearToken, getToken, setToken } from '@/api/token'
import { loadState, saveState } from './persist'

/** 登录前的占位资料，避免模板里出现 undefined */
const guestInfo = () => ({
  nickname: '极客用户',
  avatar: '',
  realName: '',
  gender: '保密',
  genderCode: 0,
  birthday: '',
  phone: '',
  email: '',
  bio: '',
  level: '普通会员',
  levelId: 1,
  points: 0,
  growth: 0
})

const GENDER_CODE = { 男: 1, 女: 2, 保密: 0 }

export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    info: { ...guestInfo(), ...loadState('user:info', {}) },
    addresses: [],
    /** 是否已完成一次初始化（避免重复请求） */
    ready: false
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    defaultAddress: (state) => state.addresses.find((a) => a.isDefault) || state.addresses[0] || null
  },

  actions: {
    /** 应用启动 / 刷新后恢复登录态 */
    async init() {
      if (this.ready) return
      this.ready = true
      if (!this.token) return
      try {
        await Promise.all([this.loadProfile(), this.loadAddresses()])
      } catch (e) {
        // 令牌失效时拦截器已处理跳转，这里静默即可
      }
    },

    /* ------------------------------ 登录相关 ------------------------------ */

    async login(form) {
      const { token, user } = await authApi.login(form)
      this.applySession(token, user)
    },

    async smsLogin(form) {
      const { token, user } = await authApi.smsLogin(form)
      this.applySession(token, user)
    },

    register(form) {
      return authApi.register(form)
    },

    sendSmsCode(phone, scene) {
      return authApi.sendSmsCode(phone, scene)
    },

    resetPassword(form) {
      return authApi.resetPassword(form)
    },

    applySession(token, user) {
      this.token = token
      if (user) {
        this.info = { ...guestInfo(), ...this.info, ...user }
      }
      setToken(this.token)
      saveState('user:info', this.info)
      // 登录后立刻补齐地址等需要鉴权的数据
      this.loadAddresses().catch(() => {})
    },

    async logout() {
      try {
        await authApi.logout()
      } catch (e) {
        /* 令牌可能已过期，忽略 */
      }
      this.clearSession()
    },

    /**
     * 仅清本地登录态，不调用后端。
     *
     * 令牌失效（401）时由请求拦截器调用：必须先清掉本地 token，
     * 否则路由守卫仍认为「已登录」，会把用户从登录页弹回首页，
     * 从而卡在「看似登录、实则无数据」的状态。
     */
    clearSession() {
      this.token = ''
      this.addresses = []
      this.info = guestInfo()
      this.ready = false
      clearToken()
      saveState('user:info', this.info)
    },

    /* ------------------------------ 资料 ------------------------------ */

    async loadProfile() {
      this.info = { ...guestInfo(), ...this.info, ...(await userApi.profile()) }
      saveState('user:info', this.info)
    },

    /** 保存资料：只把后端认识的字段提交上去 */
    async updateInfo(patch) {
      const merged = { ...this.info, ...patch }
      await userApi.updateProfile({
        nickname: merged.nickname,
        gender: merged.genderCode ?? GENDER_CODE[merged.gender] ?? 0,
        birthday: merged.birthday || null,
        email: merged.email || null,
        bio: merged.bio || null,
        avatar: merged.avatar || ''
      })
      await this.loadProfile()
    },

    async uploadAvatar(url) {
      this.info = { ...this.info, avatar: url }
      saveState('user:info', this.info)
      await userApi.updateProfile({ avatar: url })
    },

    /* ------------------------------ 地址 ------------------------------ */

    async loadAddresses() {
      if (!this.token) return
      this.addresses = await userApi.addresses()
    },

    async addAddress(addr) {
      await userApi.addAddress(this.toAddressPayload(addr))
      await this.loadAddresses()
    },

    async updateAddress(id, addr) {
      await userApi.updateAddress(id, this.toAddressPayload(addr))
      await this.loadAddresses()
    },

    /**
     * 表单里的「省 / 市 / 区」目前是一个自由文本框，
     * 而后端按 province / city / district 三个字段存储，这里做一次拆分，
     * 保证填写的内容能原样回显。
     */
    toAddressPayload(addr) {
      const parts = String(addr.region || '').split(/[\s/]+/).filter(Boolean)
      return {
        name: addr.name,
        phone: addr.phone,
        province: addr.province || parts[0] || '',
        city: addr.city || parts[1] || '',
        district: addr.district || parts[2] || '',
        detail: addr.detail,
        isDefault: !!addr.isDefault
      }
    },

    async removeAddress(id) {
      await userApi.removeAddress(id)
      await this.loadAddresses()
    },

    async setDefaultAddress(id) {
      await userApi.setDefaultAddress(id)
      await this.loadAddresses()
    },

    /* ------------------------------ 积分 ------------------------------ */

    /** 每日签到：以后端返回的积分为准 */
    async signIn() {
      const result = await userApi.signIn()
      if (result?.totalPoints != null) {
        this.info = { ...this.info, points: result.totalPoints }
        saveState('user:info', this.info)
      }
      return result
    },

    setPoints(points) {
      this.info = { ...this.info, points: Number(points) || 0 }
      saveState('user:info', this.info)
    },

    addPoints(n) {
      this.setPoints(this.info.points + Number(n || 0))
    },

    deductPoints(n) {
      this.setPoints(Math.max(0, this.info.points - Number(n || 0)))
    }
  }
})
