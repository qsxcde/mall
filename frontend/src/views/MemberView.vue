<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userApi } from '@/api/user'
import { useUserStore } from '@/stores/user'
import { benefitIcons } from '@/data/constants'

const router = useRouter()
const user = useUserStore()

// 等级、成长值、权益、签到状态全部来自后端
const raw = ref({
  nickname: '',
  levelName: '',
  points: 0,
  growth: 0,
  growthMin: 0,
  growthMax: 0,
  growthPercent: 0,
  nextLevelName: '',
  nextLevelGrowth: 0,
  tiers: [],
  benefits: [],
  signedToday: false,
  signDays: 0
})
const signing = ref(false)

const load = async () => {
  try {
    raw.value = await userApi.memberInfo()
  } catch (e) {
    /* 失败信息由拦截器提示 */
  }
}
onMounted(load)

/** 7 天签到进度：已完成天数取后端的累计签到天数 */
const buildSignDays = (r) => {
  const done = Math.min(r.signDays || 0, 7)
  return Array.from({ length: 7 }, (_, i) => ({
    day: i + 1,
    done: i < done,
    today: i === done && !r.signedToday
  }))
}

/**
 * 成长任务：完成状态取自真实数据（签到状态 / 资料完善度）。
 * 注意：目前只有「每日签到」的积分奖励由后端真实发放，其余为运营展示位。
 */
const buildTasks = (r) => {
  const profile = user.info || {}
  return [
    { key: 'sign', icon: '📅', name: '每日签到', reward: '+10 积分', action: r.signedToday ? '已签到' : '去签到', done: !!r.signedToday },
    { key: 'profile', icon: '👤', name: '完善个人资料', reward: '提升账户安全', action: '去完善', done: !!(profile.avatar && profile.email) },
    { key: 'order', icon: '🛒', name: '逛逛新品', reward: '累计成长值', action: '去逛逛', done: false },
    { key: 'review', icon: '✍️', name: '发表商品评价', reward: '累计成长值', action: '去评价', done: false }
  ]
}

const memberInfo = computed(() => {
  const r = raw.value
  return {
    ...r,
    level: r.levelName || '普通会员',
    // 后端只存权益名称，图标属于展示规则，在前端按顺序补齐
    benefits: (r.benefits || []).map((name, i) => ({ icon: benefitIcons[i % benefitIcons.length], name })),
    signDays: buildSignDays(r),
    tasks: buildTasks(r)
  }
})

const signed = computed(() => !!raw.value.signedToday)
const points = computed(() => raw.value.points || 0)

const growthPercent = computed(() => {
  const r = raw.value
  if (r.growthPercent > 0) return Math.min(100, r.growthPercent)
  const span = (r.growthMax || 0) - (r.growthMin || 0)
  return span > 0 ? Math.min(100, Math.round(((r.growth - r.growthMin) / span) * 100)) : 0
})

const signIn = async () => {
  if (signed.value || signing.value) return
  signing.value = true
  try {
    const result = await user.signIn()
    ElMessage.success(`签到成功，积分 +${result?.points ?? 10}`)
    await Promise.all([load(), user.loadProfile().catch(() => {})])
  } catch (e) {
    /* 失败信息由拦截器提示 */
  } finally {
    signing.value = false
  }
}

const onTask = (t) => {
  if (t.key === 'sign') return signIn()
  if (t.key === 'profile') return router.push({ name: 'user', query: { tab: 'profile' } })
  if (t.key === 'order') return router.push({ name: 'category' })
  if (t.key === 'review') return router.push({ name: 'orders', query: { status: 'cmt' } })
}

// 等级由成长值自动升级，不存在「购买升级」入口，如实告知
const upgrade = () => ElMessage.info('会员等级由成长值自动升级，暂时无需手动升级')
const exchange = () => router.push({ name: 'points' })
</script>

<template>
  <div class="container page-wrap">
    <!-- 会员等级卡 -->
    <div class="member-hero">
      <div class="m-avatar" />
      <div class="m-main">
        <div class="m-name">{{ memberInfo.nickname }} <span class="m-badge">👑 {{ memberInfo.level }}</span></div>
        <div class="m-points">我的积分 <b>{{ points.toLocaleString() }}</b></div>
        <div class="m-growth">
          <el-progress :percentage="growthPercent" :stroke-width="10" :show-text="false" />
          <div class="m-growth-text">
            成长值 {{ (memberInfo.growth || 0).toLocaleString() }} / {{ (memberInfo.growthMax || 0).toLocaleString() }}，再得
            {{ Math.max(0, (memberInfo.growthMax || 0) - (memberInfo.growth || 0)).toLocaleString() }} 成长值升级{{ memberInfo.nextLevelName || '下一等级' }}
          </div>
        </div>
      </div>
      <div class="m-actions">
        <button class="m-btn m-btn-gold" @click="upgrade">立即升级</button>
        <button class="m-btn m-btn-ghost" @click="exchange">积分兑换</button>
      </div>
    </div>

    <!-- 等级体系 -->
    <div class="section-block">
      <div class="section-title">会员等级体系</div>
      <div class="tiers">
        <div v-for="t in memberInfo.tiers" :key="t.name" class="tier" :class="{ current: t.current }">
          <div class="tier-ico">{{ t.icon }}</div>
          <div class="tier-name">{{ t.name }}</div>
          <div class="tier-req">{{ t.req }}</div>
          <div class="tier-perk">{{ t.perk }}</div>
        </div>
      </div>
    </div>

    <!-- 专属权益 -->
    <div class="section-block">
      <div class="section-title">黄金会员专属权益</div>
      <div class="benefits">
        <div v-for="b in memberInfo.benefits" :key="b.name" class="benefit">
          <div class="ico">{{ b.icon }}</div>
          <div class="name">{{ b.name }}</div>
        </div>
      </div>
    </div>

    <!-- 每日签到 -->
    <div class="section-block">
      <div class="section-title">每日签到</div>
      <div class="signin">
        <div class="sign-days">
          <div
            v-for="(d, i) in memberInfo.signDays"
            :key="i"
            class="sign-day"
            :class="{ done: d.done || (signed && d.today), today: d.today }"
          >
            <div class="sign-dot">{{ d.done || (signed && d.today) ? '✓' : d.today ? '🎯' : '＋' }}</div>
            <small>{{ d.day }}</small>
          </div>
        </div>
        <button class="m-btn m-btn-gold" style="border-radius:22px;padding:10px 26px" :disabled="signed" @click="signIn">
          {{ signed ? '今日已签到' : '立即签到' }}
        </button>
      </div>
    </div>

    <!-- 成长任务 -->
    <div class="section-block">
      <div class="section-title">成长任务</div>
      <div class="tasks">
        <div v-for="t in memberInfo.tasks" :key="t.name" class="task">
          <div class="t-ico">{{ t.icon }}</div>
          <div class="task-main">
            <div class="task-name">{{ t.name }}</div>
            <div class="task-reward">{{ t.reward }}</div>
          </div>
          <el-button :disabled="t.done" size="small" :type="t.done ? 'default' : 'primary'" @click="onTask(t)">
            {{ t.action }}
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>
