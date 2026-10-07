<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import productApi from '@/api/product'
import reviewApi from '@/api/review'
import { useCartStore } from '@/stores/cart'
import { useCollectionStore } from '@/stores/collection'
import { fmtMoney } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const cart = useCartStore()
const collection = useCollectionStore()

/** 详情异步加载，用一个空对象兜底，模板无需到处判空 */
const EMPTY_PRODUCT = {
  id: 0, title: '', price: 0, oldPrice: null, spec: '', sales: 0,
  rating: 5, brand: '', cat: '', tags: [], c: 'c1'
}
const product = ref({ ...EMPTY_PRODUCT })
const loading = ref(true)
const qty = ref(1)

const thumbs = ['c1', 'c2', 'c3', 'c4', 'c6']
const stageClass = ref('c1')

const faved = computed(() => collection.isFavorite(product.value.id))

const toggleFav = async () => {
  if (!product.value.id) return
  const added = await collection.toggleFavorite(product.value.id)
  ElMessage.success(added ? '已加入收藏' : '已取消收藏')
}

// 规格选项与参数表暂为演示数据：后端尚未提供 SKU / 参数表接口
const options = {
  颜色: ['原色钛', '深空黑', '银白色', '沙漠金'],
  版本: ['256G', '512G +¥1000', '1T +¥2200'],
  套餐: ['官方标配', '充电套餐 +¥99', '碎屏险 +¥499']
}
const selected = ref({ 颜色: '原色钛', 版本: '256G', 套餐: '官方标配' })
const pick = (key, val) => (selected.value[key] = val)

const specRows = computed(() => [
  ['品牌', product.value.brand || '—'],
  ['分类', product.value.cat || '—'],
  ['默认规格', product.value.spec || '—'],
  ['库存', `${product.value.stock ?? 0} 件`],
  ['评分', `${product.value.rating} 分`],
  ['累计销量', `${product.value.sales} 件`]
])

// 评价与推荐均来自后端
const comments = ref([])
const recommendations = ref([])

const load = async () => {
  loading.value = true
  const id = route.params.id
  try {
    product.value = await productApi.detail(id)
    qty.value = 1
    stageClass.value = product.value.c
    // 记录浏览足迹（失败静默，不影响浏览）
    collection.addHistory(product.value.id)
    const [rec, reviewPage] = await Promise.all([
      productApi.recommend(id, 4),
      reviewApi.byProduct(id, { pageSize: 5 })
    ])
    recommendations.value = rec
    comments.value = reviewPage.list
  } catch (e) {
    product.value = { ...EMPTY_PRODUCT }
    recommendations.value = []
    comments.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => route.params.id, load)

const currentSpec = computed(() => `${selected.value.颜色} · ${selected.value.版本}`)

const addCart = async () => {
  await cart.add(product.value, qty.value, { spec: currentSpec.value })
  ElMessage.success(`已加入购物车 ×${qty.value}`)
}
const buyNow = async () => {
  await cart.add(product.value, qty.value, { spec: currentSpec.value })
  router.push({ name: 'checkout' })
}
</script>

<template>
  <div class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item :to="{ name: 'category', query: { cat: product.cat } }">商品分类</el-breadcrumb-item>
      <el-breadcrumb-item>{{ product.title.slice(0, 12) }}</el-breadcrumb-item>
    </el-breadcrumb>

    <div class="pd-main">
      <div class="pd-gallery">
        <div class="pd-stage" :class="stageClass">主图</div>
        <div class="pd-thumbs">
          <div
            v-for="(t, i) in thumbs"
            :key="t"
            class="th"
            :class="[t, { on: stageClass === t }]"
            @click="stageClass = t"
          >
            图{{ i + 1 }}
          </div>
        </div>
      </div>

      <div class="pd-info">
        <h1>{{ product.title }}</h1>
        <div class="pd-sub">{{ product.spec }} · {{ product.brand }} · 官方正品</div>
        <div class="pd-tags">
          <span class="tg-new">新品</span><span class="tg-first">限量首发</span><span class="tg-free">12 期免息</span>
        </div>

        <div class="pd-price-box">
          <span class="lab">极客价</span>
          <div class="pd-price">
            <small>¥</small>{{ fmtMoney(product.price) }}
            <span class="old">¥{{ product.oldPrice }}</span>
          </div>
          <div class="right">已抢<b>{{ product.sales }}</b>件<br />好评率 {{ (product.rating * 20).toFixed(0) }}%</div>
        </div>

        <div class="pd-rows">
          <div v-for="(vals, key) in options" :key="key" class="pd-row">
            <div class="k">{{ key }}</div>
            <div class="v">
              <div
                v-for="v in vals"
                :key="v"
                class="opt"
                :class="{ on: selected[key] === v }"
                @click="pick(key, v)"
              >
                {{ v }}
              </div>
            </div>
          </div>
          <div class="pd-row">
            <div class="k">数量</div>
            <div class="v">
              <el-input-number v-model="qty" :min="1" :max="99" />
            </div>
          </div>
        </div>

        <div class="pd-actions">
          <el-button style="height:52px;border-radius:26px" @click="toggleFav">
            {{ faved ? '❤️ 已收藏' : '🤍 收藏' }}
          </el-button>
          <button class="btn-cart" @click="addCart">加入购物车</button>
          <button class="btn-buy" @click="buyNow">立即购买</button>
        </div>

        <div class="pd-services">
          <span><span class="dot">✓</span> 正品保障</span>
          <span><span class="dot">✓</span> 7 天无理由</span>
          <span><span class="dot">✓</span> 假一赔十</span>
          <span><span class="dot">✓</span> 12 期免息</span>
          <span><span class="dot">✓</span> 极速发货</span>
        </div>
      </div>
    </div>

    <!-- 详情 Tabs -->
    <el-tabs type="border-card" class="pd-tabs-wrap" style="margin-top:22px">
      <el-tab-pane label="商品详情">
        <h3>产品亮点</h3>
        <p style="color:#555;line-height:2">
          搭载全新芯片，性能较上代提升 20%；超视网膜 XDR 显示屏，峰值亮度 2000 尼特；钛金属机身更轻更坚固；
          多摄系统支持 5 倍光学变焦，随手拍出好照片。
        </p>
        <div class="pd-detail-img c1">详情图 1</div>
        <div class="pd-detail-img c2">详情图 2</div>
        <div class="pd-detail-img c3">详情图 3</div>
      </el-tab-pane>

      <el-tab-pane label="规格参数">
        <el-table :data="specRows" border style="width:100%">
          <el-table-column prop="0" label="项目" width="180" class-name="spec-k" />
          <el-table-column prop="1" label="参数" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`用户评价 (${comments.length})`">
        <div v-for="c in comments" :key="c.name" class="cmt">
          <div class="av">{{ c.av }}</div>
          <div class="body">
            <div class="nm">
              {{ c.name }}
              <el-rate :model-value="c.rate" disabled size="small" />
            </div>
            <div class="txt">{{ c.text }}</div>
            <div class="meta">{{ c.meta }}</div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 推荐 -->
    <div class="section-title">看了又看</div>
    <div class="pd-rec">
      <a
        v-for="p in recommendations"
        :key="p.id"
        class="rec-card"
        @click="router.push({ name: 'product', params: { id: p.id } })"
      >
        <div class="ri" :class="p.c">商品图</div>
        <div class="info">
          <div class="t">{{ p.title }}</div>
          <div class="p">¥{{ fmtMoney(p.price) }}</div>
        </div>
      </a>
    </div>
  </div>
</template>
