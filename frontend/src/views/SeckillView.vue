<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { seckillApi } from '@/api/marketing'
import { useUserStore } from '@/stores/user'
import CountdownTimer from '@/components/CountdownTimer.vue'

const route = useRoute()
const router = useRouter()
const user = useUserStore()

// 场次与商品均来自后端
const sessions = ref([])
const seckillItems = ref([])
const sessionIndex = ref(0)
const grabbing = ref(0)

const loadItems = async () => {
  const current = sessions.value[sessionIndex.value]
  if (!current) return
  try {
    seckillItems.value = await seckillApi.items(current.id)
  } catch (e) {
    seckillItems.value = []
  }
}

onMounted(async () => {
  try {
    sessions.value = await seckillApi.sessions()
    // 默认落到「进行中」的场次，没有则用第一场
    const running = sessions.value.findIndex((s) => s.state === 'running')
    sessionIndex.value = running >= 0 ? running : 0
  } catch (e) {
    sessions.value = []
  }
  await loadItems()
})

const switchSession = async (i) => {
  if (sessions.value[i].state === 'done') {
    ElMessage.info('该场次已结束')
    return
  }
  sessionIndex.value = i
  await loadItems()
}

/**
 * 立即抢购：由后端 Redis 原子预扣 + 一人一单，成功直接生成待付款订单。
 * 秒杀不走购物车，所以需要先确定收货地址。
 */
const grab = async (item) => {
  if (!user.isLoggedIn) {
    ElMessage.warning('请先登录后再抢购')
    return router.push({ name: 'login', query: { redirect: route.fullPath } })
  }
  if (!user.addresses.length) {
    await user.loadAddresses().catch(() => {})
  }
  if (!user.defaultAddress) {
    ElMessage.warning('请先添加收货地址')
    return router.push({ name: 'user', query: { tab: 'address' } })
  }

  grabbing.value = item.id
  try {
    const orderNo = await seckillApi.grab(item.id, user.defaultAddress.id)
    ElMessage.success('抢购成功，请在 15 分钟内完成支付')
    router.push({ name: 'payment', query: { orderNo } })
  } catch (e) {
    /* 抢光 / 重复抢购等由拦截器提示 */
  } finally {
    grabbing.value = 0
    // 无论成功失败都刷新一次，保证库存与进度是最新的
    await loadItems()
  }
}

const remind = () => ElMessage.success('已开启开抢提醒')
</script>

<template>
  <div class="container page-wrap">
    <!-- 秒杀 Banner -->
    <div class="seckill-hero">
      <div class="sh-left">
        <h1>⚡ 限时秒杀</h1>
        <p>天天低价 · 整点开抢 · 抢完即止 · 正品保障</p>
      </div>
      <div class="sh-right">
        <div class="sh-label">本场结束还剩</div>
        <CountdownTimer :hours="2" :minutes="15" :seconds="30" big />
      </div>
    </div>

    <!-- 场次 -->
    <div class="sessions">
      <div
        v-for="(s, i) in sessions"
        :key="s.time"
        class="session"
        :class="{ on: sessionIndex === i, done: s.state === 'done' }"
        @click="switchSession(i)"
      >
        <div class="t">{{ s.time }}</div>
        <div class="s">{{ s.label }}</div>
      </div>
    </div>

    <!-- 秒杀网格 -->
    <div class="sk-grid">
      <div v-for="item in seckillItems" :key="item.id" class="sk-card">
        <div class="sk-img" :class="item.product.c" @click="router.push({ name: 'product', params: { id: item.pid } })">
          <span class="tag" :class="{ plain: item.notStart }">{{ item.notStart ? '预告' : '秒杀' }}</span>
          <span v-if="!item.notStart" class="stock-badge">仅剩 {{ item.stock }} 件</span>
          商品图
        </div>
        <div class="sk-info">
          <div class="sk-title">{{ item.product.title }}</div>
          <div class="sk-price-row">
            <span class="sk-price"><small>¥</small>{{ item.price }}</span>
            <span class="sk-old">¥{{ item.oldPrice }}</span>
          </div>
          <div class="sk-progress"><i :style="{ width: item.percent + '%' }" /></div>
          <div class="sk-progress-text">
            <span v-if="!item.notStart">已抢 <b>{{ item.percent }}%</b></span>
            <span v-else>14:00 开抢</span>
            <span>{{ item.tip }}</span>
          </div>
          <button v-if="!item.notStart" class="sk-btn" :disabled="grabbing === item.id || item.soldout" @click="grab(item)">
            {{ item.soldout ? '已抢光' : grabbing === item.id ? '抢购中…' : '马上抢' }}
          </button>
          <button v-else class="sk-btn not-start" @click="remind">提醒我</button>
        </div>
      </div>
    </div>
  </div>
</template>
