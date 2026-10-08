<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useUserStore } from '@/stores/user'
import productApi from '@/api/product'
import { couponApi, seckillApi } from '@/api/marketing'
import ProductCard from '@/components/ProductCard.vue'
import { homeBanners, homeEntryLinks } from '@/data/constants'

const router = useRouter()
const user = useUserStore()
const { isLoggedIn, info } = storeToRefs(user)

// 纯展示的营销位留在前端常量里
const banners = homeBanners
const entryLinks = homeEntryLinks

// 以下数据全部来自后端
const categories = ref([])
const hotProducts = ref([])
const guessProducts = ref([])
const seckillPreview = ref([])
const couponsPreview = ref([])
/** 当前秒杀场次（来自后端），用于公告栏与秒杀卡片标题 */
const seckillSession = ref(null)

/**
 * 公告栏内容由真实数据推导：后端没有 CMS 公告接口，
 * 与其写死「iPhone 17 系列首发」这种与在售商品不符的文案，不如展示当下真实的活动状态。
 */
const notice = computed(() => {
  const first = seckillPreview.value[0]
  if (first) {
    return `限时秒杀进行中：${first.product?.title || '热销商品'} ¥${first.price} 起，仅剩 ${first.stock} 件。`
  }
  return seckillSession.value
    ? `秒杀场次 ${seckillSession.value.time} 即将开始，敬请关注。`
    : '今日暂无秒杀场次，可先逛逛热销榜。'
})

const hotTab = ref('综合')
const hotTabs = ['综合', '销量', '价格', '好评']
const HOT_SORT = { 综合: 'default', 销量: 'sales', 价格: 'price_asc', 好评: 'rating' }

const goCategory = (cat) => router.push({ name: 'category', query: { cat } })

