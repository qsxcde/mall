<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { couponApi } from '@/api/marketing'
import { useUserStore } from '@/stores/user'
import { useAfterSaleStore } from '@/stores/aftersale'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const afterStore = useAfterSaleStore()
const { info, addresses } = storeToRefs(user)

const menus = [
  { key: 'profile', icon: '👤', name: '个人资料' },
  { key: 'coupon', icon: '🎟️', name: '我的优惠券' },
  { key: 'address', icon: '📍', name: '地址管理' },
  { key: 'security', icon: '🔒', name: '账户安全' },
  { key: 'aftersale', icon: '🛠️', name: '售后服务' }
]

const activeTab = ref(menus.some((m) => m.key === route.query.tab) ? route.query.tab : 'profile')
watch(() => route.query.tab, (v) => {
  if (v && menus.some((m) => m.key === v)) activeTab.value = v
})
const switchTab = (key) => {
  activeTab.value = key
  router.replace({ name: 'user', query: { tab: key } })
}

// ---- 个人资料 ----
const profile = reactive({ ...info.value })
// 刷新页面时资料是异步补上的，这里跟 store 保持同步，避免编辑框空着
watch(info, (v) => Object.assign(profile, v), { deep: true })

const saving = ref(false)
const saveProfile = async () => {
  saving.value = true
  try {
    await user.updateInfo(profile)
    ElMessage.success('资料已保存')
  } catch (e) {
    /* 失败信息由拦截器提示 */
  } finally {
    saving.value = false
  }
}

// ---- 我的优惠券 ----
// 后端 status：0 未使用 / 1 已使用 / 2 已过期；一次取全，前端分 Tab
const coupons = ref([])
const couponTab = ref('usable')
const COUPON_STATUS = { usable: 0, used: 1, expired: 2 }

const couponTabs = computed(() => [
  { key: 'usable', name: `可用 (${countByStatus(0)})` },
  { key: 'used', name: `已使用 (${countByStatus(1)})` },
  { key: 'expired', name: `已过期 (${countByStatus(2)})` }
])
const countByStatus = (status) => coupons.value.filter((c) => c.status === status).length
const shownCoupons = computed(() => coupons.value.filter((c) => c.status === COUPON_STATUS[couponTab.value]))

const loadCoupons = async () => {
  try {
    coupons.value = await couponApi.mine()
  } catch (e) {
    coupons.value = []
  }
}

// ---- 地址管理 ----
const addrDialog = ref(false)
const editingId = ref(null)
const addrForm = reactive({ name: '', phone: '', region: '', detail: '', isDefault: false })

const openAddr = () => {
  editingId.value = null
  Object.assign(addrForm, { name: '', phone: '', region: '', detail: '', isDefault: false })
  addrDialog.value = true
}
const editAddr = (addr) => {
  editingId.value = addr.id
  Object.assign(addrForm, {
    name: addr.name,
    phone: addr.phone,
    region: [addr.province, addr.city, addr.district].filter(Boolean).join(' '),
    detail: addr.detail,
    isDefault: addr.isDefault
  })
  addrDialog.value = true
}

const savingAddr = ref(false)
const saveAddr = async () => {
  if (!addrForm.name || !addrForm.phone || !addrForm.detail) {
    return ElMessage.warning('请填写完整地址信息')
  }
  if (!/^1[3-9]\d{9}$/.test(addrForm.phone)) {
    return ElMessage.warning('请输入正确的手机号')
  }
  savingAddr.value = true
  try {
    if (editingId.value) {
      await user.updateAddress(editingId.value, { ...addrForm })
      ElMessage.success('地址已更新')
    } else {
      await user.addAddress({ ...addrForm })
      ElMessage.success('地址已保存')
    }
    addrDialog.value = false
  } catch (e) {
    /* 失败信息由拦截器提示 */
  } finally {
    savingAddr.value = false
  }
}
const removeAddr = (id) => {
  ElMessageBox.confirm('确定删除该地址吗？', '提示', { type: 'warning' })
    .then(async () => {
      await user.removeAddress(id)
      ElMessage.success('已删除')
    })
    .catch(() => {})
}
const setDefault = async (id) => {
  try {
    await user.setDefaultAddress(id)
    ElMessage.success('已设为默认地址')
  } catch (e) {
    /* 失败信息由拦截器提示 */
  }
}

// ---- 账户安全 ----
// 只保留后端真实支持的项，状态全部由当前账号推导，不写死
const maskPhone = (phone) => (phone ? `${phone.slice(0, 3)}****${phone.slice(-4)}` : '')

const securityRows = computed(() => [
  { key: 'password', icon: '🔑', name: '登录密码', desc: '已设置 · 建议定期更换', ok: true, btn: '修改' },
  {
    key: 'phone',
    icon: '📱',
    name: '绑定手机',
    desc: info.value.phone ? `${maskPhone(info.value.phone)} 已验证` : '未绑定',
    ok: !!info.value.phone,
    btn: info.value.phone ? '更换' : '去绑定'
  },
  {
    key: 'email',
    icon: '✉️',
    name: '绑定邮箱',
    desc: info.value.email || '未绑定，绑定后可用于找回密码',
    ok: !!info.value.email,
    btn: info.value.email ? '更换' : '去绑定'
  }
])

