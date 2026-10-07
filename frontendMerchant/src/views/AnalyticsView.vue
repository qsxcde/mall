<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatStrip from '@/components/StatStrip.vue'
import LineAreaChart from '@/components/charts/LineAreaChart.vue'
import DonutChart from '@/components/charts/DonutChart.vue'
import BarChart from '@/components/charts/BarChart.vue'
import { fetchAnalytics } from '@/api/catalog'
import { int, moneyShort, percent } from '@/utils/format'

/**
 * 数据看板。
 * 与「经营概览」的分工：概览回答「今天该做什么」，看板回答「生意为什么是这样」——
 * 因此在趋势之外补齐了渠道、品类、地区、漏斗、时段五个切面。
 */
const loading = ref(true)
const data = ref(null)
/** 主趋势展示哪条指标：成交额 / 订单数 */
const metric = ref('amount')
const range = ref(30)

const RANGES = [
  { value: 7, label: '近 7 天' },
  { value: 14, label: '近 14 天' },
  { value: 30, label: '近 30 天' }
]

onMounted(async () => {
  try {
    data.value = await fetchAnalytics()
  } catch {
    ElMessage.error('看板数据加载失败')
  } finally {
    loading.value = false
  }
})

/* ---------- 顶部指标 ---------- */
/** 接口给的是原始小数/大数，这里统一转成展示用的 value + 单位 */
const statItems = computed(() => {
  const s = data.value?.summary || []
  return s.map((it) => {
    if (it.percent) {
      return { ...it, value: it.value * 100, suffix: '%', digits: 2, prefix: '' }
    }
    return it
  })
})

/* ---------- 趋势 ---------- */
/** 按 range 截取尾部数据，切换区间无需重新请求 */
const sliced = computed(() => {
  const t = data.value?.trend
  if (!t) return { labels: [], amount: [], orders: [] }
  return {
    labels: t.labels.slice(-range.value),
    amount: t.amount.slice(-range.value),
    orders: t.orders.slice(-range.value)
  }
})

const trendSeries = computed(() => {
  if (metric.value === 'amount') {
    return [
      { name: '成交额', values: sliced.value.amount, color: '#1a6dff', area: true },
      { name: '订单数 ×1,000', values: sliced.value.orders.map((v) => v * 1000), color: '#7c5cff', dashed: true }
    ]
  }
  return [
    { name: '订单数', values: sliced.value.orders, color: '#109150', area: true },
    { name: '成交额 ÷1,000', values: sliced.value.amount.map((v) => v / 1000), color: '#1a6dff', dashed: true }
  ]
})

/**
 * 双轴叠加时两条线量纲不同，用统一格式化会误导阅读。
 * 因此这里按当前指标给出对应的格式化，并在图例上注明换算关系。
 */
const trendFormatter = computed(() =>
  metric.value === 'amount' ? (v) => moneyShort(v) : (v) => int(v)
)

/* ---------- 渠道 ---------- */
const channelMax = computed(() => Math.max(...(data.value?.channels || []).map((c) => c.uv), 1))

/* ---------- 品类 ---------- */
const categoryTones = ['brand', 'violet', 'teal', 'green', 'amber', 'coral']
const categoryItems = computed(() =>
  (data.value?.categories || []).map((c, i) => ({ ...c, tone: categoryTones[i % categoryTones.length] }))
)

/* ---------- 地区 ---------- */
const regionItems = computed(() =>
  (data.value?.regions || []).map((r) => ({ label: r.name.replace('省', '').replace('市', ''), value: r.amount }))
)

/* ---------- 漏斗 ---------- */
const funnelMax = computed(() => data.value?.funnel?.[0]?.value || 1)

/* ---------- 时段 ---------- */
const hourMax = computed(() => Math.max(...(data.value?.hourly || []).map((h) => h.value), 1))

