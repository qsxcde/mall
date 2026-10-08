<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import productApi from '@/api/product'
import ProductCard from '@/components/ProductCard.vue'

const route = useRoute()
const router = useRouter()

const PRICE_MAX = 12000

// 分类树来自后端
const categories = ref([])
const catKey = computed(() => route.query.cat || 'phone')
const currentCat = computed(
  () => categories.value.find((c) => c.key === catKey.value) || categories.value[0] || { name: '全部商品', children: [] }
)
// 子分类直接用分类树的 children，不再写死 phone 的子分类
const subList = computed(() => currentCat.value.children || [])
const subKey = ref(route.query.sub || 'all')

// 筛选条件（全部由后端执行）；后端 ProductQueryDTO 只支持品牌/价格/排序，
// 因此这里不做「服务」这类后端不认的勾选项——勾了也不生效等于骗用户
const filters = reactive({ brands: [] })
const priceRange = ref([0, PRICE_MAX])
// 品牌需与后端 brand_name 一致
const brandOptions = ['苹果', '华为', '小米', 'OPPO', '荣耀', '三星', '联想', '戴尔']
const pricePresets = [
  { label: '1000 以下', range: [0, 1000] },
  { label: '1000-3000', range: [1000, 3000] },
  { label: '3000-5000', range: [3000, 5000] },
  { label: '5000-8000', range: [5000, 8000] },
  { label: '8000 以上', range: [8000, PRICE_MAX] }
]
const presetActive = ref(-1)

const sortKey = ref('default')
const priceAsc = ref(true)
const viewMode = ref('grid')

// 结果与总数均由后端分页返回
const pagedList = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 8
const loading = ref(false)

const sortParam = computed(() => {
  if (sortKey.value === 'sales') return 'sales'
  if (sortKey.value === 'price') return priceAsc.value ? 'price_asc' : 'price_desc'
  if (sortKey.value === 'new') return 'new'
  if (sortKey.value === 'rate') return 'rating'
  return 'default'
})

const load = async () => {
  loading.value = true
  try {
    const result = await productApi.list({
      cat: catKey.value,
      sub: subKey.value === 'all' ? '' : subKey.value,
      brand: filters.brands.join(','),
      priceMin: priceRange.value[0] || '',
      // 顶到上限时视为不限，避免把 12000 以上的商品过滤掉
      priceMax: priceRange.value[1] >= PRICE_MAX ? '' : priceRange.value[1],
      sort: sortParam.value,
      page: page.value,
      pageSize
    })
    pagedList.value = result.list
    total.value = result.total
  } catch (e) {
    pagedList.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const loadCategories = async () => {
  try {
    categories.value = await productApi.categories()
  } catch (e) {
    categories.value = []
  }
}

onMounted(async () => {
  await loadCategories()
  await load()
})

watch(() => route.query.sub, (v) => {
  subKey.value = v || 'all'
  page.value = 1
  load()
})
watch(catKey, () => {
  subKey.value = 'all'
  page.value = 1
  load()
})
// 筛选变化回到第一页重新查询
watch(
  [() => filters.brands.join(','), priceRange, sortParam],
  () => {
    page.value = 1
    load()
  },
  { deep: true }
)
watch(page, load)

const selectSub = (key) => {
  subKey.value = key
  router.replace({ name: 'category', query: { cat: catKey.value, sub: key === 'all' ? undefined : key } })
}
const applyPreset = (i, range) => {
  presetActive.value = i
  priceRange.value = [...range]
}
const onSort = (key) => {
  if (key === 'price' && sortKey.value === 'price') priceAsc.value = !priceAsc.value
  sortKey.value = key
}
</script>

<template>
  <div class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item>{{ currentCat.name }}</el-breadcrumb-item>
    </el-breadcrumb>

    <!-- 分类 Banner -->
    <div class="cat-banner">
      <div class="cb-left">
        <h1>{{ currentCat.name }}频道</h1>
        <p>新品首发 12 期免息 · 以旧换新大额补贴 · 顺丰次日达</p>
        <div class="cb-tags">
          <span>游戏旗舰</span><span>影像旗舰</span><span>轻薄长续航</span><span>百元性价比</span>
        </div>
      </div>
      <div class="cb-right">
        <div class="big">12 期</div>
        <div class="small">分期免息 · 0 手续费</div>
      </div>
    </div>

    <!-- 子分类 -->
    <div v-if="subList.length" class="sub-nav">
      <span class="lab">子分类：</span>
      <a v-for="s in subList" :key="s.key" :class="{ on: subKey === s.key }" @click="selectSub(s.key)">
        {{ s.name }}
      </a>
    </div>

    <div class="cat-body">
      <!-- 筛选侧栏 -->
      <aside class="filter-side">
        <div class="filter-group">
          <div class="filter-title">品类</div>
          <div class="filter-opts">
            <el-checkbox
              v-for="c in categories"
              :key="c.key"
              class="filter-check"
              :model-value="catKey === c.key"
              :label="c.name"
              @change="router.push({ name: 'category', query: { cat: c.key } })"
            />
          </div>
        </div>

        <div class="filter-group">
          <div class="filter-title">品牌</div>
          <div class="filter-opts">
            <el-checkbox-group v-model="filters.brands" class="filter-opts">
              <el-checkbox v-for="b in brandOptions" :key="b" class="filter-check" :label="b" :value="b">{{ b }}</el-checkbox>
            </el-checkbox-group>
          </div>
        </div>

        <div class="filter-group">
          <div class="filter-title">价格区间</div>
          <el-slider v-model="priceRange" range :min="0" :max="12000" :step="100" />
          <div class="price-presets">
            <el-button
              v-for="(p, i) in pricePresets"
              :key="p.label"
              size="small"
              :type="presetActive === i ? 'primary' : 'default'"
              @click="applyPreset(i, p.range)"
            >
              {{ p.label }}
            </el-button>
          </div>
        </div>

      </aside>

      <!-- 结果区 -->
      <main class="result-main">
        <div class="sort-bar">
          <a :class="{ on: sortKey === 'default' }" @click="onSort('default')">综合</a>
          <a :class="{ on: sortKey === 'sales' }" @click="onSort('sales')">销量</a>
          <a :class="{ on: sortKey === 'price' }" @click="onSort('price')">
            价格 <span class="arrow">{{ sortKey === 'price' ? (priceAsc ? '↑' : '↓') : '↕' }}</span>
          </a>
          <a :class="{ on: sortKey === 'new' }" @click="onSort('new')">新品</a>
          <a :class="{ on: sortKey === 'rate' }" @click="onSort('rate')">好评</a>
          <div class="view-toggle">
            <el-button :type="viewMode === 'grid' ? 'primary' : 'default'" @click="viewMode = 'grid'">▦</el-button>
            <el-button :type="viewMode === 'list' ? 'primary' : 'default'" @click="viewMode = 'list'">☰</el-button>
          </div>
        </div>

        <div v-if="pagedList.length" class="result-grid" :class="{ list: viewMode === 'list' }">
          <ProductCard v-for="p in pagedList" :key="p.id" :product="p" show-spec />
        </div>
        <el-empty v-else description="没有符合条件的商品" />

        <div class="pager">
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            layout="prev, pager, next, total"
            background
            hide-on-single-page
          />
        </div>
      </main>
    </div>
  </div>
</template>
