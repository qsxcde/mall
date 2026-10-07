<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import tradeApi from '@/api/trade'
import reviewApi from '@/api/review'
import { fileApi } from '@/api/content'
import { reviewDimensions } from '@/data/constants'

const route = useRoute()
const router = useRouter()

// 订单信息（含商品）来自后端，用于展示「评价商品」区块
const detail = ref(null)
const loading = ref(true)
const submitting = ref(false)

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

const form = reactive({
  desc: 5,
  logistics: 5,
  service: 5,
  content: '',
  anonymous: false
})
const fileList = ref([])

const avgScore = computed(() =>
  ((form.desc + form.logistics + form.service) / 3).toFixed(1)
)

/** 自定义上传：走 content 域接口（MinIO / 本地磁盘），成功后把 URL 挂到文件项上 */
const uploadImage = async ({ file, onSuccess, onError }) => {
  try {
    onSuccess(await fileApi.upload(file, 'review'))
  } catch (e) {
    onError(e)
  }
}

const submit = async () => {
  if (!order.value) return
  if (!form.content.trim()) return ElMessage.warning('请填写评价内容')
  submitting.value = true
  try {
    const images = fileList.value.map((f) => f.response?.url).filter(Boolean)
    // 三维评分 + 图文，提交后订单由后端流转为「已完成」
    const count = await reviewApi.submit({
      orderNo: order.value.no,
      scoreDesc: form.desc,
      scoreLogistics: form.logistics,
      scoreService: form.service,
      content: form.content.trim(),
      images,
      anonymous: form.anonymous
    })
    ElMessage.success(`评价提交成功（${count} 件商品），综合 ${avgScore.value} 分`)
    router.push({ name: 'order-detail', params: { no: order.value.no } })
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
      <el-breadcrumb-item :to="{ name: 'orders' }">我的订单</el-breadcrumb-item>
      <el-breadcrumb-item :to="{ name: 'order-detail', params: { no: route.params.no } }">订单详情</el-breadcrumb-item>
      <el-breadcrumb-item>发表评价</el-breadcrumb-item>
    </el-breadcrumb>

    <template v-if="order">
      <div class="co-card">
        <div class="ttl">评价商品</div>
        <div class="co-item">
          <div class="ci" :class="order.product.c">图</div>
          <div class="cm">
            <div class="cn">{{ order.product.title }}</div>
            <div class="cs">{{ order.spec }}</div>
          </div>
        </div>
      </div>

      <div class="co-card">
        <div class="ttl">评分</div>
        <div class="rv-dims">
          <div v-for="d in reviewDimensions" :key="d.key" class="rv-dim">
            <span class="rv-label">{{ d.label }}</span>
            <el-rate v-model="form[d.key]" show-text :texts="['很差', '较差', '一般', '满意', '非常满意']" />
          </div>
        </div>
      </div>

      <div class="co-card">
        <div class="ttl">评价内容</div>
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="5"
          maxlength="500"
          show-word-limit
          placeholder="说说这款商品的使用感受，帮助更多人了解它～"
        />
        <div class="rv-upload">
          <el-upload
            v-model:file-list="fileList"
            action="#"
            list-type="picture-card"
            :http-request="uploadImage"
            :limit="6"
          >
            <el-icon><Plus /></el-icon>
            <template #tip>
              <div class="rv-tip">最多上传 6 张图片，支持 jpg / png / webp，单张不超过 10MB</div>
            </template>
          </el-upload>
        </div>
        <el-checkbox v-model="form.anonymous" style="margin-top:8px">匿名评价</el-checkbox>
      </div>

      <div class="rv-actions">
        <el-button size="large" @click="router.back()">取消</el-button>
        <el-button type="primary" size="large" :loading="submitting" @click="submit">提交评价</el-button>
      </div>
    </template>

    <el-empty v-else description="订单不存在" />
  </div>
</template>

<style scoped>
.rv-dims { display: flex; flex-direction: column; gap: 16px; }
.rv-dim { display: flex; align-items: center; gap: 16px; }
.rv-label { width: 80px; color: #555; font-size: 14px; }
.rv-upload { margin-top: 16px; }
.rv-tip { font-size: 12px; color: var(--text-light); }
.rv-actions { display: flex; justify-content: center; gap: 14px; padding: 8px 0 20px; }
</style>