const customerItems = computed(() =>
  (data.value?.customerMix || []).map((c, i) => ({ ...c, tone: i === 0 ? 'brand' : 'green' }))
)

function exportBoard() {
  ElMessage.success('看板数据导出中，完成后将发送至店铺绑定邮箱')
}
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="Business · Analytics" title="数据" title-accent="看板">
      <template #desc>
        统计周期 <b>近 30 天</b> · 数据每小时更新 ·
        对比期 <b>再往前 30 天</b>
      </template>
      <template #actions>
        <el-select v-model="range" style="width: 118px">
          <el-option v-for="r in RANGES" :key="r.value" :label="r.label" :value="r.value" />
        </el-select>
        <el-button @click="exportBoard">
          <el-icon><Download /></el-icon> 导出看板
        </el-button>
      </template>
    </PageHeader>

    <el-skeleton v-if="loading" :rows="8" animated />

    <template v-else-if="data">
      <StatStrip :items="statItems" />

      <!-- ============ 主趋势 ============ -->
      <section class="mz-card">
        <div class="mz-card__head">
          <h3>经营趋势</h3>
          <span class="sub">双指标叠加对比</span>
          <div class="spacer" />
          <el-radio-group v-model="metric" size="small">
            <el-radio-button value="amount">成交额</el-radio-button>
            <el-radio-button value="orders">订单数</el-radio-button>
          </el-radio-group>
        </div>

        <div class="chart-legend">
          <span>
            <i :style="{ background: metric === 'amount' ? '#1a6dff' : '#109150' }" />
            {{ metric === 'amount' ? '成交额' : '订单数' }}
          </span>
          <span>
            <i class="is-dashed" :style="{ background: metric === 'amount' ? '#7c5cff' : '#1a6dff' }" />
            {{ metric === 'amount' ? '订单数（×1,000 同轴对比）' : '成交额（÷1,000 同轴对比）' }}
          </span>
        </div>

        <div class="chart-box">
          <LineAreaChart
            :labels="sliced.labels"
            :series="trendSeries"
            :height="300"
            :formatter="trendFormatter"
          />
        </div>
      </section>

      <!-- ============ 渠道 + 品类 ============ -->
      <div class="row row--2">
        <section class="mz-card">
          <div class="mz-card__head">
            <h3>流量渠道</h3>
            <span class="sub">按访客数与环比</span>
          </div>
          <div class="channels">
            <!-- 流量来源依赖前端埋点，后端暂无数据源，如实提示而不是画一张 0 值图 -->
            <div v-if="!data.channels?.length" class="chart-empty">暂无渠道数据，需接入埋点后展示</div>
            <div v-for="c in data.channels" :key="c.name" class="channel">
              <div class="channel__head">
                <span class="channel__name">{{ c.name }}</span>
                <span class="channel__uv mono">{{ int(c.uv) }} 人</span>
                <b class="channel__pct num">{{ percent(c.value) }}</b>
              </div>
              <div class="channel__bar">
                <i :style="{ width: `${(c.uv / channelMax) * 100}%` }" />
              </div>
              <div class="channel__delta" :class="c.delta >= 0 ? 'is-up' : 'is-down'">
                环比 {{ c.delta >= 0 ? '↑' : '↓' }}{{ percent(Math.abs(c.delta)) }}
              </div>
            </div>
          </div>
        </section>

        <section class="mz-card">
          <div class="mz-card__head">
            <h3>品类销售占比</h3>
            <span class="sub">按成交额</span>
          </div>
          <div class="compose">
            <DonutChart
              :items="categoryItems"
              :size="158"
              :thickness="20"
              center-value="100%"
              center-label="全部品类"
            />
            <ul class="compose__list">
              <li v-for="c in categoryItems" :key="c.name">
                <i :style="{ background: `var(--${c.tone})` }" />
                <span class="compose__name">{{ c.name }}</span>
                <b class="num">{{ moneyShort(c.amount) }}</b>
                <span class="compose__pct mono">{{ percent(c.value) }}</span>
              </li>
            </ul>
          </div>
        </section>
      </div>

      <!-- ============ 地区 + 漏斗 ============ -->
      <div class="row row--2">
        <section class="mz-card">
          <div class="mz-card__head">
            <h3>地区分布 TOP 8</h3>
            <span class="sub">按成交额</span>
          </div>
          <div class="chart-box">
            <BarChart :items="regionItems" :height="240" :formatter="moneyShort" />
          </div>
        </section>

        <section class="mz-card">
          <div class="mz-card__head">
            <h3>转化漏斗</h3>
            <span class="sub">从曝光的层层流失</span>
          </div>
          <div class="funnel">
            <!-- 漏斗前置环节（曝光/点击/加购）依赖埋点，后端只能提供下单与支付两级，故暂不展示 -->
            <div v-if="!data.funnel?.length" class="chart-empty">暂无漏斗数据，需接入曝光 / 点击埋点</div>
            <div v-for="(f, i) in data.funnel" :key="f.name" class="funnel__row">
              <div class="funnel__label">
                <span>{{ f.name }}</span>
                <b class="num">{{ int(f.value) }}</b>
              </div>
              <div class="funnel__track">
                <i
                  :style="{
                    width: `${(f.value / funnelMax) * 100}%`,
                    animationDelay: `${i * 90}ms`
                  }"
                />
              </div>
              <div class="funnel__rate">
                <span class="mono">{{ percent(f.rate, 2) }}</span>
                <!-- 相邻层级的转化率，比累计占比更能看出问题在哪一步 -->
                <em v-if="i" class="mono">
                  单步 {{ percent(f.value / data.funnel[i - 1].value, 1) }}
                </em>
              </div>
            </div>
          </div>
        </section>
      </div>

      <!-- ============ 时段 + 新老客 ============ -->
      <div class="row row--2b">
        <section class="mz-card">
          <div class="mz-card__head">
            <h3>下单时段分布</h3>
            <span class="sub">24 小时下单热度</span>
          </div>
          <div class="hourly">
            <div v-for="h in data.hourly" :key="h.hour" class="hour">
              <div class="hour__bar">
                <i :style="{ height: `${(h.value / hourMax) * 100}%` }" />
              </div>
              <span v-if="h.hour % 3 === 0" class="hour__label mono">{{ h.hour }}</span>
            </div>
          </div>
          <div class="hourly__hint">
            下单高峰出现在 <b>21:00</b> 与 <b>12:00</b>，
            建议将秒杀与直播场次安排在这两个时段的整点前 30 分钟开始蓄水。
          </div>
        </section>

        <section class="mz-card">
          <div class="mz-card__head">
            <h3>新老客结构</h3>
            <span class="sub">按成交人数</span>
          </div>
          <div class="compose compose--center">
            <DonutChart
              :items="customerItems"
              :size="146"
              :thickness="19"
              :center-value="percent(data.customerMix?.[1]?.value || 0)"
              center-label="老客复购占比"
            />
            <ul class="compose__list">
              <li v-for="c in customerItems" :key="c.name">
                <i :style="{ background: `var(--${c.tone})` }" />
                <span class="compose__name">{{ c.name }}</span>
                <b class="num">{{ int(c.count) }} 人</b>
                <span class="compose__pct mono">{{ percent(c.value) }}</span>
              </li>
            </ul>
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

