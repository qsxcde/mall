<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useCartStore } from '@/stores/cart'
import { useMessageStore } from '@/stores/message'
import productApi from '@/api/product'

const route = useRoute()
const router = useRouter()
const cart = useCartStore()
const message = useMessageStore()
const { itemCount } = storeToRefs(cart)
const { unread } = storeToRefs(message)

// 品类导航由后端分类树提供
const categories = ref([])
onMounted(async () => {
  try {
    categories.value = await productApi.categories()
  } catch (e) {
    categories.value = []
  }
})

const keyword = ref('')

// 主导航（品类收纳进「全部品类」下拉，避免一行过挤）
const navs = [
  { name: 'home', label: '首页' },
  { name: 'seckill', label: '限时秒杀' },
  { name: 'newproduct', label: '新品首发' },
  { name: 'coupon', label: '领券中心' }
]

const onSearch = () => {
  router.push({ name: 'search', query: { q: keyword.value.trim() || 'iPhone' } })
}
const goCategory = (key) => router.push({ name: 'category', query: { cat: key } })
</script>

<template>
  <div class="header">
    <div class="container header-inner">
      <div class="logo" @click="router.push({ name: 'home' })">极客数码</div>

      <nav class="nav">
        <router-link
          v-for="nav in navs"
          :key="nav.name"
          :to="{ name: nav.name }"
          :class="{ active: route.name === nav.name }"
        >
          {{ nav.label }}
        </router-link>

        <el-dropdown trigger="hover" placement="bottom-start" @command="goCategory">
          <span class="nav-cat" :class="{ active: route.name === 'category' }">
            全部品类<el-icon class="nav-cat-ic"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="c in categories" :key="c.key" :command="c.key">
                <span class="dd-ic">{{ c.icon }}</span>{{ c.name }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </nav>

      <div class="search">
        <div class="search-box">
          <el-icon class="search-ic"><Search /></el-icon>
          <el-input v-model="keyword" placeholder="搜索手机、电脑、耳机" @keyup.enter="onSearch" />
          <el-button @click="onSearch">搜索</el-button>
        </div>
      </div>

      <div class="icon-link" title="消息中心" @click="router.push({ name: 'messages' })">
        <el-badge :value="unread" :hidden="unread === 0">
          <el-icon><Bell /></el-icon>
        </el-badge>
      </div>

      <div class="cart-link" @click="router.push({ name: 'cart' })">
        <el-badge :value="itemCount" :hidden="itemCount === 0">
          <el-icon><ShoppingCart /></el-icon>
        </el-badge>
        <span>购物车</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.nav-cat-ic { margin-left: 2px; font-size: 13px; }
.dd-ic { margin-right: 8px; }
</style>