/** 热销榜切换排序：真正换数据，而不是只换 Tab 高亮 */
const onHotTab = async (tab) => {
  hotTab.value = tab
  try {
    const page = await productApi.list({ sort: HOT_SORT[tab], pageSize: 5, cat: 'phone' })
    hotProducts.value = page.list
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/** 换一批：翻到推荐池的下一批 */
const guessPage = ref(1)
const shuffleGuess = async () => {
  guessPage.value = (guessPage.value % 3) + 1
  try {
    const page = await productApi.list({ sort: 'new', pageSize: 5, page: guessPage.value })
    guessProducts.value = page.list
  } catch (e) {
    /* 拦截器已提示 */
  }
}

onMounted(async () => {
  try {
    // 首页楼层一次请求拿全：分类 + 热销 + 新品
    const [floors, sessions] = await Promise.all([productApi.homeFloors(), seckillApi.sessions()])
    categories.value = floors.categories
    hotProducts.value = floors.hotProducts
    guessProducts.value = floors.newProducts

    const running = sessions.find((s) => s.state === 'running') || sessions[0]
    seckillSession.value = running || null
    if (running) {
      seckillPreview.value = (await seckillApi.items(running.id)).slice(0, 4)
    }
    couponsPreview.value = (await couponApi.templates()).filter((c) => !c.soldout).slice(0, 2)
  } catch (e) {
    /* 加载失败保持空态 */
  }
})
</script>

<template>
  <div class="container page-wrap">
    <!-- 首屏 -->
    <div class="hero">
      <div class="cat-nav">
        <div class="cat-title">数码全品类</div>
        <div v-for="c in categories" :key="c.key" class="cat-item" @click="goCategory(c.key)">
          <span>{{ c.icon }} {{ c.name }}</span><span>›</span>
        </div>
        <div class="cat-item" @click="router.push({ name: 'category' })">
          <span>全部品类</span><span>›</span>
        </div>
      </div>

      <el-carousel class="hero-carousel" height="400px" :interval="4000" arrow="hover">
        <el-carousel-item v-for="b in banners" :key="b.title">
          <div class="hero-media" :class="b.c">
            <h2>{{ b.title }}</h2>
            <p>{{ b.desc }}</p>
            <div class="btn" @click="router.push({ name: 'seckill' })">立即抢购</div>
          </div>
        </el-carousel-item>
      </el-carousel>

      <div class="side-panel">
        <div class="user-box">
          <div class="avatar" />
          <div>
            <div class="welcome">
              {{ isLoggedIn ? `Hi，${info.nickname}，欢迎回来` : 'Hi，欢迎来到极客数码' }}
            </div>
            <div class="user-btns">
              <template v-if="isLoggedIn">
                <el-button type="primary" size="small" @click="router.push({ name: 'member' })">会员中心</el-button>
                <el-button size="small" @click="router.push({ name: 'orders' })">我的订单</el-button>
              </template>
              <template v-else>
                <el-button type="primary" size="small" @click="router.push({ name: 'login' })">登录</el-button>
                <el-button size="small" @click="router.push({ name: 'login' })">注册</el-button>
              </template>
            </div>
          </div>
        </div>

        <div class="side-grid">
          <a @click="router.push({ name: 'seckill' })"><span class="ico">⚡</span>秒杀</a>
          <a @click="router.push({ name: 'newproduct' })"><span class="ico">🆕</span>新品首发</a>
          <a @click="router.push({ name: 'coupon' })"><span class="ico">🎫</span>领券</a>
          <a @click="router.push({ name: 'member' })"><span class="ico">👑</span>会员</a>
          <a @click="router.push({ name: 'help' })"><span class="ico">🛠️</span>延保服务</a>
        </div>

        <div class="side-notice">
          <strong>公告：</strong>{{ notice }}
        </div>
      </div>
    </div>

    <!-- 快捷入口 -->
    <div class="quick-entry">
      <div v-for="e in entryLinks" :key="e.name" class="entry-item" @click="router.push(e.to)">
        <div class="entry-icon" :class="e.c">{{ e.icon }}</div>
        <div class="entry-name">{{ e.name }}</div>
      </div>
    </div>

    <!-- 营销活动区 -->
    <div class="promo">
      <div class="promo-card">
        <div class="promo-head">
          <div class="promo-title"><em>限时</em>秒杀</div>
          <span class="promo-session">
            {{ seckillSession ? `${seckillSession.time} ${seckillSession.label || ''}` : '即将开始' }}
          </span>
        </div>
        <div class="seckill-list">
          <div v-for="s in seckillPreview" :key="s.id" class="seckill-item" @click="router.push({ name: 'seckill' })">
            <div class="pic" :class="s.product?.c" />
            <div><span class="price">¥{{ s.price }}</span><span class="old">¥{{ s.old }}</span></div>
          </div>
        </div>
        <div class="progress"><i /></div>
        <div class="progress-text">
          <template v-if="seckillPreview.length">
            已抢 {{ seckillPreview[0].percent }}%，仅剩 {{ seckillPreview[0].stock }} 件
          </template>
          <template v-else>本期秒杀即将开始</template>
        </div>
      </div>

      <div class="promo-card">
        <div class="promo-head">
          <div class="promo-title"><em>优惠券</em>领取</div>
          <a class="promo-more" @click="router.push({ name: 'coupon' })">领券中心 ›</a>
        </div>
        <div class="coupon-list">
          <div v-for="c in couponsPreview" :key="c.name" class="coupon" @click="router.push({ name: 'coupon' })">
            <div class="coupon-left">
              <div class="amount"><small>¥</small>{{ c.amount }}</div>
              <div class="cond">{{ c.cond }}</div>
            </div>
            <div class="coupon-right">
              <div class="name">{{ c.name }}</div>
              <div class="date">{{ c.date }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 热销榜 -->
    <div class="floor">
      <div class="floor-head">
        <div class="floor-title"><span>数码</span>热销榜</div>
        <div class="floor-tabs">
          <a v-for="t in hotTabs" :key="t" :class="{ on: hotTab === t }" @click="onHotTab(t)">{{ t }}</a>
        </div>
        <a class="floor-more" @click="router.push({ name: 'category', query: { cat: 'phone' } })">查看全部 ›</a>
      </div>
      <div class="product-grid">
        <ProductCard v-for="p in hotProducts" :key="p.id" :product="p" />
      </div>
    </div>

    <!-- 猜你喜欢 -->
    <div class="floor">
      <div class="floor-head">
        <div class="floor-title"><span>猜你</span>喜欢</div>
        <div class="floor-tabs">
          <a class="on">为你推荐</a>
          <a @click="shuffleGuess">换一批</a>
        </div>
        <a class="floor-more" @click="router.push({ name: 'search', query: { q: '' } })">更多 ›</a>
      </div>
      <div class="product-grid">
        <ProductCard v-for="p in guessProducts" :key="p.id" :product="p" />
      </div>
    </div>

    <!-- 服务保障 -->
    <div class="service-bar">
      <div class="service-item"><div class="ico">✅</div><div class="name">正品保证</div><div class="desc">官方授权 假一赔十</div></div>
      <div class="service-item"><div class="ico">🚚</div><div class="name">顺丰包邮</div><div class="desc">当日发 次日达</div></div>
      <div class="service-item"><div class="ico">🔄</div><div class="name">7 天无理由</div><div class="desc">极速退款 售后无忧</div></div>
      <div class="service-item"><div class="ico">🛠️</div><div class="name">延保服务</div><div class="desc">最长延保 3 年</div></div>
      <div class="service-item"><div class="ico">🔧</div><div class="name">上门维修</div><div class="desc">全国联保 快速响应</div></div>
    </div>
  </div>
</template>
