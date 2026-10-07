<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { couponCats } from '@/data/constants'
import { useCouponStore } from '@/stores/coupon'

const router = useRouter()
const couponStore = useCouponStore()

const activeCat = ref('all')
const claiming = ref(0)
// 列表直接取后端返回值，类型筛选也交给后端
const list = computed(() => couponStore.list)

const load = async () => {
  try {
    await Promise.all([couponStore.loadTemplates(activeCat.value), couponStore.loadMine()])
  } catch (e) {
    /* 失败信息由拦截器提示 */
  }
}

onMounted(load)
watch(activeCat, load)

const claim = async (coupon) => {
  if (coupon.soldout) return
  if (couponStore.isClaimed(coupon.id)) {
    ElMessage.info('该券已领取，去「我的优惠券」使用吧')
    return
  }
  claiming.value = coupon.id
  try {
    // 领取后由后端刷新库存与已领百分比，前端不再自行估算
    await couponStore.claim(coupon.id)
    ElMessage.success('领取成功')
  } catch (e) {
    /* 失败信息由拦截器提示 */
  } finally {
    claiming.value = 0
  }
}
</script>

<template>
  <div class="container page-wrap">
    <!-- 领券 Banner -->
    <div class="cp-hero">
      <div class="cp-left">
        <div class="t1">🎫 领券中心</div>
        <div class="t2">天天领券 · 下单更省 · 新人专享大额券</div>
      </div>
      <div class="cp-right">
        <div><div class="num">{{ list.length }}</div><div class="lab">在领券种</div></div>
        <div class="sep" />
        <div><div class="num">¥50</div><div class="lab">新人立减</div></div>
        <div class="sep" />
        <div><div class="num">9折</div><div class="lab">会员折扣</div></div>
      </div>
    </div>

    <!-- 我的券入口 -->
    <div class="cp-mine">
      <div class="ic">🎟️</div>
      <div class="txt">
        <b>我的优惠券</b>
        <small>已领取 {{ couponStore.claimedCount }} 张 · 记得及时使用</small>
      </div>
      <button class="go" @click="router.push({ name: 'user', query: { tab: 'coupon' } })">去使用</button>
    </div>

    <!-- 分类筛选 -->
    <el-radio-group v-model="activeCat" class="cp-tabs">
      <el-radio-button v-for="c in couponCats" :key="c.key" :value="c.key">{{ c.name }}</el-radio-button>
    </el-radio-group>

    <div class="section-title">可领取优惠券</div>
    <div class="cp-grid">
      <div
        v-for="c in list"
        :key="c.id"
        class="cp-card"
        :class="{ limited: c.limited, soldout: c.soldout }"
      >
        <div class="cp-left-part">
          <div class="amt"><small v-if="c.unit === '¥'">¥</small>{{ c.amount }}<small v-if="c.unit !== '¥'">{{ c.unit }}</small></div>
          <div class="cond">{{ c.cond }}</div>
        </div>
        <div class="cp-right-part">
          <div class="name">{{ c.name }}</div>
          <div class="scope">{{ c.scope }}</div>
          <div class="date">{{ c.date }}</div>
          <div class="cp-bar"><i :style="{ width: c.percent + '%' }" /></div>
          <div class="cp-bar-txt">
            {{ c.soldout ? '已抢光' : `已抢 ${c.percent}%` }}<template v-if="c.limited"> · 限量大额</template>
          </div>
          <button
            class="cp-get"
            :class="{ got: couponStore.isClaimed(c.id), gone: c.soldout }"
            :disabled="c.soldout || claiming === c.id"
            :loading="claiming === c.id"
            @click="claim(c)"
          >
            {{ c.soldout ? '已抢光' : couponStore.isClaimed(c.id) ? '已领取' : '立即领取' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 规则 -->
    <div class="cp-tips">
      <h3>领券规则</h3>
      <ul>
        <li>每张优惠券每位用户限领 1 次，领取后请在有效期内使用。</li>
        <li>满减券 / 折扣券 / 免息券不可叠加使用，具体以结算页为准。</li>
        <li>部分券仅限指定品类或指定价位的商品使用，详见券面说明。</li>
        <li>券一经使用不可退回，逾期自动失效，敬请及时使用。</li>
      </ul>
    </div>
  </div>
</template>
