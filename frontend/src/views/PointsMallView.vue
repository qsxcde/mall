<script setup>
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pointsApi } from '@/api/marketing'
import { useUserStore } from '@/stores/user'

const user = useUserStore()
const { info } = storeToRefs(user)

const goods = ref([])
const exchanging = ref(0)

const load = async () => {
  try {
    goods.value = await pointsApi.goods()
  } catch (e) {
    goods.value = []
  }
}

onMounted(async () => {
  // 先刷新积分，保证「是否够兑」与后端一致
  await user.loadProfile().catch(() => {})
  await load()
})

// 是否够兑以服务端返回的 affordable 为准，前端不再自己比较
const canAfford = (g) => g.affordable !== false

const exchange = (g) => {
  if (g.stock <= 0) return ElMessage.warning('该商品已兑完')
  if (!canAfford(g)) return ElMessage.warning('积分不足，先去赚积分吧')
  ElMessageBox.confirm(`确认使用 ${g.points} 积分兑换「${g.name}」吗？`, '积分兑换', { type: 'info' })
    .then(async () => {
      exchanging.value = g.id
      try {
        // 扣积分 + 扣库存由后端同一事务完成，返回剩余积分
        const result = await pointsApi.exchange(g.id)
        if (result?.remainPoints != null) {
          user.setPoints(result.remainPoints)
        }
        ElMessage.success(`兑换成功，消耗 ${result?.points ?? g.points} 积分，剩余 ${result?.remainPoints} 积分`)
        await load()
      } catch (e) {
        /* 失败信息由拦截器提示 */
      } finally {
        exchanging.value = 0
      }
    })
    .catch(() => {})
}

const myPoints = computed(() => (info.value.points || 0).toLocaleString())
</script>

<template>
  <div class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item :to="{ name: 'member' }">会员中心</el-breadcrumb-item>
      <el-breadcrumb-item>积分商城</el-breadcrumb-item>
    </el-breadcrumb>

    <!-- 积分头图 -->
    <div class="pm-hero">
      <div class="pm-left">
        <div class="pm-t1">🎁 积分商城</div>
        <div class="pm-t2">积分当钱花 · 好礼免费兑 · 每日上新</div>
      </div>
      <div class="pm-points">
        <div class="pm-points-label">我的积分</div>
        <div class="pm-points-num">{{ myPoints }}</div>
      </div>
    </div>

    <div class="section-title">可兑换好礼</div>
    <div class="pm-grid">
      <div v-for="g in goods" :key="g.id" class="pm-card" :class="{ out: g.stock <= 0 }">
        <div class="pm-img" :class="g.c">{{ g.icon }}</div>
        <div class="pm-info">
          <div class="pm-name">{{ g.name }}</div>
          <div class="pm-desc">{{ g.desc }}</div>
          <div class="pm-foot">
            <div class="pm-cost"><b>{{ g.points }}</b> 积分</div>
            <el-button
              size="small"
              type="primary"
              :disabled="!canAfford(g) || g.stock <= 0 || exchanging === g.id"
              :loading="exchanging === g.id"
              @click="exchange(g)"
            >
              {{ g.stock <= 0 ? '已兑完' : canAfford(g) ? '立即兑换' : '积分不足' }}
            </el-button>
          </div>
          <div class="pm-stock">剩余 {{ g.stock }} 份</div>
        </div>
      </div>
    </div>

    <div class="pm-tips">
      <h3>兑换规则</h3>
      <ul>
        <li>积分兑换成功后不可撤销，优惠券类奖品会发放至「我的优惠券」。</li>
        <li>实物奖品将在 3 个工作日内发货，可在「我的订单」查看物流。</li>
        <li>积分获取：购物返积分、签到、评价晒单、完善资料、邀请好友等。</li>
        <li>积分有效期为获得年度起 12 个月，逾期自动清零。</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.pm-hero {
  display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap;
  background: linear-gradient(120deg, #2b3a67 0%, #1a6dff 60%, #4da3ff 100%);
  color: #fff; border-radius: 12px; padding: 26px 32px;
}
.pm-t1 { font-size: 26px; font-weight: bold; }
.pm-t2 { font-size: 14px; opacity: .92; margin-top: 8px; }
.pm-points { text-align: right; }
.pm-points-label { font-size: 13px; opacity: .9; }
.pm-points-num { font-size: 34px; font-weight: bold; font-variant-numeric: tabular-nums; }
.pm-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.pm-card { background: #fff; border: 1px solid var(--border); border-radius: 12px; overflow: hidden; transition: all .25s; }
.pm-card:hover { box-shadow: 0 6px 20px rgba(26,109,255,.14); transform: translateY(-3px); }
.pm-card.out { opacity: .7; }
.pm-img { height: 120px; display: flex; align-items: center; justify-content: center; font-size: 44px; }
.pm-info { padding: 14px; }
.pm-name { font-size: 14px; font-weight: 600; }
.pm-desc { font-size: 12px; color: var(--text-light); margin-top: 6px; }
.pm-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 12px; }
.pm-cost { color: var(--accent); font-size: 13px; }
.pm-cost b { font-size: 18px; }
.pm-stock { font-size: 11px; color: var(--text-light); margin-top: 8px; }
.pm-tips { background: #fff; border-radius: 12px; padding: 18px 22px; margin-top: 24px; border: 1px solid var(--border); }
.pm-tips h3 { font-size: 15px; margin-bottom: 10px; }
.pm-tips ul { padding-left: 18px; color: #666; font-size: 13px; line-height: 2; list-style: disc; }
@media (max-width: 992px) { .pm-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 768px) { .pm-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
