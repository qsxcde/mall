<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatStrip from '@/components/StatStrip.vue'
import CountUp from '@/components/CountUp.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyHint from '@/components/EmptyHint.vue'
import BarChart from '@/components/charts/BarChart.vue'
import DonutChart from '@/components/charts/DonutChart.vue'
import {
  applyWithdraw,
  fetchCashFlow,
  fetchFundSummary,
  fetchSettleOrders,
  fetchSettlementPage
} from '@/api/growth'
import { SETTLE_STATUS, pick } from '@/utils/dict'
import { fmtDate, int, money, moneyShort, percent } from '@/utils/format'

/**
 * 财务结算。
 *
 * 这一页的数字必须能手工复核，因此：
 * 佣金与服务费由成交额乘固定费率推导，本期实结为残差，
 * 无论看汇总卡、环形图还是表格，口径都是同一个。
 */
const loading = ref(true)
const summary = ref(null)

/* ---------- 结算单列表 ---------- */
const tab = ref('all')
const page = ref(1)
const size = 6
const table = reactive({ list: [], total: 0, tabs: {}, trend: [], composition: [] })
const tableLoading = ref(false)

/* ---------- 资金流水 ---------- */
const flow = reactive({ list: [], total: 0 })

async function loadTable() {
  tableLoading.value = true
  try {
    const res = await fetchSettlementPage({ status: tab.value, page: page.value, size })
    Object.assign(table, res)
  } finally {
    tableLoading.value = false
  }
}

async function loadFlow() {
  Object.assign(flow, await fetchCashFlow({ page: 1, size: 6 }))
}

async function loadSummary() {
  summary.value = await fetchFundSummary()
}

onMounted(async () => {
  try {
    await Promise.all([loadSummary(), loadTable(), loadFlow()])
  } catch {
    ElMessage.error('财务数据加载失败')
  } finally {
    loading.value = false
  }
})

function onTabChange() {
  page.value = 1
  loadTable()
}

function onPageChange(p) {
  page.value = p
  loadTable()
}

/* ---------- 汇总卡 ---------- */
const feeRate = computed(() => summary.value?.feeRate || { commission: 0.05, service: 0.006 })

const heroSubs = computed(() => {
  const s = summary.value
  if (!s) return []
  return [
    { label: '待结算', value: s.pending, note: '本期待结算' },
    { label: '结算中', value: s.settling, note: 'T+3 到账' },
    { label: '冻结中（售后）', value: s.frozen, note: '售后完结后解冻' },
    { label: '累计已结算', value: s.settledTotal, note: '历史累计' }
  ]
})

const statItems = computed(() => {
  const c = summary.value?.current
  if (!c) return []
  return [
    {
      key: 'gmv',
      label: '本期成交额',
      value: c.gmv,
      prefix: '¥',
      tone: 'brand',
      desc: `账期 ${c.range}`
    },
    {
      key: 'commission',
      label: '平台佣金',
      value: -c.commission,
      prefix: '¥',
      tone: 'coral',
      desc: `费率 ${percent(feeRate.value.commission)}`
    },
    {
      key: 'service',
      label: '支付服务费',
      value: -c.service,
      prefix: '¥',
      tone: 'amber',
      desc: `费率 ${percent(feeRate.value.service)}`
    },
    {
      key: 'refund',
      label: '退款扣减',
      value: -c.refund,
      prefix: '¥',
      tone: 'violet',
      desc: '含售后退款与赔付'
    },
    {
      key: 'settle',
      label: '本期实结',
      value: c.settle,
      prefix: '¥',
      tone: 'green',
      desc: `实结率 ${percent(c.rate)}`
    }
  ]
})

/** 扣费构成加上百分比，供环形图旁的图例使用 */
const compositionWithPercent = computed(() => {
  const items = table.composition || []
  const total = items.reduce((acc, it) => acc + it.value, 0) || 1
  return items.map((it) => ({ ...it, percent: it.value / total }))
})

const trendItems = computed(() =>
  (table.trend || []).map((t) => ({ label: t.label, value: t.value }))
)

/* ---------- 提现 ---------- */
const withdrawDialog = reactive({ visible: false, amount: 0, fee: 0, arrival: 0, requestId: '' })
const balance = computed(() => summary.value?.balance || 0)

