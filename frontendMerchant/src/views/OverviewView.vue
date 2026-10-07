<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatStrip from '@/components/StatStrip.vue'
import CountUp from '@/components/CountUp.vue'
import ProductThumb from '@/components/ProductThumb.vue'
import LineAreaChart from '@/components/charts/LineAreaChart.vue'
import DonutChart from '@/components/charts/DonutChart.vue'
import { fetchOverview } from '@/api/catalog'
import { int, money, moneyShort, percent } from '@/utils/format'

/**
 * 经营概览。
 * 页面职责：把「今天该关心什么」在首屏一次性说完 ——
 * 目标进度、核心指标、趋势、流量结构、待办、爆款与风险。
 */
const router = useRouter()
const loading = ref(true)
const data = ref(null)
/** 趋势区间：7 天 / 30 天 */
const range = ref('week')

onMounted(async () => {
  try {
    data.value = await fetchOverview()
  } catch {
    ElMessage.error('经营数据加载失败')
  } finally {
    loading.value = false
  }
})

/* ---------- 深色横幅 ---------- */
const hero = computed(() => data.value?.hero)

const heroSubs = computed(() => {
  const h = hero.value
  if (!h) return []
  return [
    { label: '累计客户', value: int(h.monthCustomers), suffix: '人', delta: h.customerGrowth },
    { label: '待结算', value: money(h.pendingSettle), prefix: '¥' },
    { label: '目标差额', value: money(Math.max(0, h.targetAmount - h.monthRevenue)), prefix: '¥' }
  ]
})

/** 顶部 KPI：把接口给的 delta 直接透传给 StatStrip */
const kpiItems = computed(() => data.value?.kpi || [])

/* ---------- 趋势 ---------- */
const trendSeries = computed(() => {
  const t = data.value?.trend?.[range.value]
  if (!t) return []
  return [
    { name: '本周期', values: t.current, color: '#1a6dff', area: true },
    { name: '上一周期', values: t.previous, color: '#b9c3d4', dashed: true }
  ]
})
const trendLabels = computed(() => data.value?.trend?.[range.value]?.labels || [])

/* ---------- 流量来源 ---------- */
const trafficTones = ['brand', 'violet', 'teal', 'green', 'amber', 'coral']
const trafficItems = computed(() =>
  (data.value?.traffic || []).map((t, i) => ({ ...t, tone: trafficTones[i % trafficTones.length] }))
)

/* ---------- 待办 ---------- */
function goTodo(item) {
  if (item.to) router.push(item.to)
}

