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

/**
 * 图集：来自后端 images。
 * 图片路径失效时（演示数据里的占位路径）自动剔除并回落到占位块，
 * 避免出现「5 张假缩略图 + 破图」。
 */
const gallery = ref([])
const activeImage = ref('')
const dropImage = (url) => {
  gallery.value = gallery.value.filter((g) => g !== url)
  if (activeImage.value === url) activeImage.value = gallery.value[0] || ''
}

const faved = computed(() => collection.isFavorite(product.value.id))

const toggleFav = async () => {
  if (!product.value.id) return
  const added = await collection.toggleFavorite(product.value.id)
  ElMessage.success(added ? '已加入收藏' : '已取消收藏')
}

/**
 * 后端未提供 SKU / 参数表接口，因此规格只展示商品自带字段，
 * 不编造「+¥1000」这类并不生效的加价选项（价格以下单页为准）。
 */
const currentSpec = computed(() => product.value.spec || '默认规格')

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
    gallery.value = [...(product.value.images || [])]
    activeImage.value = gallery.value[0] || ''
    // 记录浏览足迹（失败静默，不影响浏览）
    collection.addHistory(product.value.id)
    // 推荐位与评价是「附加内容」：任一失败都不应该把整页清空成空商品
    const [rec, reviewPage] = await Promise.all([
      productApi.recommend(id, 4).catch(() => []),
      reviewApi.byProduct(id, { pageSize: 5 }).catch(() => ({ list: [] }))
    ])
    recommendations.value = rec
    comments.value = reviewPage.list
  } catch (e) {
    product.value = { ...EMPTY_PRODUCT }
    gallery.value = []
    activeImage.value = ''
    recommendations.value = []
    comments.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => route.params.id, load)

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
        <div class="pd-stage" :class="activeImage ? '' : product.c">
          <img v-if="activeImage" :src="activeImage" :alt="product.title" @error="dropImage(activeImage)" />
          <template v-else>暂无实拍图</template>
        </div>
        <div class="pd-thumbs">
          <div
            v-for="img in gallery"
            :key="img"
            class="th"
            :class="{ on: activeImage === img }"
            @click="activeImage = img"
          >
            <img :src="img" :alt="product.title" @error="dropImage(img)" />
          </div>
          <div v-if="!gallery.length" class="th ph">占位图</div>
        </div>
      </div>

      <div class="pd-info">
        <h1>{{ product.title }}</h1>
        <div class="pd-sub">{{ product.spec }} · {{ product.brand }} · 官方正品</div>
        <!-- 营销标签取自后端 tags 字段，不再写死「新品 / 限量首发 / 12 期免息」 -->
        <div v-if="product.tags && product.tags.length" class="pd-tags">
          <span v-for="t in product.tags" :key="t" class="tg">{{ t }}</span>
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
          <div class="pd-row">
            <div class="k">规格</div>
            <div class="v">
              <div class="opt on">{{ currentSpec }}</div>
              <span class="opt-note">该商品为单一规格，加购与下单均按此规格记录</span>
            </div>
          </div>
          <div class="pd-row">
            <div class="k">数量</div>
            <div class="v">
              <el-input-number v-model="qty" :min="1" :max="99" />
              <span class="opt-note">库存 {{ product.stock ?? 0 }} 件</span>
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
        <!-- 文案全部来自后端字段（description / tags / brand / spec），不写死某款手机的宣传语 -->
        <h3>商品信息</h3>
        <p style="color:#555;line-height:2">{{ product.description || product.title }}</p>
        <div class="pd-params">
          <span v-for="row in specRows" :key="row[0]" class="pd-param">
            <i>{{ row[0] }}</i>{{ row[1] }}
          </span>
        </div>
        <p v-if="!gallery.length" class="pd-note">商家暂未上传详情图文，以上参数即为商品的完整信息。</p>
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
