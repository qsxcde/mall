<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatStrip from '@/components/StatStrip.vue'
import BulkBar from '@/components/BulkBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import ProductThumb from '@/components/ProductThumb.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import EmptyHint from '@/components/EmptyHint.vue'
import OrderDetailBlocks from '@/components/OrderDetailBlocks.vue'
import { useTableQuery } from '@/composables/useTableQuery'
import { closeOrders, fetchOrderPage, saveOrderNote, shipOrders } from '@/api/trade'
import { useMerchantStore } from '@/stores/merchant'
import { ORDER_STATUS, ORDER_TABS, PAY_OPTIONS, pick } from '@/utils/dict'
import { fmtDate, int, money } from '@/utils/format'

/**
 * 订单管理。
 *
 * 列表的筛选/分页/选中全部交给 useTableQuery，
 * 本组件只关心：怎么渲染一行、点操作时调哪个接口。
 */
const store = useMerchantStore()

const {
  params,
  list,
  total,
  loading,
  extras,
  selected,
  selectedCount,
  pageCount,
  isEmpty,
  isFiltered,
  load,
  changePage,
  reset,
  clearSelection
} = useTableQuery(fetchOrderPage, {
  defaultParams: {
    status: 'all',
    keyword: '',
    range: 'all',
    payWay: 'all',
    amountRange: 'all',
    sort: 'time_desc'
  },
  watchKeys: ['status', 'keyword', 'range', 'payWay', 'amountRange', 'sort'],
  size: 8
})

/** 顶栏搜索词 → 订单列表关键词 */
watch(
  () => store.keyword,
  (v) => {
    params.keyword = v
  }
)

const tableRef = ref(null)

/* ---------- 统计条 ---------- */
const statItems = computed(() => {
  const s = extras.value.stats || {}
  const t = extras.value.tabs || {}
  return [
    {
      key: 'today',
      label: '今日新增订单',
      value: s.todayCount || 0,
      suffix: '笔',
      tone: 'brand',
      desc: `今日成交 ¥${money(s.todayAmount || 0)}`
    },
    {
      key: 'waitShip',
      label: '待发货',
      value: s.waitShip || 0,
      suffix: '笔',
      tone: 'coral',
      alert: (s.waitShip || 0) > 0,
      desc: '超 24h 将影响体验分'
    },
    {
      key: 'shipped',
      label: '待收货',
      value: s.shipped || 0,
      suffix: '笔',
      tone: 'teal',
      desc: '包裹运输中'
    },
    {
      key: 'after',
      label: '售后处理中',
      value: s.after || 0,
      suffix: '笔',
      tone: 'amber',
      desc: '需 48 小时内响应'
    },
    {
      key: 'amount',
      label: '累计交易额',
      value: s.totalAmount || 0,
      prefix: '¥',
      tone: 'green',
      desc: `共 ${int(t.all || 0)} 笔订单`
    }
  ]
})

/* ---------- 表格列 ---------- */
const payOptions = PAY_OPTIONS
const amountOptions = [
  { value: 'all', label: '不限金额' },
  { value: '0-1000', label: '¥0 - 1,000' },
  { value: '1000-5000', label: '¥1,000 - 5,000' },
  { value: '5000-10000', label: '¥5,000 - 10,000' },
  { value: '10000-999999', label: '¥10,000 以上' }
]
const rangeOptions = [
  { value: 'all', label: '全部时间' },
  { value: 'today', label: '今天' },
  { value: '7', label: '近 7 天' },
  { value: '30', label: '近 30 天' }
]
const sortOptions = [
  { value: 'time_desc', label: '下单时间 · 新→旧' },
  { value: 'time_asc', label: '下单时间 · 旧→新' },
  { value: 'amount_desc', label: '实付金额 · 高→低' },
  { value: 'amount_asc', label: '实付金额 · 低→高' }
]