function exportReport() {
  ElMessage.success('报表生成中，完成后将发送至店铺绑定邮箱')
}
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="Business · Overview" title="经营" title-accent="概览">
      <template #desc>
        <template v-if="data">
          今日成交 <b>¥{{ money(data.today.amount) }}</b> ·
          订单 <b>{{ data.today.orders }}</b> 笔 ·
          环比昨日 <b>{{ data.today.amountDelta >= 0 ? '+' : '' }}{{ percent(data.today.amountDelta) }}</b>
        </template>
        <template v-else>正在汇总经营数据…</template>
      </template>
      <template #actions>
        <el-button @click="exportReport">
          <el-icon><Download /></el-icon> 导出报表
        </el-button>
        <el-button type="primary" @click="router.push('/analytics')">
          <el-icon><TrendCharts /></el-icon> 查看数据看板
        </el-button>
      </template>
    </PageHeader>

    <el-skeleton v-if="loading" :rows="8" animated style="margin-top: 18px" />

    <template v-else-if="data">
      <!-- ============ 深色资金/营收横幅 ============ -->
      <section class="hero rise" style="animation-delay: 0.02s">
        <div class="hero__main">
          <div class="hero__label">本月营收</div>
          <div class="hero__value">
            <CountUp :value="hero.monthRevenue" prefix="¥" />
          </div>
          <div class="hero__delta">
            环比上月
            <b :class="hero.monthGrowth >= 0 ? 'is-up' : 'is-down'">
              {{ hero.monthGrowth >= 0 ? '↑' : '↓' }}{{ percent(Math.abs(hero.monthGrowth)) }}
            </b>
          </div>
        </div>

        <div class="hero__subs">
          <div v-for="s in heroSubs" :key="s.label" class="hero__sub">
            <span class="hero__sub-label">{{ s.label }}</span>
            <b class="num">
              <small v-if="s.prefix">{{ s.prefix }}</small>{{ s.value
              }}<small v-if="s.suffix" class="is-suffix">{{ s.suffix }}</small>
            </b>
            <span v-if="s.delta !== undefined" class="hero__sub-delta">
              {{ s.delta >= 0 ? '↑' : '↓' }}{{ percent(Math.abs(s.delta)) }}
            </span>
          </div>
        </div>

        <!-- 目标达成环 -->
        <div class="hero__ring">
          <el-progress
            type="circle"
            :percentage="Math.round(hero.targetRate * 100)"
            :width="108"
            :stroke-width="9"
            color="#ffd88a"
            :show-text="false"
          />
          <div class="hero__ring-txt">
            <b class="num">{{ Math.round(hero.targetRate * 100) }}%</b>
            <span>月度目标</span>
          </div>
        </div>
      </section>

      <!-- ============ 核心指标 ============ -->
      <StatStrip :items="kpiItems" />

      <!-- ============ 趋势 + 流量 ============ -->
      <div class="row row--2">
        <section class="mz-card">
          <div class="mz-card__head">
            <h3>销售趋势</h3>
            <span class="sub">按日成交额</span>
            <div class="spacer" />
            <el-radio-group v-model="range" size="small">
              <el-radio-button value="week">近 7 天</el-radio-button>
              <el-radio-button value="month">近 30 天</el-radio-button>
            </el-radio-group>
          </div>
          <div class="chart-legend">
            <span><i style="background: #1a6dff" />本周期</span>
            <span><i class="is-dashed" style="background: #b9c3d4" />上一周期</span>
          </div>
          <div class="chart-box">
            <LineAreaChart :labels="trendLabels" :series="trendSeries" :height="272" />
          </div>
        </section>

        <section class="mz-card">
          <div class="mz-card__head">
            <h3>流量来源</h3>
            <span class="sub">按访客占比</span>
          </div>
          <div class="traffic">
            <DonutChart
              :items="trafficItems"
              :size="168"
              :thickness="20"
              center-value="100%"
              center-label="全渠道访客"
            />
            <ul class="traffic__list">
              <li v-for="t in trafficItems" :key="t.name">
                <i :style="{ background: `var(--${t.tone})` }" />
                <span class="traffic__name">{{ t.name }}</span>
                <span class="traffic__orders mono">{{ int(t.orders) }} 单</span>
                <b class="traffic__pct num">{{ percent(t.value) }}</b>
              </li>
            </ul>
          </div>
        </section>
      </div>

      <!-- ============ 待办 + 爆款 + 库存 ============ -->
      <div class="row row--3">
        <section class="mz-card">
          <div class="mz-card__head"><h3>待处理事项</h3></div>
          <ul class="todo">
            <li v-for="t in data.todos" :key="t.key" class="todo__item" @click="goTodo(t)">
              <div class="todo__count" :class="`tone-${t.tone}`">{{ t.count }}</div>
              <div class="todo__body">
                <b>{{ t.label }}</b>
                <span>{{ t.desc }}</span>
              </div>
              <el-icon class="todo__arrow"><ArrowRight /></el-icon>
            </li>
          </ul>
        </section>

        <section class="mz-card">
          <div class="mz-card__head">
            <h3>热销商品 TOP 5</h3>
            <span class="sub">按销量</span>
          </div>
          <ul class="rank">
            <li v-for="(p, i) in data.topProducts" :key="p.id" class="rank__item">
              <span class="rank__no" :class="{ 'is-top': i < 3 }">{{ i + 1 }}</span>
              <ProductThumb :thumb="p.thumb" :tag="p.tag" :size="38" :radius="9" />
              <div class="rank__body">
                <b>{{ p.name }}</b>
                <div class="rank__bar">
                  <i :style="{ width: `${(p.sales / data.topProducts[0].sales) * 100}%` }" />
                </div>
              </div>
              <div class="rank__num">
                <b class="num">{{ int(p.sales) }}</b>
                <span class="mono">{{ moneyShort(p.amount) }}</span>
              </div>
            </li>
          </ul>
        </section>

        <section class="mz-card">
          <div class="mz-card__head">
            <h3>库存预警</h3>
            <span class="sub">{{ data.stockAlerts.length }} 个商品</span>
          </div>
          <ul class="rank">
            <li v-for="p in data.stockAlerts" :key="p.id" class="rank__item">
              <ProductThumb :thumb="p.thumb" :tag="p.tag" :size="38" :radius="9" />
              <div class="rank__body">
                <b>{{ p.name }}</b>
                <div class="rank__bar is-warn">
                  <i :style="{ width: `${Math.min(100, (p.stock / (p.safeStock * 3)) * 100)}%` }" />
                </div>
              </div>
              <div class="rank__num">
                <b class="num" :class="{ 'is-out': p.stock === 0 }">{{ p.stock }}</b>
                <span class="mono">安全 {{ p.safeStock }}</span>
              </div>
            </li>
          </ul>
          <div class="card-foot">
            <el-button text type="primary" @click="router.push('/products')">
              前往补货 <el-icon><ArrowRight /></el-icon>
            </el-button>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ============ 深色横幅 ============ */
