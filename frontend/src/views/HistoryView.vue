<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCollectionStore } from '@/stores/collection'
import { useCartStore } from '@/stores/cart'
import { fmtMoney } from '@/utils/format'

const router = useRouter()
const collection = useCollectionStore()
const cart = useCartStore()

// 后端返回「商品 + 浏览时间」，按最近浏览排序
const list = computed(() => collection.history)

onMounted(() => {
  collection.loadHistory().catch(() => {})
})

const go = (id) => router.push({ name: 'product', params: { id } })
const addCart = async (p) => {
  await cart.add(p, 1)
  ElMessage.success('已加入购物车')
}
const remove = async (id) => {
  await collection.removeHistory(id)
  ElMessage.success('已删除')
}
const clearAll = () => {
  ElMessageBox.confirm('确定清空浏览历史吗？', '提示', { type: 'warning' })
    .then(async () => {
      await collection.clearHistory()
      ElMessage.success('已清空浏览历史')
    })
    .catch(() => {})
}
</script>

<template>
  <div class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item>浏览历史</el-breadcrumb-item>
    </el-breadcrumb>

    <div class="col-head">
      <div class="col-title">浏览历史 <span class="col-sub">共 {{ list.length }} 件</span></div>
      <el-button v-if="list.length" text type="danger" @click="clearAll">清空历史</el-button>
    </div>

    <div v-if="list.length" class="his-list">
      <div v-for="p in list" :key="p.id + p.time" class="his-item">
        <div class="his-thumb" :class="p.c" @click="go(p.id)">图</div>
        <div class="his-main">
          <div class="his-title" @click="go(p.id)">{{ p.title }}</div>
          <div class="his-meta">{{ p.spec }} · 浏览时间 {{ p.time }}</div>
        </div>
        <div class="his-price">¥{{ fmtMoney(p.price) }}</div>
        <div class="his-ops">
          <el-button size="small" @click="go(p.id)">查看详情</el-button>
          <el-button size="small" type="primary" @click="addCart(p)">加入购物车</el-button>
          <el-button size="small" text type="danger" @click="remove(p.id)">删除</el-button>
        </div>
      </div>
    </div>

    <el-empty v-else description="暂无浏览记录">
      <el-button type="primary" @click="router.push({ name: 'home' })">去逛逛</el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.col-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.col-title { font-size: 18px; font-weight: bold; }
.col-sub { font-size: 13px; color: var(--text-light); font-weight: normal; margin-left: 6px; }
.his-list { display: flex; flex-direction: column; gap: 12px; }
.his-item {
  display: flex; align-items: center; gap: 16px; background: #fff;
  border: 1px solid var(--border); border-radius: 12px; padding: 14px 18px;
}
.his-thumb {
  width: 72px; height: 72px; border-radius: 8px; flex-shrink: 0; cursor: pointer;
  display: flex; align-items: center; justify-content: center; color: rgba(0,0,0,.18); font-size: 12px;
}
.his-main { flex: 1; min-width: 0; }
.his-title { font-size: 14px; font-weight: 600; cursor: pointer; }
.his-title:hover { color: var(--primary); }
.his-meta { font-size: 12px; color: var(--text-light); margin-top: 6px; }
.his-price { color: var(--primary); font-size: 16px; font-weight: bold; white-space: nowrap; }
.his-ops { display: flex; gap: 8px; flex-wrap: wrap; }
@media (max-width: 768px) {
  .his-item { flex-wrap: wrap; }
  .his-ops { width: 100%; justify-content: flex-end; }
}
</style>