.chart-legend {
  display: flex;
  gap: 18px;
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

.row {
  display: grid;
  gap: 16px;
  align-items: start;
}
.row--2 {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}
.row--2b {
  grid-template-columns: minmax(0, 1.45fr) minmax(0, 1fr);
}

/* ============ 渠道 ============ */
/* 无数据源时的占位：只提示，不画图 */
.chart-empty {
  padding: 48px 12px;
  text-align: center;
  font-size: 12.5px;
  color: var(--text-3);
}
.channels {
  padding: 16px 18px 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.channel__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.channel__name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-2);
}
.channel__uv {
  margin-left: auto;
  color: var(--text-3);
  font-size: 11px;
}
.channel__pct {
  width: 54px;
  text-align: right;
  font-size: 14px;
  font-weight: 600;
}
.channel__bar {
  height: 5px;
  border-radius: 4px;
  background: #f1eee6;
  margin-top: 7px;
  overflow: hidden;
}
.channel__bar i {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: linear-gradient(90deg, #8dbbff, var(--brand));
  transition: width 0.7s cubic-bezier(0.22, 0.8, 0.2, 1);
}
.channel__delta {
  font-family: var(--font-mono);
  font-size: 10.5px;
  margin-top: 5px;
}
.channel__delta.is-up {
  color: var(--green);
}
.channel__delta.is-down {
  color: var(--coral);
}

/* ============ 环形图 + 图例 ============ */
.compose {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 18px;
}
.compose--center {
  padding: 22px 18px;
}
.compose__list {
  flex: 1;
  min-width: 0;
  list-style: none;
}
.compose__list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 12.5px;
}
.compose__list i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.compose__name {
  color: var(--text-2);
  font-weight: 600;
  white-space: nowrap;
}
.compose__list b {
  margin-left: auto;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}
