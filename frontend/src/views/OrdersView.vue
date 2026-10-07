<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import tradeApi from '@/api/trade'
import { STATUS_CODE_BY_KEY, orderStatusMap } from '@/data/constants'
import { fmtMoney } from '@/utils/format'

const route = useRoute()
const router = useRouter()

// 订单状态：全部 / 待付款 / 待发货 / 待收货 / 待评价
const statusTabs = [
  { key: 'all', name: '全部', icon: '📋' },
  { key: 'pay', name: '待付款', icon: '💰' },
  { key: 'ship', name: '待发货', icon: '📦' },
  { key: 'recv', name: '待收货', icon: '🚚' },
  { key: 'cmt', name: '待评价', icon: '⭐' }
]

const activeStatus = ref(statusTabs.some((t) => t.key === route.query.status) ? route.query.status : 'all')
const keyword = ref('')

// 列表、总数、各状态计数全部来自后端
const shownOrders = ref([])
const total = ref(0)
const counts = ref({ all: 0, pay: 0, ship: 0, recv: 0, cmt: 0 })
const loading = ref(false)

const countBy = (key) => counts.value[key] ?? 0

const load = async () => {
  loading.value = true
  try {
    const status = activeStatus.value === 'all' ? undefined : STATUS_CODE_BY_KEY[activeStatus.value]
    const [page, statusCounts] = await Promise.all([
      tradeApi.orders({ status, keyword: keyword.value.trim(), pageSize: 20 }),
      tradeApi.statusCounts()
    ])
    shownOrders.value = page.list
    total.value = page.total
    counts.value = {
      all: Number(statusCounts.all || 0),
      pay: Number(statusCounts.pay || 0),
      ship: Number(statusCounts.ship || 0),
      recv: Number(statusCounts.recv || 0),
      cmt: Number(statusCounts.cmt || 0)
    }
  } catch (e) {
    shownOrders.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(load)

watch(activeStatus, (v) => {
  router.replace({ name: 'orders', query: v === 'all' ? {} : { status: v } })
  load()
})
watch(() => route.query.status, (v) => {
  if (v && v !== activeStatus.value) activeStatus.value = v
})

// 关键词搜索走服务端，做个防抖避免每敲一个字就请求
let searchTimer = null
watch(keyword, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(load, 300)
})

const goUserCenter = (tab) => router.push({ name: 'user', query: tab ? { tab } : {} })

const goDetail = (no) => router.push({ name: 'order-detail', params: { no } })
const goLogistics = (no) => router.push({ name: 'logistics', params: { no } })
const goReview = (no) => router.push({ name: 'review', params: { no } })
const goApply = (no) => router.push({ name: 'aftersale-apply', query: { order: no } })
const goPay = (order) => router.push({ name: 'payment', query: { orderNo: order.no } })

const remind = async (order) => {
  try {
    await tradeApi.remind(order.no)
    ElMessage.success('已提醒商家发货')
  } catch (e) {
    /* 拦截器已提示 */
  }
}

const confirmReceipt = async (order) => {
  ElMessageBox.confirm('确认已收到商品？确认后订单将进入待评价状态。', '提示', { type: 'warning' })
    .then(async () => {
      await tradeApi.confirm(order.no)
      ElMessage.success('确认收货成功')
      load()
    })
    .catch(() => {})
}

const cancelOrder = (order) => {
  ElMessageBox.confirm('确定取消该订单吗？取消后库存将回滚。', '提示', { type: 'warning' })
    .then(async () => {
      await tradeApi.cancel(order.no, '用户取消')
      ElMessage.success('订单已取消')
      load()
    })
    .catch(() => {})
}
</script>

<template>
  <div class="container page-wrap oc-wrap">
    <!-- 页头 -->
    <div class="oc-head">
      <div>
        <div class="oc-title">我的订单</div>
        <div class="oc-sub">共 {{ total }} 笔订单，按状态快速筛选与处理</div>
      </div>
      <div class="oc-head-actions">
        <el-input v-model="keyword" placeholder="搜索订单号 / 商品名称" clearable style="width: 260px" />
        <el-button @click="goUserCenter('aftersale')">售后服务</el-button>
        <el-button type="primary" plain @click="goUserCenter()">个人中心</el-button>
      </div>
    </div>

    <!-- 状态概览卡 -->
    <div class="oc-stats">
      <div
        v-for="t in statusTabs.slice(1)"
        :key="t.key"
        class="oc-stat"
        :class="{ on: activeStatus === t.key, empty: countBy(t.key) === 0 }"
        @click="activeStatus = activeStatus === t.key ? 'all' : t.key"
      >
        <div class="oc-stat-ico">{{ t.icon }}</div>
        <div class="oc-stat-num">{{ countBy(t.key) }}</div>
        <div class="oc-stat-name">{{ t.name }}</div>
      </div>
    </div>

    <!-- 状态标签 -->
    <div class="od-sub">
      <a
        v-for="t in statusTabs"
        :key="t.key"
        :class="{ on: activeStatus === t.key }"
        @click="activeStatus = t.key"
      >
        {{ t.name }}<span class="cnt">({{ countBy(t.key) }})</span>
      </a>
    </div>

    <!-- 订单列表 -->
    <div v-if="shownOrders.length" class="od-list">
      <div v-for="o in shownOrders" :key="o.no" class="od-card">
        <div class="od-head">
          <span>订单号：{{ o.no }}</span>
          <span class="od-status" :class="orderStatusMap[o.status].cls">{{ orderStatusMap[o.status].text }}</span>
        </div>
        <div class="od-body">
          <div class="od-img" :class="o.product.c" @click="router.push({ name: 'product', params: { id: o.product.id } })">图</div>
          <div class="od-meta">
            <div class="od-name" @click="router.push({ name: 'product', params: { id: o.product.id } })">{{ o.product.title }}</div>
            <div class="od-spec">{{ o.spec }}</div>
          </div>
          <div class="od-price">¥{{ fmtMoney(o.price) }} <small>×{{ o.qty }}</small></div>
        </div>
        <div class="od-foot">
          <span class="od-total">合计：<b>¥{{ fmtMoney(o.price * o.qty) }}</b></span>
          <span class="od-ops">
            <template v-if="o.status === 'pay'">
              <el-button size="small" @click="goDetail(o.no)">查看详情</el-button>
              <el-button size="small" @click="cancelOrder(o)">取消订单</el-button>
              <el-button size="small" type="primary" @click="goPay(o)">去支付</el-button>
            </template>
            <template v-else-if="o.status === 'ship'">
              <el-button size="small" @click="goDetail(o.no)">查看详情</el-button>
              <el-button size="small" type="primary" @click="remind(o)">提醒发货</el-button>
            </template>
            <template v-else-if="o.status === 'recv'">
              <el-button size="small" @click="goDetail(o.no)">查看详情</el-button>
              <el-button size="small" @click="goLogistics(o.no)">查看物流</el-button>
              <el-button size="small" type="primary" @click="confirmReceipt(o)">确认收货</el-button>
            </template>
            <template v-else>
              <el-button size="small" @click="goDetail(o.no)">查看详情</el-button>
              <el-button size="small" @click="goApply(o.no)">申请售后</el-button>
              <el-button size="small" type="primary" @click="goReview(o.no)">去评价</el-button>
            </template>
          </span>
        </div>
      </div>
    </div>

    <el-empty v-else :description="keyword ? '没有匹配的订单' : '暂无相关订单'">
      <el-button type="primary" @click="router.push({ name: 'home' })">去逛逛</el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.oc-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  background: #fff;
  border-radius: 12px;
  padding: 20px 24px;
}
.oc-title { font-size: 20px; font-weight: bold; }
.oc-sub { font-size: 13px; color: var(--text-light); margin-top: 6px; }
.oc-head-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.oc-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-top: 16px; }
.oc-stat {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 18px;
  text-align: center;
  cursor: pointer;
  transition: all .2s;
}
.oc-stat:hover { border-color: var(--primary); box-shadow: 0 4px 14px rgba(26,109,255,.12); transform: translateY(-2px); }
.oc-stat.on { border-color: var(--primary); background: #f5f9ff; }
.oc-stat.empty { opacity: .6; }
.oc-stat-ico { font-size: 24px; }
.oc-stat-num { font-size: 22px; font-weight: bold; color: var(--primary); margin-top: 6px; }
.oc-stat-name { font-size: 13px; color: #666; margin-top: 2px; }
.od-sub { margin-top: 16px; background: #fff; border-radius: 12px 12px 0 0; padding: 0 20px; }
.od-sub .cnt { color: var(--text-light); font-size: 12px; }
.od-list { margin-top: 14px; }
.od-name { cursor: pointer; }
.od-name:hover { color: var(--primary); }
.od-img { cursor: pointer; }
@media (max-width: 768px) {
  .oc-stats { grid-template-columns: repeat(2, 1fr); }
}
</style>
