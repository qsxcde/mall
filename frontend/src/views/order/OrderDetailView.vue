<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import tradeApi from '@/api/trade'
import { useCartStore } from '@/stores/cart'
import { fmtMoney } from '@/utils/format'
import { useNarrow } from '@/utils/useNarrow'

const route = useRoute()
const router = useRouter()
const cart = useCartStore()

// 订单详情（含收货信息、支付信息、进度时间轴、物流摘要）一次拿到
const detail = ref(null)
const loading = ref(true)

const load = async () => {
  loading.value = true
  try {
    detail.value = await tradeApi.orderDetail(route.params.no)
  } catch (e) {
    detail.value = null
  } finally {
    loading.value = false
  }
}
onMounted(load)

const order = computed(() => detail.value?.order || null)
const address = computed(() => detail.value?.receiver || { name: '', phone: '', region: '', detail: '' })
// 未发货时后端返回 null，这里也没有步骤，统一收敛成 null 让模板整块隐藏
const logistic = computed(() => {
  const lg = detail.value?.logistics
  return lg && lg.steps?.length ? lg : null
})
const extra = computed(() => ({
  createTime: order.value?.createTime || '',
  payMethod: detail.value?.payMethodText || '—',
  payTime: detail.value?.payTime || '待支付',
  tradeNo: detail.value?.tradeNo || '—',
  timeline: detail.value?.timeline || []
}))
const status = computed(() => ({ cls: order.value?.statusCls || '', text: order.value?.statusText || '' }))

// 金额直接取后端订单，日后再改价规则前端无需跟着改
const goodsAmount = computed(() => order.value?.goodsAmount ?? 0)
const shippingFee = computed(() => order.value?.shippingFee ?? 0)
const discount = computed(() => order.value?.discount ?? 0)
const payTotal = computed(() => order.value?.payAmount ?? 0)

/** 窄屏下 el-descriptions 降为单列，否则 375px 宽度会挤压成两列 */
const narrow = useNarrow()

const go = (name, query) => router.push({ name, params: { no: route.params.no }, query })

const handle = async (label) => {
  try {
    if (label === '提醒发货') {
      await tradeApi.remind(route.params.no)
    } else if (label === '确认收货') {
      await tradeApi.confirm(route.params.no)
    }
    ElMessage.success(`${label}成功`)
    await load()
  } catch (e) {
    /* 失败信息由拦截器提示 */
  }
}

const cancel = () => {
  ElMessageBox.confirm('确定取消该订单吗？取消后库存将回滚。', '提示', { type: 'warning' })
    .then(async () => {
      await tradeApi.cancel(route.params.no, '用户取消')
      ElMessage.success('订单已取消')
      await load()
    })
    .catch(() => {})
}

/** 再次购买：把订单内商品按原数量重新加购 */
const again = async () => {
  const items = order.value?.items || []
  if (!items.length) return
  try {
    await cart.addBatch(items.map((i) => ({ productId: i.productId, qty: i.qty || 1, spec: i.spec })))
    ElMessage.success('已加入购物车')
    router.push({ name: 'cart' })
  } catch (e) {
    /* 失败信息由拦截器提示 */
  }
}
</script>