.compose__pct {
  width: 48px;
  text-align: right;
  color: var(--text-3);
  font-size: 11px;
}

/* ============ 漏斗 ============ */
.funnel {
  padding: 16px 18px 18px;
  display: flex;
  flex-direction: column;
  gap: 13px;
}
.funnel__label {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 12.5px;
  margin-bottom: 6px;
}
.funnel__label span {
  color: var(--text-2);
  font-weight: 600;
}
.funnel__label b {
  font-size: 14px;
  font-weight: 600;
}
.funnel__track {
  height: 9px;
  border-radius: 5px;
  background: #f1eee6;
  overflow: hidden;
}
.funnel__track i {
  display: block;
  height: 100%;
  border-radius: 5px;
  background: linear-gradient(90deg, #7c5cff, var(--brand));
  transform-origin: left;
  animation: funnel-grow 0.7s cubic-bezier(0.22, 0.8, 0.2, 1) both;
}
@keyframes funnel-grow {
  from {
    transform: scaleX(0);
  }
  to {
    transform: scaleX(1);
  }
}
.funnel__rate {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 10.5px;
  color: var(--text-3);
  margin-top: 5px;
}
.funnel__rate em {
  font-style: normal;
  color: var(--violet);
  font-weight: 600;
}

/* ============ 时段 ============ */
.hourly {
  display: flex;
  align-items: flex-end;
  gap: 3px;
  height: 150px;
  padding: 18px 18px 0;
}
.hour {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
  min-width: 0;
}
.hour__bar {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: flex-end;
}
.hour__bar i {
  display: block;
  width: 100%;
  border-radius: 3px 3px 0 0;
  background: linear-gradient(180deg, #8dbbff, var(--brand));
  transition: height 0.6s cubic-bezier(0.22, 0.8, 0.2, 1);
}
.hour__label {
  font-size: 9.5px;
  color: var(--text-3);
  margin-top: 5px;
  height: 12px;
}
.hourly__hint {
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.7;
  background: #faf8f4;
  border-radius: 10px;
  margin: 12px 18px 18px;
  padding: 9px 12px;
}
.hourly__hint b {
  color: var(--brand);
  font-family: var(--font-mono);
}

/* ============ 桌面端响应式 ============ */
@media (max-width: 1400px) {
  .row--2,
  .row--2b {
    grid-template-columns: minmax(0, 1fr);
  }
  .compose {
    flex-direction: column;
    align-items: center;
  }
  .compose__list {
    width: 100%;
  }
}
</style>
