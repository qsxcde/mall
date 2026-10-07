<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
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
/** 已受理、等待落库的商品 ID：削峰模式下按钮需要显示「排队中」 */
const queueingItemId = ref(0)

/**
 * 削峰模式下抢购接口返回的是 32 位十六进制请求号，而同步模式返回 GM 开头的订单号。
 * 用它来分辨当前后端处于哪种模式 —— 这样前端不需要读后端配置，两种模式都能跑。
 */
const REQUEST_ID_PATTERN = /^[0-9a-f]{32}$/i

/** 轮询节奏：与后端抢购接口的 10s 超时对齐（14 × 700ms ≈ 9.8s） */
const POLL_INTERVAL_MS = 700
const POLL_MAX_ATTEMPTS = 14

let pollTimer = null

const stopPolling = () => {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

// 离开页面必须停掉轮询：否则定时器仍会触发跳转，把已经离开的用户拽走
onUnmounted(stopPolling)

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

/** 抢购成功：统一走收银台 */
const goPay = (orderNo) => {
  ElMessage.success('抢购成功，请在 15 分钟内完成支付')
  router.push({ name: 'payment', query: { orderNo } })
}

/**
 * 削峰模式：轮询抢购结果直到终态。
 *
 * 「已受理」不再等于「已抢到」，必须拿到终态才能决定是跳收银台还是提示失败。
 */
const pollGrabResult = (requestId) =>
  new Promise((resolve) => {
    let attempts = 0
    const tick = async () => {
      attempts += 1
      try {
        const res = await seckillApi.grabResult(requestId)
        if (res.status === 'SUCCESS') {
          goPay(res.orderNo)
          return resolve()
        }
        if (res.status === 'FAILED') {
          ElMessage.error(res.message || '抢购失败')
          return resolve()
        }
      } catch (e) {
        // 轮询期间的瞬时失败不打断流程：继续重试，最终由超时兜底
      }
      if (attempts >= POLL_MAX_ATTEMPTS) {
        // 超时 ≠ 失败：消息可能仍在队列里，引导用户自查，避免误报「抢购失败」
        ElMessage.warning('抢购结果仍在处理中，可稍后在「我的订单」查看')
        router.push({ name: 'orders' })
        return resolve()
      }
      pollTimer = setTimeout(tick, POLL_INTERVAL_MS)
    }
    tick()
  })

/**
 * 立即抢购：由后端 Redis 原子预扣 + 一人一单。
 *
 * 秒杀不走购物车，所以需要先确定收货地址。成功后能否当场拿到订单号取决于后端模式：
 * 同步模式直接返回订单号；削峰模式只返回受理请求号，需轮询结果接口。
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
    const data = await seckillApi.grab(item.id, user.defaultAddress.id)
    if (REQUEST_ID_PATTERN.test(data || '')) {
      // 削峰模式：先明确告知已受理，避免用户以为按钮没响应而反复点击
      queueingItemId.value = item.id
      ElMessage.info('抢购请求已受理，正在排队处理…')
      await pollGrabResult(data)
    } else {
      goPay(data)
    }
  } catch (e) {
    /* 抢光 / 重复抢购等由拦截器提示 */
  } finally {
    grabbing.value = 0
    queueingItemId.value = 0
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
          <!-- 抢购与排队期间禁用全部按钮：轮询期间若允许再次抢购，会同时挂起两个轮询 -->
          <button v-if="!item.notStart" class="sk-btn" :disabled="grabbing !== 0 || item.soldout" @click="grab(item)">
            {{
              item.soldout
                ? '已抢光'
                : queueingItemId === item.id
                  ? '排队中…'
                  : grabbing === item.id
                    ? '抢购中…'
                    : '马上抢'
            }}
          </button>
          <button v-else class="sk-btn not-start" @click="remind">提醒我</button>
        </div>
      </div>
    </div>
  </div>
</template>
