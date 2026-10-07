<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import productApi from '@/api/product'
import ProductCard from '@/components/ProductCard.vue'

const route = useRoute()

const keyword = computed(() => route.query.q ?? 'iPhone')

const categories = ref([])
const filters = reactive({ cats: [], brands: [], services: [] })
const price = reactive({ min: '', max: '' })
// 与后端 brand_name 对齐
const brandOptions = ['苹果', '华为', '小米', 'OPPO', '荣耀', '三星', '联想', '戴尔']

const sortKey = ref('default')
const priceAsc = ref(true)
const viewMode = ref('grid')

const pagedList = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 8
const loading = ref(false)

const sortParam = computed(() => {
  if (sortKey.value === 'sales') return 'sales'
  if (sortKey.value === 'price') return priceAsc.value ? 'price_asc' : 'price_desc'
  if (sortKey.value === 'rate') return 'rating'
  return 'default'
})

/** 分类名 → 分类 key（后端按 key 过滤） */
const catKeys = computed(() =>
  filters.cats.map((name) => categories.value.find((c) => c.name === name)?.key).filter(Boolean)
)

const load = async () => {
  loading.value = true
  try {
    const result = await productApi.search({
      keyword: keyword.value.trim(),
      // 后端只接受单个 cat，多选时取第一个（与搜索场景的实际使用一致）
      cat: catKeys.value[0] || '',
      brand: filters.brands.join(','),
      priceMin: price.min,
      priceMax: price.max,
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

onMounted(async () => {
  try {
    categories.value = await productApi.categories()
  } catch (e) {
    categories.value = []
  }
  await load()
})

watch(
  () => [route.query.q, keyword.value].join('|'),
  () => {
    page.value = 1
    load()
  }
)
watch(
  [() => filters.cats.join(','), () => filters.brands.join(','), () => filters.services.join(','), price, sortParam],
  () => {
    page.value = 1
    load()
  },
  { deep: true }
)
watch(page, load)

const onSort = (key) => {
  if (key === 'price' && sortKey.value === 'price') priceAsc.value = !priceAsc.value
  sortKey.value = key
}
</script>

<template>
  <div class="container search-wrap">
    <!-- 筛选侧栏 -->
    <aside class="filter-side">
      <div class="filter-group">
        <div class="filter-title">商品分类</div>
        <el-checkbox-group v-model="filters.cats">
          <el-checkbox v-for="c in categories" :key="c.key" :label="c.name" :value="c.name" />
        </el-checkbox-group>
      </div>
      <div class="filter-group">
        <div class="filter-title">品牌</div>
        <el-checkbox-group v-model="filters.brands">
          <el-checkbox v-for="b in brandOptions" :key="b" :label="b" :value="b" />
        </el-checkbox-group>
      </div>
      <div class="filter-group">
        <div class="filter-title">价格区间</div>
        <div style="display:flex;gap:8px;align-items:center">
          <el-input v-model="price.min" placeholder="最低" type="number" />
          <span>—</span>
          <el-input v-model="price.max" placeholder="最高" type="number" />
        </div>
      </div>
      <div class="filter-group">
        <div class="filter-title">服务</div>
        <el-checkbox-group v-model="filters.services">
          <el-checkbox label="分期免息" value="分期免息" />
          <el-checkbox label="顺丰包邮" value="顺丰包邮" />
          <el-checkbox label="延保服务" value="延保服务" />
        </el-checkbox-group>
      </div>
    </aside>

    <!-- 结果区 -->
    <main class="result-main">
      <div class="search-head">
        <div class="kw">“<b>{{ keyword }}</b>” 的搜索结果</div>
        <div class="meta">
          共找到 <b>{{ total }}</b> 件商品 · 相关推荐：iPhone 17 Pro、iPhone 17、iPhone 16
        </div>
      </div>

      <div class="sort-bar">
        <a :class="{ on: sortKey === 'default' }" @click="onSort('default')">综合</a>
        <a :class="{ on: sortKey === 'sales' }" @click="onSort('sales')">销量</a>
        <a :class="{ on: sortKey === 'price' }" @click="onSort('price')">
          价格 <span class="arrow">{{ sortKey === 'price' ? (priceAsc ? '↑' : '↓') : '↕' }}</span>
        </a>
        <a :class="{ on: sortKey === 'rate' }" @click="onSort('rate')">好评</a>
        <div class="view-toggle">
          <el-button :type="viewMode === 'grid' ? 'primary' : 'default'" @click="viewMode = 'grid'">▦</el-button>
          <el-button :type="viewMode === 'list' ? 'primary' : 'default'" @click="viewMode = 'list'">☰</el-button>
        </div>
      </div>

      <div v-if="pagedList.length" class="result-grid" :class="{ list: viewMode === 'list' }">
        <ProductCard v-for="p in pagedList" :key="p.id" :product="p" :keyword="keyword" />
      </div>
      <el-empty v-else description="没有找到相关商品" />

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
</template>