/** 每行按状态给出差异化操作，避免所有行都堆一长串按钮 */
const actionsOf = (order) => {
  const base = [{ key: 'detail', label: '详情', plain: true }]
  const map = {
    wait_pay: [
      { key: 'remind', label: '催付款', plain: true },
      ...base,
      { key: 'close', label: '关闭', danger: true }
    ],
    wait_ship: [
      { key: 'ship', label: '发货', primary: true },
      { key: 'note', label: '备注', plain: true },
      ...base
    ],
    shipped: [
      { key: 'trace', label: '物流', plain: true },
      { key: 'remindRecv', label: '提醒收货', plain: true },
      ...base
    ],
    wait_review: [{ key: 'urge', label: '催评价', plain: true }, ...base],
    after: [{ key: 'afterSale', label: '处理售后', danger: true }, ...base],
    done: [...base, { key: 'again', label: '再来一单', plain: true }],
    closed: [...base]
  }
  return map[order.status] || base
}

/* ---------- 选中同步 ---------- */
function onSelectionChange(rows) {
  selected.value = rows.map((r) => r.id)
}

function clearAll() {
  clearSelection()
  tableRef.value?.clearSelection()
}

/* ---------- 发货弹窗 ---------- */
const shipDialog = reactive({ visible: false, ids: [], express: '顺丰速运', waybill: '', note: '' })

function openShip(ids) {
  const targets = list.value.filter((o) => ids.includes(o.id) && o.status === 'wait_ship')
  if (!targets.length) {
    ElMessage.warning('所选订单中没有「待发货」的订单')
    return
  }
  shipDialog.ids = targets.map((o) => o.id)
  shipDialog.express = '顺丰速运'
  shipDialog.waybill = `SF${String(Date.now()).slice(-10)}`
  shipDialog.note = ''
  shipDialog.visible = true
}

async function confirmShip() {
  await shipOrders(shipDialog.ids, { express: shipDialog.express, waybill: shipDialog.waybill })
  shipDialog.visible = false
  ElMessage.success(`已发货 ${shipDialog.ids.length} 笔订单 · ${shipDialog.express}`)
  clearAll()
  await Promise.all([load(), store.loadBadges(true)])
}

/** 顶部「批量发货」：优先用已选，没选就取当前页全部待发货 */
function batchShip() {
  const ids = selected.value.length
    ? selected.value
    : list.value.filter((o) => o.status === 'wait_ship').map((o) => o.id)
  if (!ids.length) {
    ElMessage.warning('当前没有可发货的订单')
    return
  }
  openShip(ids)
}

/* ---------- 备注弹窗 ---------- */
const noteDialog = reactive({ visible: false, id: '', note: '' })

function openNote(id) {
  const order = list.value.find((o) => o.id === id)
  noteDialog.id = id
  // 回填的是商家备注：buyer 的留言是 note，与这里可编辑的 merchantNote 是两回事
  noteDialog.note = order?.merchantNote || ''
  noteDialog.visible = true
}

async function confirmNote() {
  await saveOrderNote(noteDialog.id, noteDialog.note.trim())
  noteDialog.visible = false
  ElMessage.success('备注已保存')
  await load()
}

