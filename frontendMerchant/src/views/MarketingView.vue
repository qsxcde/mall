<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatStrip from '@/components/StatStrip.vue'
import StatusTag from '@/components/StatusTag.vue'
import ProductThumb from '@/components/ProductThumb.vue'
import EmptyHint from '@/components/EmptyHint.vue'
import BarChart from '@/components/charts/BarChart.vue'
import { useTableQuery } from '@/composables/useTableQuery'
import { fetchCoupons, fetchPromoChannels, fetchPromoPage } from '@/api/growth'
import { useMerchantStore } from '@/stores/merchant'
import { PROMO_STATUS, PROMO_TABS, PROMO_TYPES, pick } from '@/utils/dict'
import { fmtDay, int, money, moneyShort, percent } from '@/utils/format'

/**
 * 营销中心。
 * 活动用卡片而非表格呈现 —— 营销物料需要一眼看到「类型 + 周期 + 产出」，
 * 表格的密集列反而不利于快速扫读。
 */
const store = useMerchantStore()

const {
  params,
  list,
  total,
  loading,
  extras,
  pageCount,
  isFiltered,
  load,
  changePage,
  reset
} = useTableQuery(fetchPromoPage, {
  defaultParams: { status: 'all', keyword: '', type: 'all', sort: 'gmv_desc' },
  watchKeys: ['status', 'keyword', 'type', 'sort'],
  size: 6
})

watch(
  () => store.keyword,
  (v) => {
    params.keyword = v
  }
)

/* ---------- 渠道与优惠券 ---------- */
const channels = ref([])
const coupons = ref([])

onMounted(async () => {
  const [c, cp] = await Promise.all([fetchPromoChannels(), fetchCoupons()])
  channels.value = c
  coupons.value = cp
})

const typeOptions = computed(() => [
  { value: 'all', label: '全部类型' },
  ...Object.entries(PROMO_TYPES).map(([value, v]) => ({ value, label: v.text }))
])

const sortOptions = [
  { value: 'gmv_desc', label: '成交额 · 高→低' },
  { value: 'roi_desc', label: 'ROI · 高→低' },
  { value: 'cost_desc', label: '花费 · 高→低' },
  { value: 'start_desc', label: '开始时间 · 新→旧' }
]

/* ---------- 统计条 ---------- */
const statItems = computed(() => {
  const s = extras.value.stats || {}
  return [
    { key: 'running', label: '进行中活动', value: s.running || 0, suffix: '个', tone: 'coral', desc: '正在引流中' },
    {
      key: 'gmv',
      label: '活动带来成交额',
      value: s.runningGmv || 0,
      prefix: '¥',
      tone: 'green',
      desc: '进行中活动累计'
    },
    { key: 'cost', label: '累计推广花费', value: s.totalCost || 0, prefix: '¥', tone: 'amber', desc: '含直通车与站外投放' },
    {
      key: 'roas',
      label: '整体 ROI',
      value: s.roas || 0,
      suffix: '倍',
      digits: 2,
      tone: 'brand',
      desc: '总成交额 / 总花费'
    },
    {
      key: 'coupon',
      label: '优惠券核销率',
      value: (s.couponUsedRate || 0) * 100,
      suffix: '%',
      digits: 1,
      tone: 'violet',
      desc: '已领取券的核销占比'
    }
  ]
})

/* ---------- 活动卡片 ---------- */
/** 活动进度：按「已过天数 / 总天数」计算，用于卡片上的进度条 */
function progressOf(p) {
  const start = new Date(p.startAt).getTime()
  const end = new Date(p.endAt).getTime()
  const now = Date.now()
  if (now >= end) return 1
  if (now <= start) return 0
  return (now - start) / (end - start || 1)
}

const channelMaxGmv = computed(() => Math.max(...channels.value.map((c) => c.gmv), 1))

const channelBars = computed(() =>
  channels.value.map((c) => ({ label: c.name, value: c.gmv, tone: c.tone }))
)