<template>
  <div v-if="order" class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item :to="{ name: 'orders' }">我的订单</el-breadcrumb-item>
      <el-breadcrumb-item>订单详情</el-breadcrumb-item>
    </el-breadcrumb>

    <!-- 状态头 -->
    <div class="od-hero">
      <div class="od-hero-left">
        <div class="od-hero-status" :class="status.cls">{{ status.text }}</div>
        <div class="od-hero-tip">
          <template v-if="order.status === 'pay'">请尽快完成支付，超时订单将自动取消</template>
          <template v-else-if="order.status === 'ship'">商家正在备货，请耐心等待发货</template>
          <template v-else-if="order.status === 'recv'">包裹正在途中，请注意查收</template>
          <template v-else>感谢你的购买，期待你的评价</template>
        </div>
      </div>
      <div class="od-hero-ops">
        <template v-if="order.status === 'pay'">
          <el-button @click="cancel">取消订单</el-button>
          <el-button type="primary" @click="go('payment', { orderNo: order.no })">去支付</el-button>
        </template>
        <template v-else-if="order.status === 'ship'">
          <el-button @click="handle('提醒发货')">提醒发货</el-button>
          <el-button type="primary" @click="go('aftersale-apply', { order: order.no })">申请售后</el-button>
        </template>
        <template v-else-if="order.status === 'recv'">
          <el-button @click="go('logistics')">查看物流</el-button>
          <el-button type="primary" @click="handle('确认收货')">确认收货</el-button>
        </template>
        <template v-else>
          <el-button @click="go('aftersale-apply', { order: order.no })">申请售后</el-button>
          <el-button type="primary" @click="go('review')">去评价</el-button>
          <el-button @click="again">再次购买</el-button>
        </template>
      </div>
    </div>

    <!-- 物流摘要 -->
    <div v-if="logistic" class="co-card">
      <div class="ttl">物流信息 <span class="edit" @click="go('logistics')">查看全部</span></div>
      <div class="lg-line">
        <span class="lg-tag">{{ logistic.company }}</span>
        <span>{{ logistic.steps[0].text }}</span>
      </div>
      <div class="lg-time">{{ logistic.steps[0].time }} · 运单号 {{ logistic.no }}</div>
    </div>

    <!-- 收货地址 -->
    <div class="co-card">
      <div class="ttl">收货地址</div>
      <div class="od-addr">
        <div class="nm">{{ address.name }} <span class="ph">{{ address.phone }}</span></div>
        <div class="dt">{{ address.region }} {{ address.detail }}</div>
      </div>
    </div>

    <!-- 商品清单 -->
    <div class="co-card">
      <div class="ttl">商品清单</div>
      <div class="co-item">
        <div class="ci" :class="order.product.c" @click="router.push({ name: 'product', params: { id: order.product.id } })">图</div>
        <div class="cm">
          <div class="cn">{{ order.product.title }}</div>
          <div class="cs">{{ order.spec }}</div>
        </div>
        <div class="cp">¥{{ fmtMoney(order.price) }}</div>
        <div class="cq">×{{ order.qty }}</div>
      </div>
    </div>

    <!-- 金额 / 支付信息 -->
    <div class="co-card">
      <div class="ttl">订单信息</div>
      <el-descriptions :column="narrow ? 1 : 2" border>
        <el-descriptions-item label="订单号">{{ order.no }}</el-descriptions-item>
        <el-descriptions-item label="下单时间">{{ extra.createTime }}</el-descriptions-item>
        <el-descriptions-item label="支付方式">{{ extra.payMethod }}</el-descriptions-item>
        <el-descriptions-item label="支付时间">{{ extra.payTime }}</el-descriptions-item>
        <el-descriptions-item label="交易号">{{ extra.tradeNo }}</el-descriptions-item>
        <el-descriptions-item label="商品总额">¥{{ fmtMoney(goodsAmount) }}</el-descriptions-item>
        <el-descriptions-item label="运费">¥{{ fmtMoney(shippingFee) }}</el-descriptions-item>
        <el-descriptions-item label="优惠减免">-¥{{ fmtMoney(discount) }}</el-descriptions-item>
        <el-descriptions-item label="实付款">
          <b style="color:var(--price)">¥{{ fmtMoney(payTotal) }}</b>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <!-- 状态时间轴 -->
    <div class="co-card">
      <div class="ttl">订单进度</div>
      <el-timeline>
        <el-timeline-item
          v-for="(t, i) in extra.timeline"
          :key="i"
          :timestamp="t.time || '—'"
          :color="t.done ? '#1a6dff' : '#c0c4cc'"
          placement="top"
        >
          <span :style="{ color: t.done ? '#333' : '#bbb', fontWeight: t.done ? 600 : 400 }">{{ t.text }}</span>
        </el-timeline-item>
      </el-timeline>
    </div>
  </div>

  <el-result v-else icon="warning" title="订单不存在" sub-title="未找到该订单，可能已被删除">
    <template #extra>
      <el-button type="primary" @click="router.push({ name: 'orders' })">返回我的订单</el-button>
    </template>
  </el-result>
</template>

<style scoped>
.od-hero {
  display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap;
  background: linear-gradient(120deg, #1a6dff 0%, #4da3ff 100%);
  color: #fff; border-radius: 12px; padding: 22px 26px;
}
.od-hero-status { font-size: 22px; font-weight: bold; }
.od-hero-tip { font-size: 13px; opacity: .92; margin-top: 6px; }
.od-hero-ops { display: flex; gap: 10px; flex-wrap: wrap; }
.od-hero-ops :deep(.el-button) { border: none; }
.od-hero-ops :deep(.el-button--primary) { background: #fff; color: var(--primary); font-weight: 600; }
.lg-line { display: flex; align-items: flex-start; gap: 10px; font-size: 14px; line-height: 1.7; }
.lg-tag { flex-shrink: 0; font-size: 12px; color: var(--primary); background: #f0f6ff; padding: 1px 8px; border-radius: 4px; }
.lg-time { font-size: 12px; color: var(--text-light); margin-top: 8px; }
.od-addr .nm { font-size: 15px; font-weight: 600; }
.od-addr .ph { color: var(--text-light); font-size: 13px; margin-left: 10px; font-weight: normal; }
.od-addr .dt { font-size: 13px; color: #555; margin-top: 8px; line-height: 1.7; }
.co-item .ci { cursor: pointer; }
</style>
