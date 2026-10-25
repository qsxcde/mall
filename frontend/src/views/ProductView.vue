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
  id: 0,
  title: '',
  price: 0,
  oldPrice: null,
  spec: '',
  sales: 0,
  rating: 5,
  brand: '',
  cat: '',
  tags: [],
  c: 'c1',
  specDimensions: [],
  skus: []
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
 * 多规格 SKU：
 *   specDimensions: [{ name, values: [{ value, image }] }]
 *   skus: [{ id, specDesc, price, stock }]，specDesc = 各维度值按维度顺序用 / 拼接（如「黑色/XL」）
 * 后端未返回 SKU 数据时降级为单规格展示（向后兼容），不编造「+¥1000」这类并不生效的加价选项。
 */
const specDimensions = computed(() => product.value.specDimensions || [])
const skuList = computed(() => product.value.skus || [])
const hasSkus = computed(() => skuList.value.length > 0)

/** 已选组合：维度名 -> 规格值 */
const selectedSpec = ref({})

const skuParts = (sku) => String(sku.specDesc || '').split('/')

/** 选中组合命中的 SKU；无 SKU 数据时为 null */
const matchedSku = computed(() => {
  if (!hasSkus.value) return null
  // 无维度的单 SKU 商品直接命中，避免 specDesc 与空组合对不上
  if (!specDimensions.value.length) return skuList.value[0] || null
  const key = specDimensions.value.map((d) => selectedSpec.value[d.name] || '').join('/')
  return skuList.value.find((s) => String(s.specDesc || '') === key) || null
})

/** 该规格值在当前选择下是否能组成存在的 SKU，无对应 SKU 的组合置灰 */
const isValueEnabled = (dimIndex, value) =>
  skuList.value.some((sku) => {
    const parts = skuParts(sku)
    if (parts[dimIndex] !== value) return false
    return specDimensions.value.every(
      (d, j) =>
        j === dimIndex || !selectedSpec.value[d.name] || parts[j] === selectedSpec.value[d.name]
    )
  })

const selectValue = (dim, value) => {
  const next = { ...selectedSpec.value }
  // 再次点击已选值 = 取消选择：稀疏 SKU 矩阵下避免「其余值全被置灰」无路可退
  if (next[dim.name] === value) delete next[dim.name]
  else next[dim.name] = value
  selectedSpec.value = next
}

const applySkuSelection = (sku) => {
  const parts = skuParts(sku)
  const sel = {}
  specDimensions.value.forEach((d, i) => {
    if (parts[i] != null) sel[d.name] = parts[i]
  })
  selectedSpec.value = sel
}

/** 默认选中：优先与商品 spec 一致且有货的 SKU，其次第一个有货 SKU，最后兜底第一个 */
const initSpecSelection = () => {
  selectedSpec.value = {}
  if (!hasSkus.value || !specDimensions.value.length) return
  const preferred =
    skuList.value.find((s) => s.specDesc === product.value.spec && s.stock > 0) ||
    skuList.value.find((s) => s.stock > 0) ||
    skuList.value[0]
  if (preferred) applySkuSelection(preferred)
}

const currentSpec = computed(() => matchedSku.value?.specDesc || product.value.spec || '默认规格')
/** 展示价：选中 SKU 显示该 SKU 价，未选中显示起售价（商品价 = MIN(SKU 价)） */
const displayPrice = computed(() => matchedSku.value?.price ?? product.value.price)
const displayStock = computed(() => matchedSku.value?.stock ?? product.value.stock)
const priceLabel = computed(() =>
  hasSkus.value ? (matchedSku.value ? '已选规格价' : '起售价') : '极客价'
)
const soldOut = computed(() => !!matchedSku.value && matchedSku.value.stock === 0)
/** 规格区提示语：售罄 / 已选库存 / 未选全 / 单规格兼容 */
const specNote = computed(() => {
  if (!hasSkus.value) return '该商品为单一规格，加购与下单均按此规格记录'
  if (!matchedSku.value) return '请先选择完整规格'
  if (soldOut.value) return '该规格已售罄'
  return `已选「${matchedSku.value.specDesc}」· 该规格库存 ${matchedSku.value.stock} 件`
})

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
    initSpecSelection()
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
    selectedSpec.value = {}
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

/** 加购 / 立即购买：多规格传 { skuId, spec }，spec 取该 SKU 的 specDesc */
const pickSku = () => {
  if (!hasSkus.value) return null
  if (!matchedSku.value) {
    ElMessage.warning('请先选择完整规格')
    return null
  }
  if (soldOut.value) {
    ElMessage.warning('该规格已售罄')
    return null
  }
  return matchedSku.value
}

