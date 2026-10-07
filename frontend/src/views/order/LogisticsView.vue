<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import tradeApi from '@/api/trade'

const route = useRoute()
const router = useRouter()

// 物流页只需要订单详情里的「物流 + 收货 + 商品」三块，复用同一个接口
const detail = ref(null)

const load = async () => {
  try {
    detail.value = await tradeApi.orderDetail(route.params.no)
  } catch (e) {
    detail.value = null
  }
}
onMounted(load)

const order = computed(() => detail.value?.order || null)
const address = computed(() => detail.value?.receiver || { name: '', phone: '', region: '', detail: '' })
const logistic = computed(() => {
  const lg = detail.value?.logistics
  return lg && lg.steps?.length ? lg : null
})

const copy = async () => {
  if (!logistic.value?.no) return
  try {
    await navigator.clipboard.writeText(logistic.value.no)
    ElMessage.success('运单号已复制')
  } catch (e) {
    ElMessage.success(`运单号：${logistic.value.no}`)
  }
}
</script>

<template>
  <div class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'orders' }">我的订单</el-breadcrumb-item>
      <el-breadcrumb-item :to="{ name: 'order-detail', params: { no: route.params.no } }">订单详情</el-breadcrumb-item>
      <el-breadcrumb-item>物流跟踪</el-breadcrumb-item>
    </el-breadcrumb>

    <template v-if="logistic">
      <!-- 运单信息 -->
      <div class="co-card">
        <div class="ttl">物流信息</div>
        <div class="lg-head">
          <div>
            <span class="lg-tag">{{ logistic.company }}</span>
            <b class="lg-no">{{ logistic.no }}</b>
          </div>
          <div class="lg-ops">
            <el-button size="small" @click="copy">复制运单号</el-button>
            <el-button size="small" @click="ElMessage.info(`拨打 ${logistic.company} 客服 ${logistic.phone}（演示）`)">
              联系快递
            </el-button>
          </div>
        </div>
        <div class="lg-addr">
          <span class="k">收货信息</span>
          {{ address.name }} {{ address.phone }} · {{ address.region }} {{ address.detail }}
        </div>
        <div v-if="order" class="lg-goods">
          <span class="k">商品</span>
          <span class="thumb" :class="order.product.c">图</span>
          {{ order.product.title }} <span class="muted">×{{ order.qty }}</span>
        </div>
      </div>

      <!-- 轨迹 -->
      <div class="co-card">
        <div class="ttl">物流轨迹</div>
        <el-timeline>
          <el-timeline-item
            v-for="(s, i) in logistic.steps"
            :key="i"
            :timestamp="s.time"
            :color="s.on ? '#1a6dff' : '#c0c4cc'"
            :size="s.on ? 'large' : 'normal'"
            placement="top"
          >
            <span :style="{ color: s.on ? '#333' : '#888', fontWeight: s.on ? 600 : 400 }">{{ s.text }}</span>
          </el-timeline-item>
        </el-timeline>
      </div>
    </template>

    <el-empty v-else description="暂无物流信息（订单可能尚未发货）">
      <el-button type="primary" @click="router.push({ name: 'orders' })">返回我的订单</el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.lg-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.lg-tag { font-size: 12px; color: var(--primary); background: #f0f6ff; padding: 2px 10px; border-radius: 4px; }
.lg-no { margin-left: 12px; font-size: 15px; letter-spacing: .5px; }
.lg-ops { display: flex; gap: 8px; }
.lg-addr, .lg-goods { display: flex; align-items: center; gap: 10px; font-size: 13px; color: #555; margin-top: 14px; line-height: 1.7; }
.lg-addr .k, .lg-goods .k { flex-shrink: 0; color: var(--text-light); }
.lg-goods .thumb { width: 34px; height: 34px; border-radius: 6px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; color: rgba(0,0,0,.18); font-size: 11px; }
.lg-goods .muted { color: var(--text-light); }
</style>
