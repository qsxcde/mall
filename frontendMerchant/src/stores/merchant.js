import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { fetchNavBadges } from '@/api/catalog'
import { fetchProfile, fetchShop } from '@/api/auth'

/**
 * 商家端全局状态：店铺信息、登录用户、侧栏角标、通知。
 *
 * 角标集中在这里加载一次，保证「任意页面看到的待办数字都一致」，
 * 避免每个页面各自算一遍导致口径漂移。
 *
 * 店铺与账号资料来自后端（/merchant/auth/shop、/merchant/auth/profile），
 * 不再写死在本地 —— 否则换一个商家账号登录，界面还显示上一家的店名。
 */
export const useMerchantStore = defineStore('merchant', () => {
  /** 店铺：未加载前给空壳，避免模板读取属性时报错 */
  const shop = ref({ name: '', logo: '', verified: false, level: '', todayTarget: 0 })

  /** 登录用户 */
  const user = ref({ name: '', role: '', avatar: '' })

  const profileLoaded = ref(false)

  /** 侧栏角标（key 与路由 meta.badge 对应） */
  const badges = ref({})
  const loading = ref(false)

  /** 顶栏与角标相关的通知，文案由角标实时拼装，不会出现「通知说 3 笔、角标显示 0 笔」的矛盾 */
  const notifications = computed(() => {
    const b = badges.value
    const list = []

    if (b.shippingLate) {
      list.push({
        key: 'ship',
        icon: 'Van',
        tone: 'coral',
        title: `${b.shippingLate} 笔订单已接近发货时限`,
        desc: '超过 24 小时将影响店铺体验分',
        to: '/shipping'
      })
    }
    if (b.aftersale) {
      list.push({
        key: 'after',
        icon: 'RefreshLeft',
        tone: 'amber',
        title: `${b.aftersale} 笔售后申请待处理`,
        desc: '需在 48 小时内响应，否则将自动退款',
        to: '/aftersale'
      })
    }
    if (b.productWarn) {
      list.push({
        key: 'stock',
        icon: 'WarnTriangleFilled',
        tone: 'violet',
        title: `${b.productWarn} 个商品库存低于安全线`,
        desc: '建议尽快补货，避免影响转化',
        to: '/products'
      })
    }
    if (b.reviewWait) {
      list.push({
        key: 'review',
        icon: 'ChatLineSquare',
        tone: 'brand',
        title: `${b.reviewWait} 条评价待回复`,
        desc: '及时回复有助于提升 DSR 评分',
        to: '/reviews'
      })
    }
    return list
  })

  /** 未读通知总数 = 各类待办之和，与侧栏角标天然一致 */
  const unreadCount = computed(() => notifications.value.length)

  /**
   * 加载店铺与账号资料。
   * 登录后由路由守卫调用一次；刷新页面时若已有令牌也会重新拉取。
   * @param {boolean} [force] 强制刷新
   */
  async function loadProfile(force = false) {
    if (profileLoaded.value && !force) return
    const [shopRes, profileRes] = await Promise.all([fetchShop(), fetchProfile()])
    const name = profileRes?.nickname || profileRes?.username || ''
    shop.value = {
      name: shopRes?.name || '',
      logo: shopRes?.logo || shopRes?.name?.slice(0, 1) || '店',
      verified: Boolean(shopRes?.verified),
      level: shopRes?.level || '',
      todayTarget: Number(shopRes?.todayTarget || 0)
    }
    user.value = {
      name,
      role: profileRes?.role || '',
      avatar: profileRes?.avatar || name.slice(0, 1) || '商'
    }
    profileLoaded.value = true
  }

  /**
   * 加载侧栏角标。失败时静默降级为全 0，不阻断页面渲染。
   * @param {boolean} [force] 强制刷新
   */
  async function loadBadges(force = false) {
    if (loading.value) return
    if (!force && Object.keys(badges.value).length) return
    loading.value = true
    try {
      badges.value = await fetchNavBadges()
    } catch {
      badges.value = {}
    } finally {
      loading.value = false
    }
  }

  /** 取某个角标的数值，0 或不存在时返回 0（视图据此决定是否展示徽标） */
  const badgeOf = (key) => (key ? badges.value[key] || 0 : 0)

  /**
   * 顶栏全局搜索关键词。
   * 放在 store 里而非各页面内部，是为了让「顶栏搜索框」真正能驱动当前页面的列表，
   * 而不是一个装饰性的输入框。切换路由时由布局清空，避免污染下一页。
   */
  const keyword = ref('')
  const setKeyword = (v) => {
    keyword.value = v ?? ''
  }

  /** 登出时清空全局状态，避免下一个账号看到上一个账号的数据 */
  function reset() {
    shop.value = { name: '', logo: '', verified: false, level: '', todayTarget: 0 }
    user.value = { name: '', role: '', avatar: '' }
    profileLoaded.value = false
    badges.value = {}
    keyword.value = ''
  }

  return {
    shop,
    user,
    badges,
    loading,
    keyword,
    notifications,
    unreadCount,
    loadProfile,
    loadBadges,
    badgeOf,
    setKeyword,
    reset
  }
})