.hero {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  gap: 34px;
  padding: 24px 28px;
  border-radius: var(--r-l);
  color: #fff;
  background: linear-gradient(115deg, #0c1322, #16233d 55%, #1b2c4d);
  box-shadow: var(--shadow-m);
}
/* 网格纹理 + 品牌蓝/珊瑚光晕：避免大面积深色显得沉闷 */
.hero::before {
  content: "";
  position: absolute;
  inset: 0;
  opacity: 0.5;
  background-image: linear-gradient(rgba(255, 255, 255, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.045) 1px, transparent 1px);
  background-size: 34px 34px;
}
.hero::after {
  content: "";
  position: absolute;
  right: -60px;
  top: -110px;
  width: 340px;
  height: 340px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(26, 109, 255, 0.45), transparent 68%);
}
.hero > * {
  position: relative;
  z-index: 1;
}

.hero__main {
  min-width: 226px;
}
.hero__label {
  font-size: 12px;
  color: #93a2c0;
  letter-spacing: 0.08em;
}
.hero__value {
  font-size: 40px;
  font-weight: 600;
  line-height: 1.1;
  margin: 8px 0 6px;
}
.hero__delta {
  font-size: 12px;
  color: #93a2c0;
}
.hero__delta b {
  font-family: var(--font-mono);
  margin-left: 4px;
}
.hero__delta b.is-up {
  color: #7ef0b1;
}
.hero__delta b.is-down {
  color: #ffab8f;
}

/* 用分隔线把三组副指标切开，替代卡片边框 */
.hero__subs {
  display: flex;
  gap: 0;
  flex: 1;
}
.hero__sub {
  padding: 0 26px;
  border-left: 1px solid rgba(255, 255, 255, 0.1);
  min-width: 0;
}
.hero__sub:first-child {
  border-left: none;
  padding-left: 0;
}
.hero__sub-label {
  display: block;
  font-size: 11.5px;
  color: #93a2c0;
  margin-bottom: 7px;
}
.hero__sub b {
  font-size: 20px;
  font-weight: 600;
}
.hero__sub b small {
  font-size: 12px;
  opacity: 0.6;
}
.hero__sub b small.is-suffix {
  margin-left: 2px;
}
.hero__sub-delta {
  display: block;
  font-family: var(--font-mono);
  font-size: 11px;
  color: #7ef0b1;
  margin-top: 4px;
}

