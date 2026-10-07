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

// 后端返回的就是商品对象（含标签、价格、占位配色），无需再按 ID 反查
const list = computed(() => collection.favorites)

onMounted(() => {
  collection.loadFavorites().catch(() => {})
})

const go = (id) => router.push({ name: 'product', params: { id } })
const remove = async (id) => {
  await collection.removeFavorite(id)
  ElMessage.success('已取消收藏')
}
const addCart = async (p) => {
  await cart.add(p, 1)
  ElMessage.success('已加入购物车')
}
const clearAll = () => {
  ElMessageBox.confirm('确定清空收藏夹吗？', '提示', { type: 'warning' })
    .then(async () => {
      await collection.clearFavorites()
      ElMessage.success('已清空收藏夹')
    })
    .catch(() => {})
}
</script>

<template>
  <div class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item>我的收藏</el-breadcrumb-item>
    </el-breadcrumb>

    <div class="col-head">
      <div class="col-title">我的收藏 <span class="col-sub">共 {{ list.length }} 件</span></div>
      <el-button v-if="list.length" text type="danger" @click="clearAll">清空收藏夹</el-button>
    </div>

    <div v-if="list.length" class="product-grid">
      <div v-for="p in list" :key="p.id" class="product-card">
        <div class="product-img" :class="p.c" @click="go(p.id)">
          <span v-if="p.tags && p.tags.length" class="tag">{{ p.tags[0] }}</span>商品图
        </div>
        <div class="product-info">
          <div class="product-title" @click="go(p.id)">{{ p.title }}</div>
          <div class="product-price"><small>¥</small>{{ fmtMoney(p.price) }}</div>
          <div class="col-ops">
            <el-button size="small" plain type="danger" @click="remove(p.id)">取消收藏</el-button>
            <el-button size="small" type="primary" @click="addCart(p)">加入购物车</el-button>
          </div>
        </div>
      </div>
    </div>

    <el-empty v-else description="收藏夹还是空的，快去逛逛吧">
      <el-button type="primary" @click="router.push({ name: 'home' })">去逛逛</el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.col-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.col-title { font-size: 18px; font-weight: bold; }
.col-sub { font-size: 13px; color: var(--text-light); font-weight: normal; margin-left: 6px; }
.product-title { cursor: pointer; }
.product-title:hover { color: var(--primary); }
.col-ops { display: flex; gap: 8px; margin-top: 10px; }
.col-ops :deep(.el-button) { flex: 1; margin: 0; }
</style>
