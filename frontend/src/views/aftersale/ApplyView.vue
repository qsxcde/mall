<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import tradeApi from '@/api/trade'
import { fileApi } from '@/api/content'
import { useAfterSaleStore } from '@/stores/aftersale'
import { useUserStore } from '@/stores/user'
import { afterSaleReasons, afterSaleTypes } from '@/data/constants'
import { fmtMoney } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const afterStore = useAfterSaleStore()
const user = useUserStore()

// 可申请售后的订单：由后端 canAfterSale 判定（仅「待评价 / 已完成」且无处理中售后）
const orders = ref([])
const orderNo = ref('')
const loading = ref(false)
const submitting = ref(false)

const order = computed(() => orders.value.find((o) => o.no === orderNo.value) || null)

const form = reactive({
  type: route.query.type || 'return',
  reason: '',
  content: '',
  phone: ''
})
const fileList = ref([])

const currentType = computed(() => afterSaleTypes.find((t) => t.key === form.type))

onMounted(async () => {
  loading.value = true
  try {
    const page = await tradeApi.orders({ pageSize: 50 })
    orders.value = page.list.filter((o) => o.canAfterSale)
    const prefer = typeof route.query.order === 'string' ? route.query.order : ''
    orderNo.value = orders.value.some((o) => o.no === prefer) ? prefer : orders.value[0]?.no || ''
  } catch (e) {
    orders.value = []
  } finally {
    loading.value = false
  }
  // 联系电话默认带出账号手机号
  form.phone = user.info?.phone || ''
})

/** 凭证图片走 content 域上传接口 */
const uploadImage = async ({ file, onSuccess, onError }) => {
  try {
    onSuccess(await fileApi.upload(file, 'aftersale'))
  } catch (e) {
    onError(e)
  }
}

const submit = async () => {
  if (!orderNo.value) return ElMessage.warning('没有可申请售后的订单')
  if (!form.reason) return ElMessage.warning('请选择申请原因')
  submitting.value = true
  try {
    const images = fileList.value.map((f) => f.response?.url).filter(Boolean)
    // 退款金额由后端按订单实付金额计算，前端不传，避免被篡改
    const record = await afterStore.create({
      orderNo: orderNo.value,
      type: form.type,
      reason: form.reason,
      content: form.content,
      images,
      phone: form.phone
    })
    ElMessage.success('售后申请已提交，请等待商家审核')
    router.replace({ name: 'aftersale-detail', params: { id: record.id } })
  } catch (e) {
    /* 失败信息由拦截器提示 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'user', query: { tab: 'aftersale' } }">售后服务</el-breadcrumb-item>
      <el-breadcrumb-item>申请售后</el-breadcrumb-item>
    </el-breadcrumb>

    <!-- 服务类型 -->
    <div class="co-card">
      <div class="ttl">选择服务类型</div>
      <div class="as-types">
        <div
          v-for="t in afterSaleTypes"
          :key="t.key"
          class="as-type"
          :class="{ on: form.type === t.key }"
          @click="form.type = t.key"
        >
          <div class="as-type-name">{{ t.name }}</div>
          <div class="as-type-desc">{{ t.desc }}</div>
        </div>
      </div>
    </div>

    <!-- 关联订单 -->
    <div class="co-card">
      <div class="ttl">选择订单商品</div>
      <div class="as-order-row">
        <span class="k">订单号</span>
        <el-select v-model="orderNo" style="width:320px">
          <el-option
            v-for="o in orders"
            :key="o.no"
            :label="`${o.no}（${(o.product?.title || '商品').slice(0, 14)}…）`"
            :value="o.no"
          />
        </el-select>
      </div>
      <div v-if="order" class="co-item" style="border-bottom:none">
        <div class="ci" :class="order.product.c">图</div>
        <div class="cm">
          <div class="cn">{{ order.product.title }}</div>
          <div class="cs">{{ order.spec }} · 实付 ¥{{ fmtMoney(order.payAmount) }}</div>
        </div>
        <div class="cq">×{{ order.qty }}</div>
      </div>
    </div>

    <!-- 申请信息 -->
    <div class="co-card">
      <div class="ttl">申请信息</div>
      <el-form label-width="90px" style="max-width:680px">
        <el-form-item label="申请原因" required>
          <el-select v-model="form.reason" placeholder="请选择申请原因" style="width:100%">
            <el-option v-for="r in afterSaleReasons" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="问题描述">
          <el-input v-model="form.content" type="textarea" :rows="4" maxlength="300" show-word-limit placeholder="请补充问题描述，便于商家快速处理" />
        </el-form-item>
        <el-form-item label="上传凭证">
          <el-upload v-model:file-list="fileList" action="#" list-type="picture-card" :http-request="uploadImage" :limit="6">
            <el-icon><Plus /></el-icon>
            <template #tip><div class="as-tip">最多 6 张，支持 jpg / png / webp，单张不超过 10MB</div></template>
          </el-upload>
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.phone" style="width:240px" />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="submitting"
            :disabled="!orders.length"
            @click="submit"
          >提交申请</el-button>
          <el-button size="large" @click="router.back()">取消</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.as-types { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.as-type {
  border: 1px solid var(--border); border-radius: 10px; padding: 16px; text-align: center;
  cursor: pointer; transition: all .2s;
}
.as-type:hover { border-color: var(--primary); box-shadow: 0 4px 14px rgba(26,109,255,.12); }
.as-type.on { border-color: var(--primary); background: #f5f9ff; }
.as-type-name { font-size: 15px; font-weight: 600; }
.as-type-desc { font-size: 12px; color: var(--text-light); margin-top: 6px; }
.as-order-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.as-order-row .k { color: var(--text-light); font-size: 13px; }
.as-tip { font-size: 12px; color: var(--text-light); }
@media (max-width: 768px) {
  .as-types { grid-template-columns: repeat(2, 1fr); }
}
</style>