/** 提现请求号：服务端据此幂等去重，同一次提现意图内必须保持不变 */
function genRequestId() {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID()
  return `wd-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function openWithdraw() {
  withdrawDialog.amount = balance.value
  // 请求号在弹窗打开时生成：失败重试复用同一个值，服务端才认得出是同一笔
  withdrawDialog.requestId = genRequestId()
  withdrawDialog.visible = true
  recalcWithdraw()
}

/** 手续费 0.1%，单笔封顶 500 元 —— 与 api 层的口径保持一致 */
function recalcWithdraw() {
  const amt = Number(withdrawDialog.amount) || 0
  withdrawDialog.fee = Math.min(500, Math.round(amt * 0.001 * 100) / 100)
  withdrawDialog.arrival = Math.max(0, amt - withdrawDialog.fee)
}

/**
 * 「全部提现」：把可提现余额填进输入框并重算手续费。
 *
 * 抽成具名函数而不是写成模板内联的 `withdrawDialog.amount = balance; recalcWithdraw()`：
 * Prettier（本项目配置 `semi: false`）会把 `;` 分隔的语句序列拆成多行并去掉分号，
 * 而 Vue 指令的表达式位置**不允许语句序列** —— 会直接编译失败
 * （`Error parsing JavaScript expression: Unexpected token`）。
 */
function withdrawAll() {
  withdrawDialog.amount = balance.value
  recalcWithdraw()
}

async function confirmWithdraw() {
  if (withdrawDialog.amount <= 0) {
    ElMessage.warning('提现金额需大于 0')
    return
  }
  if (withdrawDialog.amount > balance.value) {
    ElMessage.warning('提现金额不能超过可提现余额')
    return
  }
  await applyWithdraw(withdrawDialog.amount, withdrawDialog.requestId)
  withdrawDialog.visible = false
  ElMessage.success(`提现申请已提交，¥${money(withdrawDialog.arrival)} 预计 T+1 到账`)
  // 余额改为回读服务端：本地扣减与服务端口径一旦分叉，下次刷新就会「跳回去」
  await loadSummary().catch(() => {})
  await loadFlow().catch(() => {})
}

/* ---------- 结算单明细抽屉 ---------- */
const drawer = reactive({ visible: false, settlement: null, orders: [] })

async function openSettleDetail(row) {
  const res = await fetchSettleOrders(row.id)
  drawer.settlement = res.settlement
  drawer.orders = res.orders
  drawer.visible = true
}

function exportBill() {
  ElMessage.success('账单导出中，完成后将通过站内信通知')
}
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="Finance · Settlement" title="财务" title-accent="结算">
      <template #desc>
        结算周期 <b>T+3</b> · 平台佣金率 <b>{{ percent(feeRate.commission) }}</b> · 下一结算日
        <b>10-08</b>
      </template>
      <template #actions>
        <el-button @click="ElMessage.success('发票申请已提交，平台将在 3 个工作日内处理')">
          <el-icon><Tickets /></el-icon> 发票管理
        </el-button>
        <el-button @click="exportBill">
          <el-icon><Download /></el-icon> 导出账单
        </el-button>
      </template>
    </PageHeader>

    <el-skeleton v-if="loading" :rows="8" animated />

    <template v-else-if="summary">
      <!-- ============ 资金总览 ============ -->
      <section class="fund rise" style="animation-delay: 0.02s">
        <div class="fund__main">
          <div class="fund__label">可提现余额</div>
          <div class="fund__value"><CountUp :value="summary.balance" prefix="¥" /></div>
          <div class="fund__note">已结算款项可直接提现，预计 T+1 到账</div>
        </div>

        <div class="fund__subs">
          <div v-for="s in heroSubs" :key="s.label" class="fund__sub">
            <span class="fund__sub-label">{{ s.label }}</span>
            <b class="num"><small>¥</small>{{ money(s.value) }}</b>
            <span class="fund__sub-note">{{ s.note }}</span>
          </div>
        </div>

        <div class="fund__cta">
          <el-button type="primary" size="large" @click="openWithdraw">
            <el-icon><Wallet /></el-icon> 申请提现
          </el-button>
          <el-button size="large" @click="ElMessage.success('资金流水可在下方列表查看')">
            <el-icon><Document /></el-icon> 资金流水
          </el-button>
        </div>
      </section>

      <StatStrip :items="statItems" />

      <!-- ============ 趋势 + 扣费构成 ============ -->
      <div class="row row--2">
        <section class="mz-card">
          <div class="mz-card__head">
            <h3>结算趋势</h3>
            <span class="sub">各账期实结金额 · 单位：元</span>
          </div>
          <div class="chart-box">
            <BarChart :items="trendItems" tone="green" :height="232" :formatter="moneyShort" />
          </div>
        </section>

        <section class="mz-card">
          <div class="mz-card__head">
            <h3>本期扣费构成</h3>
            <span class="sub">账期 {{ summary.current.range }}</span>
          </div>
          <div class="compose">
            <DonutChart
              :items="compositionWithPercent"
              :size="150"
              :thickness="19"
              :center-value="percent(summary.current.rate)"
              center-label="实结率"
            />
            <ul class="compose__list">
              <li v-for="c in compositionWithPercent" :key="c.name">
                <i :style="{ background: `var(--${c.tone})` }" />
                <span class="compose__name">{{ c.name }}</span>
                <b class="num">¥{{ moneyShort(c.value) }}</b>
                <span class="compose__pct mono">{{ percent(c.percent) }}</span>
              </li>
            </ul>
          </div>
          <div class="compose__hint">
            数据口径：平台佣金 {{ percent(feeRate.commission) }}，支付服务费
            {{ percent(feeRate.service) }}。开通「极客优选」会员计划后佣金可下调至 4.2%。
          </div>
        </section>
      </div>

      <!-- ============ 结算单 ============ -->
      <section class="mz-card">
        <div class="mz-card__head">
          <h3>结算单</h3>
          <span class="sub">共 {{ table.tabs.all || 0 }} 个账期 · 点击行可查看明细</span>
        </div>

        <el-tabs
          :model-value="tab"
          class="tabs"
          @update:model-value="
            (v) => {
              tab = v
              onTabChange()
            }
          "
        >
          <el-tab-pane name="all">
            <template #label
              ><span>全部</span><span class="tab-count">{{ table.tabs.all || 0 }}</span></template
            >
          </el-tab-pane>
          <el-tab-pane name="settled">
            <template #label
              ><span>已结算</span
              ><span class="tab-count">{{ table.tabs.settled || 0 }}</span></template
            >
          </el-tab-pane>
          <el-tab-pane name="settling">
            <template #label
              ><span>结算中</span
              ><span class="tab-count">{{ table.tabs.settling || 0 }}</span></template
            >
          </el-tab-pane>
          <el-tab-pane name="pending">
            <template #label
              ><span>待结算</span
              ><span class="tab-count">{{ table.tabs.pending || 0 }}</span></template
            >
          </el-tab-pane>
        </el-tabs>

        <el-table
          v-loading="tableLoading"
          :data="table.list"
          row-key="id"
          @row-click="openSettleDetail"
        >
          <el-table-column label="账期" min-width="176">
            <template #default="{ row }">
              <div class="mono order-no">{{ row.range }}</div>
              <div class="sub">{{ row.id }}</div>
            </template>
          </el-table-column>

          <el-table-column label="成交额" min-width="112" align="right">
            <template #default="{ row }">
              <span class="num amount">¥{{ money(row.gmv) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="平台佣金" min-width="108" align="right">
            <template #default="{ row }">
              <span class="mono minus">-¥{{ money(row.commission) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="服务费" min-width="96" align="right">
            <template #default="{ row }">
              <span class="mono minus">-¥{{ money(row.service) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="退款扣减" min-width="104" align="right">
            <template #default="{ row }">
              <span class="mono minus">-¥{{ money(row.refund) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="实结金额" min-width="124" align="right">
            <template #default="{ row }">
              <span class="num settle">¥{{ money(row.settle) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="实结率" width="86" align="right">
            <template #default="{ row }">
              <span class="mono">{{ percent(row.rate) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="98">
            <template #default="{ row }">
              <StatusTag :tone="pick(SETTLE_STATUS, row.status).tone">
                {{ pick(SETTLE_STATUS, row.status).text }}
              </StatusTag>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="76" align="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click.stop="openSettleDetail(row)">
                明细
              </el-button>
            </template>
          </el-table-column>

          <template #empty>
            <EmptyHint icon="Wallet" title="该状态下暂无结算单" desc="切换其它状态查看" />
          </template>
        </el-table>

        <div v-if="table.total" class="mz-pager">
          <span class="info"
            >共 <b>{{ table.total }}</b> 个账期</span
          >
          <div class="spacer" />
          <el-pagination
            layout="prev, pager, next"
            :current-page="page"
            :page-size="size"
            :total="table.total"
            background
            @current-change="onPageChange"
          />
        </div>
      </section>

      <!-- ============ 资金流水 ============ -->
      <section class="mz-card">
        <div class="mz-card__head">
          <h3>资金流水</h3>
          <span class="sub">最近 {{ flow.list.length }} 条</span>
        </div>
        <el-table :data="flow.list" row-key="id">
          <el-table-column label="流水号" min-width="140">
            <template #default="{ row }">
              <span class="mono">{{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="120">
            <template #default="{ row }">
              <StatusTag :tone="row.tone" :dot="false">{{ row.label }}</StatusTag>
            </template>
          </el-table-column>
          <el-table-column label="时间" min-width="150">
            <template #default="{ row }">
              <span class="sub">{{ fmtDate(row.at, true) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="180">
            <template #default="{ row }">
              <span class="sub">{{ row.remark }}</span>
            </template>
          </el-table-column>
          <el-table-column label="金额" min-width="124" align="right">
            <template #default="{ row }">
              <span class="mono" :class="row.amount >= 0 ? 'plus' : 'minus'">
                {{ row.amount >= 0 ? '+' : '-' }}¥{{ money(Math.abs(row.amount)) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="账户余额" min-width="124" align="right">
            <template #default="{ row }">
              <span class="num">¥{{ money(row.balance) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </template>

    <!-- ============ 提现弹窗 ============ -->
    <el-dialog v-model="withdrawDialog.visible" title="申请提现" width="480">
      <el-form label-position="top">
        <el-form-item label="提现金额（元）">
          <el-input-number
            v-model="withdrawDialog.amount"
            :min="1"
            :max="balance"
            :precision="2"
            :controls="false"
            style="width: 100%"
            @change="recalcWithdraw"
          />
          <div class="field-hint">
            可提现余额 ¥{{ money(balance) }}
            <el-button text type="primary" size="small" @click="withdrawAll()">
              全部提现
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <div class="quote">
        <div class="quote__title">提现试算</div>
        <div class="quote__row">
          <span>本次提现</span><b>¥{{ money(withdrawDialog.amount || 0) }}</b>
        </div>
        <div class="quote__row">
          <span>手续费（0.1%，单笔封顶 500 元）</span><b>¥{{ money(withdrawDialog.fee) }}</b>
        </div>
        <div class="quote__row is-total">
          <span>实际到账</span><b class="num">¥{{ money(withdrawDialog.arrival) }}</b>
        </div>
      </div>

      <template #footer>
        <el-button @click="withdrawDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmWithdraw">确认提现</el-button>
      </template>
    </el-dialog>

    <!-- ============ 结算单明细抽屉 ============ -->
    <el-drawer v-model="drawer.visible" size="620px" :with-header="false">
      <template v-if="drawer.settlement">
        <div class="drawer-head">
          <div>
            <span class="drawer-kicker">Settlement Detail</span>
            <b class="mono">{{ drawer.settlement.id }}</b>
          </div>
          <el-button circle text @click="drawer.visible = false">
            <el-icon><Close /></el-icon>
          </el-button>
        </div>

        <div class="drawer-body">
          <section class="block">
            <h4>结算概要</h4>
            <div class="kv">
              <span>账期</span><b>{{ drawer.settlement.range }}</b>
            </div>
            <div class="kv">
              <span>状态</span>
              <StatusTag :tone="pick(SETTLE_STATUS, drawer.settlement.status).tone">
                {{ pick(SETTLE_STATUS, drawer.settlement.status).text }}
              </StatusTag>
            </div>
            <div class="kv">
              <span>订单笔数</span><b>{{ int(drawer.settlement.orderCount) }} 笔</b>
            </div>
            <div class="kv">
              <span>实结率</span><b>{{ percent(drawer.settlement.rate) }}</b>
            </div>
          </section>

          <section class="block">
            <h4>金额构成</h4>
            <div class="money">
              <div class="money__row">
                <span>成交额</span><b>¥{{ money(drawer.settlement.gmv) }}</b>
              </div>
              <div class="money__row">
                <span>平台佣金</span
                ><b class="minus">-¥{{ money(drawer.settlement.commission) }}</b>
              </div>
              <div class="money__row">
                <span>支付服务费</span><b class="minus">-¥{{ money(drawer.settlement.service) }}</b>
              </div>
              <div class="money__row">
                <span>退款扣减</span><b class="minus">-¥{{ money(drawer.settlement.refund) }}</b>
              </div>
              <div class="money__row is-total">
                <span>实结金额</span><b class="num">¥{{ money(drawer.settlement.settle) }}</b>
              </div>
            </div>
          </section>

          <section class="block">
            <h4>
              订单流水（抽样 {{ drawer.orders.length }} /
              {{ int(drawer.settlement.orderCount) }} 笔）
            </h4>
            <div class="orders">
              <div v-for="o in drawer.orders" :key="o.id" class="order">
                <div class="order__line">
                  <span class="mono">{{ o.id }}</span>
                  <span class="sub">{{ fmtDate(o.paidAt) }}</span>
                </div>
                <div class="order__line">
                  <span class="order__name">{{ o.product.name }} × {{ o.qty }}</span>
                </div>
                <div class="order__nums">
                  <span>商品 ¥{{ money(o.goods) }}</span>
                  <span class="minus">佣金 -¥{{ money(o.commission) }}</span>
                  <span class="settle">实结 ¥{{ money(o.settle) }}</span>
                </div>
              </div>
            </div>
          </section>
        </div>

        <div class="drawer-foot">
          <el-button @click="exportBill">导出账单</el-button>
          <el-button type="primary" @click="ElMessage.success('资金流水可在列表页查看')">
            查看资金流水
          </el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ============ 资金总览 ============ */
.fund {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  gap: 30px;
  padding: 24px 28px;
  border-radius: var(--r-l);
  color: #fff;
  background: linear-gradient(115deg, #0c1322, #16233d 55%, #1b2c4d);
  box-shadow: var(--shadow-m);
}
.fund::before {
  content: '';
  position: absolute;
  inset: 0;
  opacity: 0.5;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.045) 1px, transparent 1px);
  background-size: 34px 34px;
}
.fund::after {
  content: '';
  position: absolute;
  right: -70px;
  bottom: -140px;
  width: 360px;
  height: 360px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(200, 146, 47, 0.3), transparent 68%);
}
.fund > * {
  position: relative;
  z-index: 1;
}
.fund__main {
  min-width: 250px;
}
.fund__label {
  font-size: 12px;
  color: #93a2c0;
  letter-spacing: 0.08em;
}
.fund__value {
  font-size: 40px;
  font-weight: 600;
  line-height: 1.1;
  margin: 8px 0 6px;
}
.fund__note {
  font-size: 12px;
  color: #93a2c0;
}
.fund__subs {
  display: flex;
  flex: 1;
}
.fund__sub {
  padding: 0 22px;
  border-left: 1px solid rgba(255, 255, 255, 0.1);
  min-width: 0;
}
.fund__sub:first-child {
  border-left: none;
  padding-left: 0;
}
.fund__sub-label {
  display: block;
  font-size: 11.5px;
  color: #93a2c0;
  margin-bottom: 7px;
  white-space: nowrap;
}
.fund__sub b {
  font-size: 18px;
  font-weight: 600;
  white-space: nowrap;
}
.fund__sub b small {
  font-size: 12px;
  opacity: 0.6;
}
.fund__sub-note {
  display: block;
  font-size: 10.5px;
  color: #7b8aa8;
  margin-top: 4px;
  white-space: nowrap;
}
.fund__cta {
  display: flex;
  flex-direction: column;
  gap: 9px;
  flex-shrink: 0;
}
.fund__cta :deep(.el-button) {
  height: 40px;
  margin-left: 0;
}

/* ============ 布局 ============ */
.row {
  display: grid;
  gap: 16px;
  align-items: start;
}
.row--2 {
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
}
.chart-box {
  padding: 14px 12px 12px;
}

/* ============ 扣费构成 ============ */
.compose {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 16px 18px 8px;
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
  width: 46px;
  text-align: right;
  color: var(--text-3);
  font-size: 11px;
}
.compose__hint {
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.7;
  background: #faf8f4;
  border-radius: 10px;
  margin: 4px 18px 16px;
  padding: 9px 12px;
}

/* ============ 表格 ============ */
.tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.tabs :deep(.el-tabs__content) {
  display: none;
}
.sub {
  font-size: 11px;
  color: var(--text-3);
}
.order-no {
  font-weight: 600;
}
.amount {
  font-size: 14px;
  font-weight: 600;
}
.minus {
  color: var(--coral);
}
.plus {
  color: var(--green);
}
.settle {
  font-size: 14px;
  font-weight: 600;
  color: var(--green);
}
:deep(.el-table__row) {
  cursor: pointer;
}

/* ============ 提现弹窗 ============ */
.field-hint {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 6px;
  line-height: 1.6;
  display: flex;
  align-items: center;
  gap: 4px;
}
.quote {
  background: #faf8f4;
  border: 1px solid var(--line-soft);
  border-radius: 12px;
  padding: 14px 16px;
}
.quote__title {
  font-size: 12.5px;
  font-weight: 800;
  margin-bottom: 8px;
}
.quote__row {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  padding: 5px 0;
}
.quote__row span {
  color: var(--text-2);
}
.quote__row b {
  font-family: var(--font-mono);
}
.quote__row.is-total {
  border-top: 1px dashed var(--line);
  margin-top: 6px;
  padding-top: 10px;
}
.quote__row.is-total b {
  font-size: 17px;
  color: var(--brand);
}

/* ============ 抽屉 ============ */
.drawer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 22px 16px;
  border-bottom: 1px solid var(--line);
}
.drawer-kicker {
  display: block;
  font-size: 10px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--text-3);
  font-weight: 700;
}
.drawer-head b {
  font-size: 14px;
}
.drawer-head .el-button {
  margin-left: auto;
}
.drawer-body {
  padding: 18px 22px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-height: calc(100vh - 170px);
  overflow-y: auto;
}
.drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 22px;
  border-top: 1px solid var(--line);
}
.block h4 {
  font-size: 12px;
  font-weight: 800;
  color: var(--text-2);
  letter-spacing: 0.04em;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--line);
}
.kv {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
  line-height: 2;
}
.kv span {
  color: var(--text-3);
}
.kv b {
  font-weight: 600;
}
.money {
  max-width: 340px;
}
.money__row {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  padding: 5px 0;
}
.money__row span {
  color: var(--text-3);
}
.money__row b {
  font-family: var(--font-mono);
}
.money__row.is-total {
  border-top: 1px dashed var(--line);
  margin-top: 5px;
  padding-top: 9px;
}
.money__row.is-total b {
  font-size: 16px;
  font-weight: 600;
  color: var(--green);
}

.orders {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.order {
  border: 1px solid var(--line-soft);
  border-radius: 11px;
  padding: 11px 13px;
  background: #fdfcfa;
}
.order__line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
}
.order__name {
  font-size: 12.5px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.order__nums {
  display: flex;
  gap: 14px;
  margin-top: 7px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
}

/* ============ 桌面端响应式 ============ */
@media (max-width: 1500px) {
  .row--2 {
    grid-template-columns: minmax(0, 1fr);
  }
  .fund {
    flex-wrap: wrap;
    gap: 22px;
  }
  .fund__subs {
    flex-basis: 100%;
    order: 3;
  }
  .fund__cta {
    flex-direction: row;
  }
}
@media (max-width: 1240px) {
  .compose {
    flex-direction: column;
    align-items: center;
  }
  .compose__list {
    width: 100%;
  }
}
</style>