async function togglePromo(p) {
  const next = p.status === 'running' ? 'paused' : 'running'
  try {
    await ElMessageBox.confirm(
      next === 'paused' ? `暂停后「${p.name}」将立即停止对外曝光。` : `「${p.name}」将恢复对外曝光。`,
      next === 'paused' ? '暂停活动' : '恢复活动',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  p.status = next
  ElMessage.success(next === 'paused' ? '活动已暂停' : '活动已恢复')
  await load()
}

/* ---------- 新建活动 ---------- */
const createDialog = reactive({
  visible: false,
  name: '',
  type: 'discount',
  range: [],
  budget: 50000
})

const TYPE_OPTIONS = Object.entries(PROMO_TYPES).map(([value, v]) => ({ value, label: v.text }))

async function submitPromo() {
  if (!createDialog.name.trim()) {
    ElMessage.warning('请填写活动名称')
    return
  }
  if (!createDialog.range?.length) {
    ElMessage.warning('请选择活动时间')
    return
  }
  createDialog.visible = false
  ElMessage.success(`活动「${createDialog.name}」已提交审核`)
  await Promise.all([load(), store.loadBadges(true)])
}

function manageCoupons() {
  const box = document.querySelector('#couponSection')
  box?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  ElMessage.success('已定位到优惠券列表')
}
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="Growth · Marketing Center" title="营销" title-accent="中心">
      <template #desc>
        进行中 <b>{{ extras.stats?.running || 0 }}</b> 个活动 ·
        累计推广花费 <b>¥{{ moneyShort(extras.stats?.totalCost || 0) }}</b> ·
        整体 ROI <b>{{ (extras.stats?.roas || 0).toFixed(2) }}</b>
      </template>
      <template #actions>
        <el-button @click="manageCoupons">
          <el-icon><Ticket /></el-icon> 优惠券管理
        </el-button>
        <el-button type="primary" @click="createDialog.visible = true">
          <el-icon><Plus /></el-icon> 新建活动
        </el-button>
      </template>
    </PageHeader>

    <StatStrip :items="statItems" />

    <!-- ============ 活动列表 ============ -->
    <section class="mz-card">
      <el-tabs v-model="params.status" class="tabs">
        <el-tab-pane v-for="t in PROMO_TABS" :key="t.key" :name="t.key">
          <template #label>
            <span>{{ t.label }}</span>
            <span class="tab-count">{{ extras.tabs?.[t.key] ?? 0 }}</span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <div class="mz-toolbar">
        <span class="mz-toolbar__label">活动类型</span>
        <el-select v-model="params.type" style="width: 138px">
          <el-option v-for="o in typeOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>

        <span class="mz-toolbar__label">排序</span>
        <el-select v-model="params.sort" style="width: 152px">
          <el-option v-for="o in sortOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>

        <el-button text type="primary" @click="reset()">重置筛选</el-button>

        <div class="spacer" />
        <span class="result-hint">共 <b>{{ total }}</b> 个活动</span>
      </div>

      <div v-loading="loading" class="promo-wrap">
        <div v-if="list.length" class="pgrid">
          <article v-for="p in list" :key="p.id" class="promo">
            <div class="promo__head">
              <StatusTag :tone="pick(PROMO_TYPES, p.type).tone" :dot="false">
                {{ pick(PROMO_TYPES, p.type).text }}
              </StatusTag>
              <StatusTag :tone="pick(PROMO_STATUS, p.status).tone">
                {{ pick(PROMO_STATUS, p.status).text }}
              </StatusTag>
              <span class="promo__id mono">{{ p.id }}</span>
            </div>

            <h4 class="promo__name">{{ p.name }}</h4>

            <div class="promo__range">
              <el-icon><Calendar /></el-icon>
              {{ fmtDay(p.startAt) }} ~ {{ fmtDay(p.endAt) }}
            </div>

            <div class="promo__bar">
              <i :style="{ width: `${Math.round(progressOf(p) * 100)}%` }" />
            </div>
            <div class="promo__progress">{{ percent(progressOf(p), 0) }} 已完成</div>

            <div class="promo__metrics">
              <div class="pm">
                <span>活动成交额</span>
                <b class="num">{{ moneyShort(p.gmv) }}</b>
              </div>
              <div class="pm">
                <span>ROI</span>
                <b class="num" :class="p.roi >= 4 ? 'good' : ''">{{ p.roi ? p.roi.toFixed(2) : '—' }}</b>
              </div>
              <div class="pm">
                <span>花费 / 预算</span>
                <b class="mono">{{ moneyShort(p.cost) }} / {{ moneyShort(p.budget) }}</b>
              </div>
              <div class="pm">
                <span>活动销量</span>
                <b class="mono">{{ int(p.sold) }} 件</b>
              </div>
            </div>

            <div class="promo__products">
              <ProductThumb
                v-for="pr in p.products"
                :key="pr.id"
                :thumb="pr.thumb"
                :tag="pr.tag"
                :size="34"
                :radius="8"
              />
              <span class="promo__count">共 {{ p.joined }} 个商品参与</span>
            </div>

            <div class="promo__acts">
              <el-button size="small" @click="ElMessage.success('活动数据已更新至最新')">
                数据
              </el-button>
              <el-button
                size="small"
                :type="p.status === 'running' ? 'primary' : 'default'"
                @click="togglePromo(p)"
              >
                {{ p.status === 'running' ? '暂停' : '启用' }}
              </el-button>
              <el-button size="small" text @click="ElMessage.success('已进入活动编辑（演示）')">
                编辑
              </el-button>
            </div>
          </article>
        </div>

        <EmptyHint
          v-else-if="!loading"
          icon="Promotion"
          :title="isFiltered ? '没有匹配的营销活动' : '还没有创建活动'"
          :desc="isFiltered ? '试试切换状态或清空筛选条件' : '点击右上角「新建活动」开始引流'"
        />
      </div>

      <div v-if="total" class="mz-pager">
        <span class="info">共 <b>{{ total }}</b> 个活动</span>
        <div class="spacer" />
        <el-pagination
          layout="prev, pager, next"
          :current-page="params.page"
          :page-count="pageCount"
          background
          @current-change="changePage"
        />
      </div>
    </section>

    <!-- ============ 渠道 + 优惠券 ============ -->
    <div class="row row--2">
      <section class="mz-card">
        <div class="mz-card__head">
          <h3>推广渠道效果</h3>
          <span class="sub">按成交额贡献</span>
        </div>
        <div class="chart-box">
          <BarChart :items="channelBars" :height="180" :formatter="moneyShort" />
        </div>
        <el-table :data="channels" row-key="name" size="small" class="channel-table">
          <el-table-column label="渠道" min-width="106">
            <template #default="{ row }">
              <div class="channel">
                <i :style="{ background: `var(--${row.tone})` }" />
                <span>{{ row.name }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="花费" min-width="96" align="right">
            <template #default="{ row }">
              <span class="mono">¥{{ int(row.cost) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="成交额" min-width="106" align="right">
            <template #default="{ row }">
              <span class="num">¥{{ int(row.gmv) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="点击" min-width="88" align="right">
            <template #default="{ row }">
              <span class="mono">{{ int(row.click) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="转化率" min-width="90" align="right">
            <template #default="{ row }">
              <span class="mono">{{ percent(row.convert, 2) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="ROI" min-width="88" align="right">
            <template #default="{ row }">
              <div class="roi">
                <span class="roi__bar">
                  <i :style="{ width: `${(row.gmv / channelMaxGmv) * 100}%` }" />
                </span>
                <b class="num">{{ row.roi.toFixed(2) }}</b>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section id="couponSection" class="mz-card">
        <div class="mz-card__head">
          <h3>店铺优惠券</h3>
          <span class="sub">{{ coupons.length }} 张</span>
        </div>
        <div class="coupons">
          <div v-for="c in coupons" :key="c.id" class="coupon" :class="`is-${c.status}`">
            <div class="coupon__left">
              <div class="coupon__amount num">
                <template v-if="c.amount">
                  <small>¥</small>{{ c.amount }}
                </template>
                <template v-else>折扣</template>
              </div>
              <div class="coupon__cond">{{ c.threshold ? `满 ${c.threshold} 可用` : '无门槛' }}</div>
            </div>
            <div class="coupon__right">
              <div class="coupon__name">{{ c.name }}</div>
              <div class="coupon__meta">
                已领 {{ int(c.taken) }} / {{ int(c.total) }} · 已用 {{ int(c.used) }}
              </div>
              <div class="coupon__bar">
                <i :style="{ width: `${c.taken ? (c.used / c.taken) * 100 : 0}%` }" />
              </div>
            </div>
            <StatusTag :tone="pick(PROMO_STATUS, c.status).tone" size="small">
              {{ pick(PROMO_STATUS, c.status).text }}
            </StatusTag>
          </div>
        </div>
      </section>
    </div>

    <!-- ============ 新建活动 ============ -->
    <el-dialog v-model="createDialog.visible" title="新建营销活动" width="560">
      <el-form label-position="top">
        <el-form-item label="活动名称">
          <el-input v-model="createDialog.name" placeholder="如：双十一抢先购 · 全场满减" />
        </el-form-item>
        <el-form-item label="活动类型">
          <el-radio-group v-model="createDialog.type">
            <el-radio-button v-for="t in TYPE_OPTIONS" :key="t.value" :value="t.value">
              {{ t.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="活动时间">
          <el-date-picker
            v-model="createDialog.range"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="推广预算（元）">
          <el-input-number v-model="createDialog.budget" :min="0" :step="10000" />
          <div class="field-hint">预算将用于活动期间的流量投放，可随时调整</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submitPromo">提交审核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.tabs :deep(.el-tabs__content) {
  display: none;
}
.result-hint {
  font-size: 12px;
  color: var(--text-3);
}
.result-hint b {
  color: var(--brand);
  font-family: var(--font-mono);
}

/* ============ 活动卡片 ============ */
.promo-wrap {
  min-height: 300px;
}
.pgrid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  padding: 18px;
}
.promo {
  border: 1px solid var(--line);
  border-radius: var(--r-m);
  padding: 15px 16px 14px;
  background: #fff;
  transition: transform 0.22s cubic-bezier(0.2, 0.8, 0.2, 1), box-shadow 0.22s;
}
.promo:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-m);
}
.promo__head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.promo__id {
  margin-left: auto;
  color: var(--text-3);
  font-size: 10.5px;
}
.promo__name {
  font-size: 14.5px;
  font-weight: 800;
  line-height: 1.4;
  margin: 11px 0 8px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 40px;
}
.promo__range {
  display: flex;
  align-items: center;
  gap: 6px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
}
.promo__bar {
  height: 5px;
  border-radius: 4px;
  background: #f1eee6;
  margin-top: 11px;
  overflow: hidden;
}
.promo__bar i {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: linear-gradient(90deg, #8dbbff, var(--brand));
  transition: width 0.7s cubic-bezier(0.22, 0.8, 0.2, 1);
}
.promo__progress {
  font-size: 10.5px;
  color: var(--text-3);
  margin-top: 5px;
}
.promo__metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 9px 14px;
  margin-top: 13px;
  padding-top: 13px;
  border-top: 1px dashed var(--line-soft);
}
.pm span {
  display: block;
  font-size: 10.5px;
  color: var(--text-3);
  margin-bottom: 3px;
}
.pm b {
  font-size: 14px;
  font-weight: 600;
}
.pm b.good {
  color: var(--green);
}
.promo__products {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 13px;
}
.promo__count {
  font-size: 11px;
  color: var(--text-3);
  margin-left: 4px;
}
.promo__acts {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 12px;
}

/* ============ 渠道 ============ */
.row {
  display: grid;
  gap: 16px;
  align-items: start;
}
.row--2 {
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
}
.chart-box {
  padding: 14px 12px 4px;
}
.channel-table {
  border-top: 1px solid var(--line);
}
.channel {
  display: flex;
  align-items: center;
  gap: 7px;
  font-weight: 600;
}
.channel i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.roi {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: flex-end;
}
.roi__bar {
  flex: 1;
  max-width: 46px;
  height: 4px;
  border-radius: 3px;
  background: #f1eee6;
  overflow: hidden;
}
.roi__bar i {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(90deg, #8dbbff, var(--brand));
}

/* ============ 优惠券 ============ */
.coupons {
  padding: 14px 18px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.coupon {
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 11px 13px;
  background: #fff;
  transition: border-color 0.18s;
}
.coupon:hover {
  border-color: #c9dcff;
}
.coupon.is-ended,
.coupon.is-paused {
  opacity: 0.62;
}
.coupon__left {
  text-align: center;
  min-width: 66px;
  padding-right: 12px;
  border-right: 1px dashed var(--line);
}
.coupon__amount {
  font-size: 20px;
  font-weight: 600;
  color: var(--brand);
  line-height: 1.15;
}
.coupon__amount small {
  font-size: 12px;
}
.coupon__cond {
  font-size: 10px;
  color: var(--text-3);
  margin-top: 2px;
}
.coupon__right {
  flex: 1;
  min-width: 0;
}
.coupon__name {
  font-size: 13px;
  font-weight: 700;
}
.coupon__meta {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 3px;
}
.coupon__bar {
  height: 4px;
  border-radius: 3px;
  background: #f1eee6;
  margin-top: 7px;
  overflow: hidden;
}
.coupon__bar i {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(90deg, #b6a2ff, var(--violet));
}

.field-hint {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 5px;
  line-height: 1.6;
}

/* ============ 桌面端响应式 ============ */
@media (max-width: 1620px) {
  .pgrid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 1440px) {
  .row--2 {
    grid-template-columns: minmax(0, 1fr);
  }
  .mz-toolbar__label {
    display: none;
  }
}
</style>