const addCart = async () => {
  const sku = pickSku()
  if (hasSkus.value && !sku) return
  await cart.add(product.value, qty.value, { skuId: sku?.id, spec: currentSpec.value })
  ElMessage.success(`已加入购物车 ×${qty.value}`)
}
const buyNow = async () => {
  const sku = pickSku()
  if (hasSkus.value && !sku) return
  await cart.add(product.value, qty.value, { skuId: sku?.id, spec: currentSpec.value })
  router.push({ name: 'checkout' })
}
</script>

<template>
  <div class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item :to="{ name: 'category', query: { cat: product.cat } }"
        >商品分类</el-breadcrumb-item
      >
      <el-breadcrumb-item>{{ product.title.slice(0, 12) }}</el-breadcrumb-item>
    </el-breadcrumb>

    <div class="pd-main">
      <div class="pd-gallery">
        <div class="pd-stage" :class="activeImage ? '' : product.c">
          <img
            v-if="activeImage"
            :src="activeImage"
            :alt="product.title"
            @error="dropImage(activeImage)"
          />
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
          <span class="lab">{{ priceLabel }}</span>
          <div class="pd-price">
            <small>¥</small>{{ fmtMoney(displayPrice) }}
            <span class="old">¥{{ product.oldPrice }}</span>
          </div>
          <div class="right">
            已抢<b>{{ product.sales }}</b
            >件<br />好评率 {{ (product.rating * 20).toFixed(0) }}%
          </div>
        </div>

        <div class="pd-rows">
          <div class="pd-row">
            <div class="k">规格</div>
            <div class="v">
              <!-- 多规格：按维度分组选择，无效组合由 SKU 列表反推置灰 -->
              <template v-if="hasSkus && specDimensions.length">
                <div v-for="(d, di) in specDimensions" :key="d.name" class="spec-dim">
                  <span class="spec-dim__name">{{ d.name }}</span>
                  <div class="spec-dim__opts">
                    <button
                      v-for="sv in d.values"
                      :key="sv.value"
                      type="button"
                      class="opt spec-opt"
                      :class="{
                        on: selectedSpec[d.name] === sv.value,
                        off: !isValueEnabled(di, sv.value)
                      }"
                      :disabled="!isValueEnabled(di, sv.value)"
                      @click="selectValue(d, sv.value)"
                    >
                      {{ sv.value }}
                    </button>
                  </div>
                </div>
              </template>
              <div v-else class="opt on">{{ currentSpec }}</div>
              <span class="opt-note">{{ specNote }}</span>
            </div>
          </div>
          <div class="pd-row">
            <div class="k">数量</div>
            <div class="v">
              <el-input-number v-model="qty" :min="1" :max="99" />
              <span class="opt-note"
                >{{ matchedSku ? '该规格库存' : '总库存' }} {{ displayStock ?? 0 }} 件</span
              >
            </div>
          </div>
        </div>

        <div class="pd-actions">
          <el-button style="height: 52px; border-radius: 26px" @click="toggleFav">
            {{ faved ? '❤️ 已收藏' : '🤍 收藏' }}
          </el-button>
          <button class="btn-cart" :disabled="soldOut" @click="addCart">
            {{ soldOut ? '已售罄' : '加入购物车' }}
          </button>
          <button class="btn-buy" :disabled="soldOut" @click="buyNow">
            {{ soldOut ? '已售罄' : '立即购买' }}
          </button>
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
    <el-tabs type="border-card" class="pd-tabs-wrap" style="margin-top: 22px">
      <el-tab-pane label="商品详情">
        <!-- 文案全部来自后端字段（description / tags / brand / spec），不写死某款手机的宣传语 -->
        <h3>商品信息</h3>
        <p style="color: #555; line-height: 2">{{ product.description || product.title }}</p>
        <div class="pd-params">
          <span v-for="row in specRows" :key="row[0]" class="pd-param">
            <i>{{ row[0] }}</i
            >{{ row[1] }}
          </span>
        </div>
        <p v-if="!gallery.length" class="pd-note">
          商家暂未上传详情图文，以上参数即为商品的完整信息。
        </p>
      </el-tab-pane>

      <el-tab-pane label="规格参数">
        <el-table :data="specRows" border style="width: 100%">
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

<style scoped>
/* ---- 多规格联动选择器（仅补充 .opt 的禁用态与分组排版） ---- */
.spec-dim {
  flex-basis: 100%;
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.spec-dim__name {
  width: 64px;
  flex-shrink: 0;
  color: var(--text-light);
  font-size: 13px;
  padding-top: 7px;
}
.spec-dim__opts {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.spec-opt {
  font-family: inherit;
}
.spec-opt.off,
.spec-opt:disabled {
  color: #c0c4cc;
  border-color: #ebeef5;
  background: #f7f8fa;
  cursor: not-allowed;
}
.spec-opt.off:hover,
.spec-opt:disabled:hover {
  color: #c0c4cc;
  border-color: #ebeef5;
}
.btn-cart:disabled,
.btn-buy:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-cart:disabled:hover {
  background: #fff;
}
.btn-buy:disabled:hover {
  filter: none;
}
</style>