.hero__ring {
  position: relative;
  flex-shrink: 0;
}
.hero__ring-txt {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.hero__ring-txt b {
  font-size: 22px;
  font-weight: 600;
}
.hero__ring-txt span {
  font-size: 10.5px;
  color: #93a2c0;
  margin-top: 2px;
}

/* ============ 通用两列 / 三列 ============ */
.row {
  display: grid;
  gap: 16px;
  align-items: start;
}
.row--2 {
  grid-template-columns: minmax(0, 1.62fr) minmax(0, 1fr);
}
.row--3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.chart-legend {
  display: flex;
  gap: 16px;
  padding: 12px 18px 0;
  font-size: 11.5px;
  color: var(--text-2);
}
.chart-legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.chart-legend i {
  width: 14px;
  height: 3px;
  border-radius: 2px;
}
.chart-legend i.is-dashed {
  background-image: linear-gradient(90deg, currentColor 0 50%, transparent 50% 100%);
  background-size: 6px 3px;
}
.chart-box {
  padding: 8px 10px 12px;
}

/* ============ 流量来源 ============ */
.traffic {
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 18px;
}
.traffic__list {
  flex: 1;
  min-width: 0;
  list-style: none;
}
.traffic__list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 0;
  font-size: 12.5px;
}
.traffic__list i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.traffic__name {
  color: var(--text-2);
  font-weight: 600;
  white-space: nowrap;
}
.traffic__orders {
  margin-left: auto;
  color: var(--text-3);
  font-size: 11px;
  white-space: nowrap;
}
.traffic__pct {
  width: 52px;
  text-align: right;
  font-weight: 600;
  font-size: 13px;
}

/* ============ 待办 ============ */
.todo {
  list-style: none;
  padding: 14px 18px 18px;
}
.todo__item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.18s;
}
.todo__item:hover {
  background: #faf8f4;
}
.todo__count {
  width: 40px;
  height: 40px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 600;
  flex-shrink: 0;
}
.todo__count.tone-brand {
  color: var(--brand);
  background: var(--brand-soft);
}
.todo__count.tone-coral {
  color: var(--coral);
  background: var(--coral-soft);
}
.todo__count.tone-amber {
  color: var(--amber);
  background: var(--amber-soft);
}
.todo__count.tone-violet {
  color: var(--violet);
  background: var(--violet-soft);
}
.todo__body {
  min-width: 0;
}
.todo__body b {
  display: block;
  font-size: 13px;
}
.todo__body span {
  font-size: 11.5px;
  color: var(--text-3);
}
.todo__arrow {
  margin-left: auto;
  color: var(--text-3);
  font-size: 13px;
}

/* ============ 榜单 / 预警 ============ */
.rank {
  list-style: none;
  padding: 10px 18px 14px;
}
.rank__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
}
.rank__no {
  width: 18px;
  text-align: center;
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 600;
  color: var(--text-3);
  flex-shrink: 0;
}
.rank__no.is-top {
  color: var(--brand);
}
.rank__body {
  flex: 1;
  min-width: 0;
}
.rank__body b {
  display: block;
  font-size: 12.5px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rank__bar {
  height: 4px;
  border-radius: 3px;
  background: #f1eee6;
  margin-top: 6px;
  overflow: hidden;
}
.rank__bar i {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(90deg, #8dbbff, var(--brand));
  transition: width 0.7s cubic-bezier(0.22, 0.8, 0.2, 1);
}
.rank__bar.is-warn i {
  background: linear-gradient(90deg, #ffd08a, var(--amber));
}
.rank__num {
  text-align: right;
  flex-shrink: 0;
}
.rank__num b {
  display: block;
  font-size: 14px;
  font-weight: 600;
}
.rank__num b.is-out {
  color: var(--coral);
}
.rank__num span {
  font-size: 10.5px;
  color: var(--text-3);
}

.card-foot {
  padding: 0 18px 16px;
}

/* ============ 桌面端响应式 ============ */
@media (max-width: 1560px) {
  .row--3 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 1400px) {
  .row--2 {
    grid-template-columns: minmax(0, 1fr);
  }
  .hero {
    flex-wrap: wrap;
    gap: 22px;
  }
  .hero__subs {
    flex-basis: 100%;
    order: 3;
  }
  .hero__sub:first-child {
    padding-left: 0;
  }
}
@media (max-width: 1180px) {
  .row--3 {
    grid-template-columns: minmax(0, 1fr);
  }
  .traffic {
    flex-direction: column;
    align-items: center;
  }
  .traffic__list {
    width: 100%;
  }
}
</style>
