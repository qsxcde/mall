<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { seckillApi } from '@/api/marketing'
import { useUserStore } from '@/stores/user'
import { fmtMoney } from '@/utils/format'
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

/** 抢购确认弹窗：pendingItem 为待确认的商品，null 表示弹窗内容已清空 */
const confirmVisible = ref(false)
const pendingItem = ref(null)
/** 弹窗展示的收货地址：默认地址优先，无默认则取第一条 */
const confirmAddress = computed(() => user.defaultAddress)

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
 * 点击「马上抢」：前置校验通过后打开确认弹窗，不产生任何订单。
 *
 * 秒杀不走购物车也没有结算页，这里补一层轻量确认：让用户在下单前
 * 看清商品、收货地址与实付金额，避免误购和地址错误。
 * 只有点了弹窗里的「确认抢购」才会真正调用下单接口。
 */
const openConfirm = async (item) => {
  if (item.soldout) return
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
  pendingItem.value = item
  confirmVisible.value = true
}

/** 弹窗内「更换地址」：去地址管理，返回后再次抢购即使用新地址 */
const goAddress = () => {
  confirmVisible.value = false
  router.push({ name: 'user', query: { tab: 'address' } })
}

/**
 * 立即抢购：由后端 Redis 原子预扣 + 一人一单。
 *
 * 成功后的返回取决于后端模式：同步模式直接给订单号；
 * 削峰模式只给受理请求号，需轮询结果接口。
 */
const doGrab = async (item) => {
  // 兜底防重：弹窗按钮可能被快速双击，保证同一时刻只有一个抢购请求
  if (grabbing.value) return
  const addressId = user.defaultAddress?.id
  if (!addressId) {
    ElMessage.warning('请先添加收货地址')
    return router.push({ name: 'user', query: { tab: 'address' } })
  }

  grabbing.value = item.id
  try {
    const data = await seckillApi.grab(item.id, addressId)
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

/** 确认抢购：关掉弹窗后立即下单（后端生成订单 → 收银台） */
const confirmGrab = async () => {
  const item = pendingItem.value
  if (!item || grabbing.value) return
  confirmVisible.value = false
  await doGrab(item)
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
          <button v-if="!item.notStart" class="sk-btn" :disabled="grabbing !== 0 || item.soldout" @click="openConfirm(item)">
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

    <!-- 抢购确认：下单前的最后一道确认，避免误购与地址错误 -->
    <el-dialog
      v-model="confirmVisible"
      title="确认抢购"
      width="520px"
      @closed="pendingItem = null"
    >
      <div v-if="pendingItem" class="sk-confirm">
        <div class="skc-goods">
          <div class="skc-img" :class="pendingItem.product.c">商品图</div>
          <div class="skc-main">
            <div class="skc-title">{{ pendingItem.product.title }}</div>
            <div v-if="pendingItem.product.spec" class="skc-spec">{{ pendingItem.product.spec }}</div>
            <div class="skc-price-row">
              <span class="skc-price"><small>¥</small>{{ fmtMoney(pendingItem.price) }}</span>
              <span class="skc-old">¥{{ fmtMoney(pendingItem.oldPrice) }}</span>
              <span class="skc-tag">限时秒杀</span>
            </div>
          </div>
        </div>

        <div class="skc-row">
          <span class="skc-label">收货地址</span>
          <div class="skc-addr">
            <div class="nm">
              {{ confirmAddress?.name }}
              <span class="ph">{{ confirmAddress?.phone }}</span>
            </div>
            <div class="dt">{{ confirmAddress?.region }} {{ confirmAddress?.detail }}</div>
          </div>
          <span class="skc-edit" @click="goAddress">更换 ›</span>
        </div>

        <div class="skc-row">
          <span class="skc-label">购买数量</span>
          <div class="skc-qty">1 件 <span class="skc-note">秒杀限购 1 件</span></div>
        </div>

        <div class="skc-row">
          <span class="skc-label">实付金额</span>
          <div class="skc-total">¥{{ fmtMoney(pendingItem.price) }} <span class="skc-note">包邮</span></div>
        </div>

        <div class="skc-tips">
          <p>确认后将立即锁定库存，请在 <b>15 分钟</b>内完成支付，超时订单将自动取消。</p>
          <p>当前仅剩 <b>{{ pendingItem.stock }}</b> 件，手慢无。</p>
        </div>
      </div>

      <template #footer>
        <el-button @click="confirmVisible = false">再想想</el-button>
        <el-button type="danger" class="skc-submit" @click="confirmGrab">确认抢购</el-button>
      </template>
    </el-dialog>
  </div>
</template>