const onSecurity = (row) => {
  // 密码修改复用「找回密码」链路；邮箱在个人资料里维护
  if (row.key === 'password') return router.push({ name: 'forgot' })
  if (row.key === 'email') return switchTab('profile')
  return ElMessage.info('更换手机号功能开发中')
}

// ---- 售后服务 ----
const asTab = ref('all')
const asTabs = [
  { key: 'all', name: '全部' },
  { key: 'doing', name: '处理中' },
  { key: 'done', name: '已完成' }
]
// status 为适配层转换后的语义 key：doing / done / canceled
const shownAfterSales = computed(() =>
  asTab.value === 'all' ? afterStore.records : afterStore.records.filter((a) => a.status === asTab.value)
)

const applyService = (type) => router.push({ name: 'aftersale-apply', query: { type } })
const openAfterSale = (id) => router.push({ name: 'aftersale-detail', params: { id } })

onMounted(async () => {
  await Promise.all([user.loadAddresses().catch(() => {}), loadCoupons()])
  if (activeTab.value === 'aftersale') {
    afterStore.load().catch(() => {})
  }
})

// 售后台账在切到该 Tab 时才需要
watch(activeTab, (tab) => {
  if (tab === 'aftersale') afterStore.load().catch(() => {})
})
</script>

<template>
  <div class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item>个人中心</el-breadcrumb-item>
    </el-breadcrumb>

    <div class="uc-layout">
      <!-- 侧边栏 -->
      <aside class="uc-side">
        <div class="user-head">
          <div class="avatar">😊</div>
          <div>
            <div class="uname">{{ info.nickname }}</div>
            <div class="ulevel">VIP {{ info.level }}</div>
          </div>
        </div>
        <nav class="uc-menu">
          <a
            v-for="m in menus"
            :key="m.key"
            :class="{ on: activeTab === m.key }"
            @click="switchTab(m.key)"
          >
            <span class="ic">{{ m.icon }}</span>{{ m.name }}
          </a>
        </nav>
        <div class="uc-extra">
          <a @click="router.push({ name: 'orders' })"><span class="ic">📦</span>我的订单</a>
          <a @click="router.push({ name: 'my-reviews' })"><span class="ic">⭐</span>我的评价</a>
          <a @click="router.push({ name: 'favorites' })"><span class="ic">❤️</span>我的收藏</a>
          <a @click="router.push({ name: 'history' })"><span class="ic">🕘</span>浏览历史</a>
          <a @click="router.push({ name: 'points' })"><span class="ic">🎁</span>积分商城</a>
          <a @click="router.push({ name: 'messages' })"><span class="ic">🔔</span>消息中心</a>
          <a @click="router.push({ name: 'member' })"><span class="ic">👑</span>会员中心</a>
        </div>
      </aside>

      <!-- 主内容 -->
      <main class="uc-main">
        <!-- 个人资料 -->
        <section v-show="activeTab === 'profile'">
          <div class="panel-title">个人资料</div>
          <div class="pf-top">
            <div class="pf-avatar">😊</div>
            <div>
              <div style="font-size:16px;font-weight:bold">{{ info.nickname }}</div>
              <div class="pf-edit-av" @click="ElMessage.info('更换头像（演示）')">更换头像</div>
            </div>
          </div>
          <el-form label-position="top" style="max-width:680px">
            <el-row :gutter="20">
              <el-col :span="12"><el-form-item label="昵称"><el-input v-model="profile.nickname" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="真实姓名"><el-input v-model="profile.realName" placeholder="请输入真实姓名" /></el-form-item></el-col>
              <el-col :span="12">
                <el-form-item label="性别">
                  <el-radio-group v-model="profile.gender">
                    <el-radio value="男">男</el-radio>
                    <el-radio value="女">女</el-radio>
                    <el-radio value="保密">保密</el-radio>
                  </el-radio-group>
                </el-form-item>
              </el-col>
              <el-col :span="12"><el-form-item label="生日"><el-date-picker v-model="profile.birthday" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="手机号"><el-input v-model="profile.phone" disabled /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="邮箱"><el-input v-model="profile.email" placeholder="请输入邮箱" /></el-form-item></el-col>
              <el-col :span="24"><el-form-item label="个人简介"><el-input v-model="profile.bio" type="textarea" :rows="3" /></el-form-item></el-col>
            </el-row>
            <el-button type="primary" :loading="saving" @click="saveProfile">保存修改</el-button>
          </el-form>
        </section>

        <!-- 我的优惠券 -->
        <section v-show="activeTab === 'coupon'">
          <div class="panel-title">我的优惠券</div>
          <div class="cp-sub">
            <a v-for="t in couponTabs" :key="t.key" :class="{ on: couponTab === t.key }" @click="couponTab = t.key">{{ t.name }}</a>
          </div>
          <div class="cp-list">
            <div v-for="c in shownCoupons" :key="c.id" class="cp-item" :class="{ used: couponTab !== 'usable' }">
              <div class="l">
                <div class="a"><small v-if="c.unit === '¥'">¥</small>{{ c.amount }}<small v-if="c.unit !== '¥'">{{ c.unit }}</small></div>
                <div class="c">{{ c.cond }}</div>
              </div>
              <div class="r">
                <div><div class="n">{{ c.name }}</div><div class="s">{{ c.desc }}</div></div>
                <el-button v-if="couponTab === 'usable'" type="primary" size="small" @click="router.push({ name: 'coupon' })">去使用</el-button>
                <el-button v-else size="small" disabled>{{ couponTab === 'used' ? '已使用' : '已过期' }}</el-button>
              </div>
            </div>
          </div>
        </section>

        <!-- 地址管理 -->
        <section v-show="activeTab === 'address'">
          <div class="panel-title">地址管理</div>
          <div class="addr-grid">
            <div v-for="a in addresses" :key="a.id" class="addr-card" :class="{ default: a.isDefault }">
              <div class="name">
                {{ a.name }}
                <span v-if="a.isDefault" class="tag">默认</span>
                <span class="phone">{{ a.phone }}</span>
              </div>
              <div class="detail">{{ a.region }}<br />{{ a.detail }}</div>
              <div class="ops">
                <a @click="editAddr(a)">编辑</a>
                <a class="del" @click="removeAddr(a.id)">删除</a>
                <a @click="setDefault(a.id)">设为默认</a>
              </div>
            </div>
            <div class="addr-add" @click="openAddr"><span class="plus">＋</span>新增收货地址</div>
          </div>
        </section>

        <!-- 账户安全 -->
        <section v-show="activeTab === 'security'">
          <div class="panel-title">账户安全</div>
          <div class="sec-list">
            <div v-for="s in securityRows" :key="s.name" class="sec-row">
              <div class="s-ic">{{ s.icon }}</div>
              <div class="s-info">
                <div class="s-name">{{ s.name }}</div>
                <div class="s-desc" :class="{ ok: s.ok }">{{ s.desc }}</div>
              </div>
              <el-button size="small" @click="onSecurity(s)">{{ s.btn }}</el-button>
            </div>
          </div>
        </section>

        <!-- 售后服务 -->
        <section v-show="activeTab === 'aftersale'">
          <div class="panel-title">售后服务</div>
          <div class="as-services">
            <div class="as-svc" @click="applyService('return')"><div class="ic">🔁</div><div class="nm">申请退换货</div><div class="ds">7 天无理由 · 质量问题退换</div></div>
            <div class="as-svc" @click="applyService('repair')"><div class="ic">🔧</div><div class="nm">维修服务</div><div class="ds">保修期内 · 预约上门</div></div>
            <div class="as-svc" @click="applyService('refund')"><div class="ic">💰</div><div class="nm">价格保护</div><div class="ds">降价补差 · 自动审核</div></div>
            <div class="as-svc" @click="ElMessage.info('已为您接入在线客服（演示）')"><div class="ic">💬</div><div class="nm">咨询客服</div><div class="ds">在线客服 · 7×24 小时</div></div>
          </div>

          <div class="panel-title" style="margin-top:26px">我的售后记录</div>
          <div class="as-sub">
            <a v-for="t in asTabs" :key="t.key" :class="{ on: asTab === t.key }" @click="asTab = t.key">{{ t.name }}</a>
          </div>
          <div class="as-list">
            <div v-for="a in shownAfterSales" :key="a.id" class="as-item">
              <div class="as-img" :class="a.c">图</div>
              <div class="as-info"><div class="as-name">{{ a.name }}</div><div class="as-desc">{{ a.desc }}</div></div>
              <span class="as-status" :class="a.status">{{ a.statusText }}</span>
              <el-button size="small" @click="openAfterSale(a.id)">查看详情</el-button>
            </div>
          </div>
        </section>
      </main>
    </div>

    <!-- 新增地址弹窗 -->
    <el-dialog v-model="addrDialog" :title="editingId ? '编辑收货地址' : '新增收货地址'" width="520px">
      <el-form label-width="80px">
        <el-form-item label="收货人"><el-input v-model="addrForm.name" placeholder="请输入收货人姓名" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="addrForm.phone" placeholder="请输入手机号" /></el-form-item>
        <el-form-item label="地区"><el-input v-model="addrForm.region" placeholder="省 / 市 / 区" /></el-form-item>
        <el-form-item label="详细地址"><el-input v-model="addrForm.detail" placeholder="街道 / 小区 / 门牌号" /></el-form-item>
        <el-form-item><el-checkbox v-model="addrForm.isDefault">设为默认收货地址</el-checkbox></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addrDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingAddr" @click="saveAddr">{{ editingId ? '保存修改' : '保存' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>
