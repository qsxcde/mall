<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import productApi from '@/api/product'
import { useCartStore } from '@/stores/cart'
import { fmtMoney } from '@/utils/format'

const router = useRouter()
const cart = useCartStore()

const tabs = [
  { key: 'all', name: '全部', cat: null },
  { key: 'phone', name: '手机', cat: 'phone' },
  { key: 'pc', name: '电脑', cat: 'pc' },
  { key: 'pad', name: '平板', cat: 'pad' },
  { key: 'audio', name: '耳机音响', cat: 'audio' },
  { key: 'wear', name: '智能穿戴', cat: 'wear' }
]
const activeTab = ref('all')

const featured = ref(null)
const newList = ref([])
const loading = ref(false)

/** 新品首发直接复用商品列表接口的 isNew 条件，分类筛选也交给后端 */
const loadList = async () => {
  const tab = tabs.find((t) => t.key === activeTab.value)
  loading.value = true
  try {
    const page = await productApi.newProducts({ cat: tab?.cat || '', pageSize: 12 })
    newList.value = page.list
  } catch (e) {
    newList.value = []
  } finally {
    loading.value = false
  }
}

// 分类 Tab 变化时重新向后端要数据
watch(activeTab, loadList)

onMounted(async () => {
  try {
    const page = await productApi.newProducts({ pageSize: 12 })
    featured.value = page.list[0] || null
  } catch (e) {
    featured.value = null
  }
  await loadList()
})

const buy = async (p) => {
  await cart.add(p, 1)
  ElMessage.success('已加入购物车')
}
</script>

<template>
  <div class="container page-wrap">
    <!-- 首发 Banner -->
    <div class="np-hero">
      <div class="np-left">
        <span class="badge-new">🆕 NEW ARRIVAL</span>
        <h1>新品首发</h1>
        <p>抢先预约 · 享 12 期免息 · 晒单返积分 · 限量首发礼</p>
      </div>
      <div class="np-right">
        <!-- 后端没有「下一次发布」的时间源，因此展示真实上新数量而不是编造的倒计时 -->
        <div class="np-label">本次上新</div>
        <div class="np-count">{{ newList.length }} 款</div>
      </div>
    </div>

    <!-- 分类筛选 -->
    <el-radio-group v-model="activeTab" class="np-tabs">
      <el-radio-button v-for="t in tabs" :key="t.key" :value="t.key">{{ t.name }}</el-radio-button>
    </el-radio-group>

    <!-- 旗舰主打 -->
    <div class="section-title">本期主打</div>
    <div v-if="featured" class="featured">
      <div class="featured-img" :class="featured.c">
        <span class="flag">🆕 首发</span>商品图
      </div>
      <div class="featured-info">
        <!-- 标签取自后端 tags；没有 tags 时退回「新品」，不编造「限量首发 / 12 期免息」 -->
        <div class="tag-line">
          <span v-if="!featured.tags || !featured.tags.length" class="tg-new">新品</span>
          <span v-for="t in featured.tags || []" :key="t" class="tg-new">{{ t }}</span>
        </div>
        <h2>{{ featured.title }}</h2>
        <div class="spec">{{ featured.spec }} · 官方标配 · 全国联保</div>
        <div class="price"><small>¥</small>{{ fmtMoney(featured.price) }}<span class="old">¥{{ featured.oldPrice }}</span></div>
        <div class="featured-actions">
          <button class="btn-primary2" @click="router.push({ name: 'product', params: { id: featured.id } })">立即抢购</button>
        </div>
      </div>
    </div>

    <!-- 最新上架 -->
    <div class="section-title">最新上架</div>
    <div class="np-grid">
      <div v-for="p in newList" :key="p.id" class="np-card" @click="router.push({ name: 'product', params: { id: p.id } })">
        <div class="np-img" :class="p.c">
          <span class="new-badge">NEW</span>
          <span v-if="p.tags.includes('限量首发')" class="first-badge">首发</span>
          商品图
        </div>
        <div class="np-info">
          <div class="np-title">{{ p.title }}</div>
          <div class="np-tags">
            <span v-for="t in p.tags" :key="t" class="tg-new">{{ t }}</span>
            <span v-if="!p.tags || !p.tags.length" class="tg-new">新品</span>
          </div>
          <div class="np-price"><small>¥</small>{{ fmtMoney(p.price) }}<span class="np-old">¥{{ p.oldPrice }}</span></div>
          <button class="np-btn" @click.stop="buy(p)">立即抢购</button>
        </div>
      </div>
    </div>

    <!-- 即将首发：后端没有「未来发布」的数据源（在售商品的 release_time 均已到达），
         这里给空态而不是编造商品名 / 发布时间 / 预约按钮 -->
    <div class="section-title">即将首发</div>
    <el-empty description="暂无预告中的新品，敬请期待" />
  </div>
</template>
