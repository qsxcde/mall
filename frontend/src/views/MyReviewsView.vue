<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import reviewApi from '@/api/review'

const router = useRouter()
const list = ref([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    list.value = await reviewApi.mine()
  } catch (e) {
    list.value = []
  } finally {
    loading.value = false
  }
}
onMounted(load)


const remove = (review) => {
  ElMessageBox.confirm('确定删除这条评价吗？删除后不可恢复。', '提示', { type: 'warning' })
    .then(async () => {
      await reviewApi.remove(review.id)
      list.value = list.value.filter((r) => r.id !== review.id)
      ElMessage.success('评价已删除')
    })
    .catch(() => {})
}

const goOrder = (no) => router.push({ name: 'order-detail', params: { no } })
</script>

<template>
  <div class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item :to="{ name: 'user', query: { tab: 'profile' } }">个人中心</el-breadcrumb-item>
      <el-breadcrumb-item>我的评价</el-breadcrumb-item>
    </el-breadcrumb>

    <div class="mr-head">
      <div class="mr-title">我的评价 <span class="mr-sub">共 {{ list.length }} 条</span></div>
    </div>

    <div v-if="list.length" class="mr-list">
      <div v-for="r in list" :key="r.id" class="mr-card">
        <div class="product-row" @click="router.push({ name: 'product', params: { id: r.product.id } })">
          <div class="thumb" :class="r.product.c">图</div>
          <div class="pinfo">
            <div class="ptitle">{{ r.product.title }}</div>
            <div class="pmeta">订单号 {{ r.orderNo }} · {{ r.time }}</div>
          </div>
        </div>

        <div class="mr-content">
          <el-rate :model-value="r.rate" disabled size="small" />
          <div class="mr-text">{{ r.content }}</div>
          <div v-if="r.images && r.images.length" class="mr-imgs">
            <img v-for="(img, i) in r.images" :key="i" class="mr-img" :src="img" alt="评价图" />
          </div>
          <div v-if="r.reply" class="mr-reply"><b>商家回复：</b>{{ r.reply }}</div>
        </div>

        <div class="mr-ops">
          <el-button size="small" @click="goOrder(r.orderNo)">查看订单</el-button>
          <!-- 后端暂无追评接口：按钮置灰而不是弹「开发中」，避免看起来可用 -->
          <el-tooltip content="追加评价暂未开放" placement="top">
            <span><el-button size="small" disabled>追加评价</el-button></span>
          </el-tooltip>
          <el-button size="small" text type="danger" @click="remove(r)">删除</el-button>
        </div>
      </div>
    </div>

    <el-empty v-else description="暂无评价，去为买过的商品打个分吧">
      <el-button type="primary" @click="router.push({ name: 'orders' })">去评价</el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.mr-head { margin-bottom: 16px; }
.mr-title { font-size: 18px; font-weight: bold; }
.mr-sub { font-size: 13px; color: var(--text-light); font-weight: normal; margin-left: 6px; }
.mr-list { display: flex; flex-direction: column; gap: 14px; }
.mr-card { background: #fff; border: 1px solid var(--border); border-radius: 12px; padding: 18px 20px; }
.product-row { display: flex; align-items: center; gap: 14px; cursor: pointer; padding-bottom: 14px; border-bottom: 1px dashed var(--border); }
.product-row .thumb { width: 56px; height: 56px; border-radius: 8px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; color: rgba(0,0,0,.18); font-size: 12px; }
.product-row .ptitle { font-size: 14px; font-weight: 600; }
.product-row .pmeta { font-size: 12px; color: var(--text-light); margin-top: 6px; }
.mr-content { padding: 14px 0; }
.mr-text { font-size: 14px; color: #555; line-height: 1.9; margin-top: 8px; }
.mr-imgs { display: flex; gap: 10px; margin-top: 12px; }
.mr-img { width: 76px; height: 76px; border-radius: 8px; }
.mr-reply { background: #f7faff; border-radius: 8px; padding: 10px 14px; font-size: 13px; color: #555; margin-top: 12px; }
.mr-reply b { color: var(--primary); }
.mr-ops { display: flex; gap: 10px; justify-content: flex-end; }
</style>
