<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fmtMoney } from '@/utils/format'
import { useAfterSaleStore } from '@/stores/aftersale'
import { SERVICE_HOTLINE, SERVICE_HOURS } from '@/data/constants'
import { useNarrow } from '@/utils/useNarrow'

const route = useRoute()
const router = useRouter()
const afterStore = useAfterSaleStore()

// 详情由接口异步返回，必须先存到 ref 再给模板用
const record = ref(null)
const loading = ref(true)

const load = async () => {
  loading.value = true
  try {
    record.value = await afterStore.fetchDetail(route.params.id)
  } catch (e) {
    record.value = null
  } finally {
    loading.value = false
  }
}
onMounted(load)

// 是否仍可撤销由后端给出（canCancel），前端不再自己推导状态规则：
// 商家已同意退货 / 已完成 / 已拒绝 / 已取消 都不允许撤销。
const canCancel = computed(() => record.value?.canCancel === true)
const isRejected = computed(() => record.value?.status === 'rejected')

/** 窄屏下 el-descriptions 降为单列 */
const narrow = useNarrow()

const cancel = () => {
  ElMessageBox.confirm('确定取消该售后申请吗？取消后需重新申请。', '提示', { type: 'warning' })
    .then(async () => {
      await afterStore.cancel(record.value.id)
      ElMessage.success('售后申请已取消')
      router.push({ name: 'user', query: { tab: 'aftersale' } })
    })
    .catch(() => {})
}
const contact = () => ElMessage.info(`客服热线 ${SERVICE_HOTLINE}（${SERVICE_HOURS}）`)
</script>

<template>
  <div v-if="record" class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'user', query: { tab: 'aftersale' } }"
        >售后服务</el-breadcrumb-item
      >
      <el-breadcrumb-item>售后详情</el-breadcrumb-item>
    </el-breadcrumb>

    <!-- 状态头 -->
    <div class="as-hero" :class="{ done: !canCancel, rejected: isRejected }">
      <div>
        <div class="as-hero-status">{{ record.statusText }}</div>
        <div class="as-hero-tip">{{ record.statusTip }}</div>
        <!-- 商家拒绝时必须把理由摆在最显眼的位置，否则用户只看到一个「已拒绝」无从申诉 -->
        <div v-if="isRejected && record.rejectReason" class="as-hero-reason">
          拒绝理由：{{ record.rejectReason }}
        </div>
      </div>
      <div class="as-hero-ops">
        <el-button v-if="canCancel" @click="cancel">取消申请</el-button>
        <el-button @click="contact">联系客服</el-button>
      </div>
    </div>

    <!-- 基本信息 -->
    <div class="co-card">
      <div class="ttl">售后信息</div>
      <el-descriptions :column="narrow ? 1 : 2" border>
        <el-descriptions-item label="售后单号">{{ record.id }}</el-descriptions-item>
        <el-descriptions-item label="售后类型">{{ record.typeName }}</el-descriptions-item>
        <el-descriptions-item label="关联订单">
          <el-link
            v-if="record.orderNo"
            type="primary"
            :underline="false"
            @click="router.push({ name: 'order-detail', params: { no: record.orderNo } })"
          >
            {{ record.orderNo }}
          </el-link>
          <span v-else>—</span>
        </el-descriptions-item>
        <el-descriptions-item label="申请时间">{{ record.applyTime }}</el-descriptions-item>
        <el-descriptions-item label="申请原因">{{ record.reason }}</el-descriptions-item>
        <el-descriptions-item label="涉及金额">
          <b style="color: var(--price)">¥{{ fmtMoney(record.amount) }}</b>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <!-- 售后商品 -->
    <div class="co-card">
      <div class="ttl">售后商品</div>
      <div class="co-item" style="border-bottom: none">
        <div class="ci" :class="record.c">图</div>
        <div class="cm">
          <div class="cn">{{ record.name }}</div>
          <div class="cs">{{ record.desc }}</div>
        </div>
      </div>
    </div>

    <!-- 处理进度 -->
    <div class="co-card">
      <div class="ttl">处理进度</div>
      <el-timeline>
        <el-timeline-item
          v-for="(s, i) in record.steps"
          :key="i"
          :timestamp="s.time || '—'"
          :color="s.done ? '#1a6dff' : '#c0c4cc'"
          placement="top"
        >
          <span :style="{ color: s.done ? '#333' : '#bbb', fontWeight: s.done ? 600 : 400 }">{{
            s.text
          }}</span>
        </el-timeline-item>
      </el-timeline>
    </div>
  </div>

  <el-result v-else icon="warning" title="售后记录不存在">
    <template #extra>
      <el-button type="primary" @click="router.push({ name: 'user', query: { tab: 'aftersale' } })"
        >返回售后服务</el-button
      >
    </template>
  </el-result>
</template>

<style scoped>
.as-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  background: linear-gradient(120deg, #1a6dff 0%, #4da3ff 100%);
  color: #fff;
  border-radius: 12px;
  padding: 22px 26px;
}
.as-hero.done {
  background: linear-gradient(120deg, #21a366 0%, #4fd18b 100%);
}
/* 拒绝态用暖色而非绿色：绿色会让用户误以为处理成功 */
.as-hero.rejected {
  background: linear-gradient(120deg, #d9534f 0%, #f08a86 100%);
}
.as-hero-status {
  font-size: 22px;
  font-weight: bold;
}
.as-hero-tip {
  font-size: 13px;
  opacity: 0.92;
  margin-top: 6px;
}
.as-hero-reason {
  margin-top: 10px;
  font-size: 13px;
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.18);
}
.as-hero-ops :deep(.el-button) {
  border: none;
  background: #fff;
  color: var(--primary);
  font-weight: 600;
}
</style>