/* ---------- 关闭订单 ---------- */
async function closeOrder(id) {
  try {
    await ElMessageBox.confirm(`关闭后订单不可恢复，占用库存会自动回滚。订单号 ${id}`, '关闭订单', {
      type: 'warning',
      confirmButtonText: '确认关闭',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await closeOrders([id])
  ElMessage.success(`订单 ${id} 已关闭`)
  clearAll()
  await Promise.all([load(), store.loadBadges(true)])
}

/* ---------- 详情抽屉 ---------- */
const drawer = reactive({ visible: false, order: null })

function openDetail(id) {
  const order = list.value.find((o) => o.id === id)
  if (!order) return
  drawer.order = order
  drawer.visible = true
}

/**
 * 抽屉内的两个复合动作：先关抽屉，再执行相应操作。
 *
 * 抽成具名函数而不是写在模板里 `@click="drawer.visible = false; openShip(...)"`，
 * 原因：Prettier（配置 semi: false）会把 `;` 分隔的多语句内联表达式拆成多行并去掉分号，
 * 而 Vue 指令的表达式位置**不允许语句序列** —— 会直接编译报错
 * `Error parsing JavaScript expression: Unexpected token`。
 * 顺带也更易读、可加日志与埋点。
 */
function shipFromDrawer(order) {
  drawer.visible = false
  openShip([order.id])
}

function closeFromDrawer(order) {
  drawer.visible = false
  closeOrder(order.id)
}

/* ---------- 行操作分发 ---------- */
function onAction(key, order) {
  const handled = {
    detail: () => openDetail(order.id),
    ship: () => openShip([order.id]),
    note: () => openNote(order.id),
    close: () => closeOrder(order.id),
    trace: () => {
      openDetail(order.id)
      ElMessage.success('物流轨迹已更新至最新节点')
    },
    afterSale: () => {
      openDetail(order.id)
      ElMessage.warning('已进入售后处理流程')
    },
    remind: () => ElMessage.success('已向买家发送付款提醒'),
    remindRecv: () => ElMessage.success(`已向 ${order.buyer.name} 发送收货提醒`),
    urge: () => ElMessage.success('已邀请买家发表评价'),
    again: () => ElMessage.success('已为该买家生成复购推荐')
  }
  handled[key]?.()
}

/* ---------- 顶部操作 ---------- */
function printWaybills() {
  const count = extras.value.stats?.waitShip || 0
  if (!count) {
    ElMessage.warning('暂无待发货订单面单')
    return
  }
  ElMessage.success(`已生成 ${count} 张电子面单，前往打印`)
}
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="Trade · Order Center" title="订单" title-accent="管理">
      <template #desc>
        共 <b>{{ extras.tabs?.all || 0 }}</b> 笔订单 · 数据每 5 分钟同步一次
      </template>
      <template #actions>
        <el-button @click="printWaybills">
          <el-icon><Printer /></el-icon> 打印面单
        </el-button>
        <el-button type="primary" @click="batchShip">
          <el-icon><Van /></el-icon> 批量发货
        </el-button>
      </template>
    </PageHeader>

    <StatStrip :items="statItems" />

    <section class="mz-card">
      <!-- 状态 Tab：计数来自接口，切换即重新查询 -->
      <el-tabs v-model="params.status" class="tabs">
        <el-tab-pane v-for="tab in ORDER_TABS" :key="tab.key" :name="tab.key">
          <template #label>
            <span>{{ tab.label }}</span>
            <span class="tab-count">{{ extras.tabs?.[tab.key] ?? 0 }}</span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <!-- 筛选条 -->
      <div class="mz-toolbar">
        <span class="mz-toolbar__label">下单时间</span>
        <el-select v-model="params.range" style="width: 132px">
          <el-option v-for="o in rangeOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>

        <span class="mz-toolbar__label">支付方式</span>
        <el-select v-model="params.payWay" style="width: 132px">
          <el-option label="全部方式" value="all" />
          <el-option v-for="p in payOptions" :key="p.value" :label="p.label" :value="p.value" />
        </el-select>

        <span class="mz-toolbar__label">金额区间</span>
        <el-select v-model="params.amountRange" style="width: 152px">
          <el-option v-for="o in amountOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>

        <span class="mz-toolbar__label">排序</span>
        <el-select v-model="params.sort" style="width: 168px">
          <el-option v-for="o in sortOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>

        <el-button text type="primary" @click="reset()">重置筛选</el-button>

        <div class="spacer" />
        <span class="result-hint">
          筛选结果 <b>{{ total }}</b> 笔
        </span>
      </div>

      <!-- 批量操作条 -->
      <BulkBar :count="selectedCount" unit="笔" label="订单" @clear="clearAll">
        <button class="bulk-primary" @click="batchShip">批量发货</button>
        <button class="bulk__btn" @click="selectedCount && openNote(selected[0])">添加备注</button>
        <button class="bulk__btn" @click="ElMessage.success(`已导出 ${selectedCount} 笔订单明细`)">
          导出所选
        </button>
      </BulkBar>

      <!-- 订单表格 -->
      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="list"
        row-key="id"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" :selectable="() => true" />
        <el-table-column type="expand" width="34">
          <template #default="{ row }">
            <div class="expand">
              <OrderDetailBlocks :order="row" />
            </div>
          </template>
        </el-table-column>

        <el-table-column label="订单信息" min-width="168">
          <template #default="{ row }">
            <div class="cell-stack">
              <span class="mono order-no">{{ row.id }}</span>
              <span class="sub">{{ fmtDate(row.createdAt) }}</span>
              <span class="sub">{{ row.payWayLabel || row.payWay }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="商品" min-width="216">
          <template #default="{ row }">
            <div class="cell-prod">
              <ProductThumb :thumb="row.product.thumb" :tag="row.product.tag" :size="46" />
              <div class="cell-prod__body">
                <div class="cell-prod__name">{{ row.product.name }}</div>
                <div class="cell-prod__spec">{{ row.product.spec }} · 数量 {{ row.qty }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="实付金额" min-width="116" align="right">
          <template #default="{ row }">
            <div class="cell-amount">
              <span class="num"><small>¥</small>{{ money(row.payAmount) }}</span>
              <span class="sub">共 {{ row.qty }} 件</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="买家" min-width="132">
          <template #default="{ row }">
            <div class="cell-buyer">
              <UserAvatar :name="row.buyer.name" :index="row.buyerIndex" :size="30" />
              <div>
                <b>{{ row.buyer.name }}</b>
                <span class="sub">{{ row.buyer.level }}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="104">
          <template #default="{ row }">
            <StatusTag :tone="pick(ORDER_STATUS, row.status).tone">
              {{ pick(ORDER_STATUS, row.status).text }}
            </StatusTag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="214" align="right">
          <template #default="{ row }">
            <div class="cell-actions">
              <el-button
                v-for="act in actionsOf(row)"
                :key="act.key"
                size="small"
                :type="act.primary ? 'primary' : act.danger ? 'danger' : 'default'"
                :text="act.plain"
                @click="onAction(act.key, row)"
              >
                {{ act.label }}
              </el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <EmptyHint
            icon="Tickets"
            :title="isFiltered ? '没有找到匹配的订单' : '还没有订单'"
            :desc="
              isFiltered ? '试试调整筛选条件，或清空关键词重新搜索' : '等待买家下单后这里会出现记录'
            "
          />
        </template>
      </el-table>

      <div v-if="!isEmpty" class="mz-pager">
        <span class="info"
          >共 <b>{{ total }}</b> 笔订单</span
        >
        <div class="spacer" />
        <el-pagination
          layout="prev, pager, next"
          :current-page="params.page"
          :page-count="pageCount"
          :pager-count="7"
          background
          @current-change="changePage"
        />
      </div>
    </section>

    <!-- ============ 发货弹窗 ============ -->
    <el-dialog
      v-model="shipDialog.visible"
      :title="`批量发货 · ${shipDialog.ids.length} 笔订单`"
      width="480"
    >
      <p class="dialog-desc">填写物流信息后，订单状态将更新为「待收货」</p>
      <el-form label-position="top">
        <el-form-item label="物流公司">
          <el-input v-model="shipDialog.express" readonly />
        </el-form-item>
        <el-form-item label="运单号">
          <el-input v-model="shipDialog.waybill">
            <template #append>
              <el-button @click="shipDialog.waybill = `SF${String(Date.now()).slice(-10)}`">
                重新获取
              </el-button>
            </template>
          </el-input>
          <div class="field-hint">已自动获取电子面单号，支持手动修改</div>
        </el-form-item>
        <el-form-item label="发货备注">
          <el-input
            v-model="shipDialog.note"
            type="textarea"
            :rows="2"
            placeholder="选填，仅商家可见"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shipDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmShip">确认发货</el-button>
      </template>
    </el-dialog>

    <!-- ============ 备注弹窗 ============ -->
    <el-dialog v-model="noteDialog.visible" title="订单备注" width="460">
      <p class="dialog-desc">订单号 {{ noteDialog.id }}</p>
      <el-input
        v-model="noteDialog.note"
        type="textarea"
        :rows="3"
        placeholder="例如：买家要求工作日送达"
      />
      <div class="field-hint">备注仅商家与客服可见，买家不可见</div>
      <template #footer>
        <el-button @click="noteDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmNote">保存备注</el-button>
      </template>
    </el-dialog>

    <!-- ============ 详情抽屉 ============ -->
    <el-drawer v-model="drawer.visible" size="560px" :with-header="false">
      <template v-if="drawer.order">
        <div class="drawer-head">
          <div>
            <span class="drawer-kicker">Order Detail</span>
            <b class="mono">{{ drawer.order.id }}</b>
          </div>
          <el-button circle text @click="drawer.visible = false">
            <el-icon><Close /></el-icon>
          </el-button>
        </div>

        <div class="drawer-body">
          <div class="banner" :class="`tone-${pick(ORDER_STATUS, drawer.order.status).tone}`">
            <el-icon :size="20"><Clock /></el-icon>
            <div>
              <b>{{ pick(ORDER_STATUS, drawer.order.status).text }}</b>
              <span>{{ pick(ORDER_STATUS, drawer.order.status).hint }}</span>
            </div>
          </div>
          <OrderDetailBlocks :order="drawer.order" />
        </div>

        <div class="drawer-foot">
          <el-button @click="openNote(drawer.order.id)">
            {{ drawer.order.merchantNote ? '修改备注' : '添加备注' }}
          </el-button>
          <el-button
            v-if="drawer.order.status === 'wait_ship'"
            type="primary"
            @click="shipFromDrawer(drawer.order)"
          >
            立即发货
          </el-button>
          <el-button
            v-else-if="drawer.order.status === 'wait_pay'"
            type="primary"
            @click="closeFromDrawer(drawer.order)"
          >
            关闭订单
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

.tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.tabs :deep(.el-tabs__content) {
  display: none; /* Tab 只作为筛选开关，内容由下方表格统一渲染 */
}

.result-hint {
  font-size: 12px;
  color: var(--text-3);
}
.result-hint b {
  color: var(--brand);
  font-family: var(--font-mono);
}

/* ---------- 表格单元 ---------- */
.cell-stack {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.order-no {
  color: var(--text);
  font-weight: 600;
}
.sub {
  font-size: 11px;
  color: var(--text-3);
}

.cell-prod {
  display: flex;
  align-items: center;
  gap: 11px;
}
.cell-prod__body {
  min-width: 0;
}
.cell-prod__name {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.cell-prod__spec {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 3px;
}

.cell-amount {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}
.cell-amount .num {
  font-size: 15px;
  font-weight: 600;
}
.cell-amount small {
  font-size: 11px;
  opacity: 0.6;
}

.cell-buyer {
  display: flex;
  align-items: center;
  gap: 9px;
}
.cell-buyer b {
  display: block;
  font-size: 12.5px;
}

.cell-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  flex-wrap: wrap;
}

.expand {
  padding: 4px 18px 18px;
  background: #fdfcfa;
  border-top: 1px dashed var(--line);
  border-bottom: 1px dashed var(--line);
}

/* ---------- 弹窗 / 抽屉 ---------- */
.dialog-desc {
  font-size: 12.5px;
  color: var(--text-3);
  margin-bottom: 14px;
}
.field-hint {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 6px;
  line-height: 1.6;
}

.drawer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 22px 16px;
  border-bottom: 1px solid var(--line);
}
.drawer-head > div {
  min-width: 0;
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
  gap: 18px;
  max-height: calc(100vh - 160px);
  overflow-y: auto;
}

.banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-radius: var(--r-m);
}
.banner b {
  display: block;
  font-size: 14px;
}
.banner span {
  font-size: 11.5px;
  opacity: 0.8;
}
.banner.tone-brand {
  color: var(--brand);
  background: var(--brand-soft);
}
.banner.tone-amber {
  color: var(--amber);
  background: var(--amber-soft);
}
.banner.tone-teal {
  color: var(--teal);
  background: var(--teal-soft);
}
.banner.tone-green {
  color: var(--green);
  background: var(--green-soft);
}
.banner.tone-coral {
  color: var(--coral);
  background: var(--coral-soft);
}
.banner.tone-neutral {
  color: var(--text-2);
  background: #f1eee6;
}

.drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 22px;
  border-top: 1px solid var(--line);
}
/* 抽屉内的信息区块改为单列，宽度有限 */
.drawer-body :deep(.blocks) {
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
}

@media (max-width: 1400px) {
  .mz-toolbar__label {
    display: none;
  }
}
</style>
