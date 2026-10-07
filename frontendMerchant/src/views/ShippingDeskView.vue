<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatStrip from '@/components/StatStrip.vue'
import BulkBar from '@/components/BulkBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import ProductThumb from '@/components/ProductThumb.vue'
import EmptyHint from '@/components/EmptyHint.vue'
import WaybillCard from '@/components/WaybillCard.vue'
import { useTableQuery } from '@/composables/useTableQuery'
import {
  deliverShipments,
  fetchShipmentFilters,
  fetchShipmentPage,
  hoursLeft,
  printShipments
} from '@/api/trade'
import { useMerchantStore } from '@/stores/merchant'
import { SHIPPING_STATUS, SHIPPING_TABS, pick } from '@/utils/dict'
import { deadlineText, fmtDate, fmtTime, int } from '@/utils/format'

/**
 * 发货中心。
 *
 * 履约被拆成三步：打单（生成运单号）→ 打印（送打印机）→ 发货（回填物流状态）。
 * 「打印」可重复执行，「发货」不可逆，因此二者不合并成一个按钮 ——
 * 这在批量场景下是能避免误操作的关键。
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
  isFiltered,
  load,
  changePage,
  reset,
  clearSelection
} = useTableQuery(fetchShipmentPage, {
  defaultParams: { status: 'all', keyword: '', express: 'all', warehouse: 'all', sort: 'late' },
  watchKeys: ['status', 'keyword', 'express', 'warehouse', 'sort'],
  size: 7
})

watch(
  () => store.keyword,
  (v) => {
    params.keyword = v
  }
)

/* ---------- 打单台配置 ---------- */
const desk = reactive({
  printer: '顺丰云打印 · SP-RT300（在线）',
  template: '标准三联单 100×180mm',
  warehouse: '深圳总仓',
  balance: 186
})
const printerOnline = computed(() => !desk.printer.includes('系统默认'))

/* ---------- 筛选选项 ---------- */
const expressOptions = ref([])
const warehouseOptions = ref([])
fetchShipmentFilters().then((res) => {
  expressOptions.value = res.express
  warehouseOptions.value = res.warehouse
})

const sortOptions = [
  { value: 'late', label: '时效 · 最紧急优先' },
  { value: 'time_asc', label: '付款时间 · 旧→新' },
  { value: 'time_desc', label: '付款时间 · 新→旧' }
]

/* ---------- 统计条 ---------- */
const statItems = computed(() => {
  const s = extras.value.stats || {}
  return [
    {
      key: 'wait',
      label: '待发货',
      value: s.wait || 0,
      suffix: '笔',
      tone: 'coral',
      alert: (s.late || 0) > 0,
      desc: `超时预警 ${s.late || 0} 笔`
    },
    { key: 'printed', label: '已打单待发出', value: s.printed || 0, suffix: '笔', tone: 'brand', desc: '面单已生成待交寄' },
    { key: 'shipped', label: '今日已发', value: s.shipped || 0, suffix: '笔', tone: 'teal', desc: '平均发货时长 4.2h' },
    { key: 'exc', label: '物流异常', value: s.exc || 0, suffix: '笔', tone: 'amber', desc: '需主动联系买家' },
    {
      key: 'rate',
      label: '发货及时率',
      value: (s.onTimeRate || 0) * 100,
      suffix: '%',
      digits: 1,
      tone: 'green',
      desc: `近 7 天 ${int(s.shipped7d || 0)} 单中 ${s.late7d || 0} 单超时`
    }
  ]
})

/* ---------- 可打单的目标集合 ---------- */
const printable = computed(() => {
  const picked = selected.value.length
    ? list.value.filter((s) => selected.value.includes(s.id))
    : list.value.filter((s) => s.status === 'wait')
  return picked.filter((s) => s.status === 'wait' || s.status === 'printed')
})

const sideStats = computed(() => ({
  selected: selectedCount.value,
  // 用接口返回的全局待发货数，而不是当前页的条数 ——
  // 否则翻页时「待打面单」会随页码跳动，与业务含义不符
  todo: extras.value.stats?.wait || 0,
  printed: extras.value.stats?.printedToday || 0
}))

/* ---------- 面单预览 ---------- */
const previewShipment = computed(
  () => list.value.find((s) => selected.value.includes(s.id)) || list.value[0] || null
)

/** 把发货单整理成面单组件需要的字段结构 */
const waybillData = computed(() => {
  const s = previewShipment.value
  if (!s) return null
  return {
    // 承运商在打单时才确定，未打单前如实提示而不是留空
    express: s.express || '待指定',
    waybill: s.waybill || '待获取',
    orderId: s.id,
    buyerName: s.buyer.name,
    phone: s.phone,
    address: `${s.area.province} ${s.area.detail}`,
    productName: s.product.name,
    qty: s.qty,
    warehouse: s.warehouse,
    amount: s.amount
  }
})

/* ---------- 时效展示 ---------- */
function dueOf(s) {
  if (s.status === 'exc') return { text: '异常', note: '需人工介入', urgent: true }
  if (s.status === 'shipped') return { text: '已发出', note: `${fmtTime(s.shippedAt)} 交寄`, done: true }
  if (s.status === 'printed') return { text: '待交寄', note: '面单已就绪' }
  const h = hoursLeft(s)
  const urgent = h <= 6
  return {
    text: deadlineText(h),
    note: urgent ? '超时将影响体验分' : '24h 发货承诺',
    urgent
  }
}

/* ---------- 打单 / 打印 ---------- */
const waybillDialog = reactive({ visible: false, ids: [] })

async function openWaybill(ids) {
  if (!ids.length) {
    ElMessage.warning('请先勾选需要打单的订单')
    return
  }
  await printShipments(ids)
  waybillDialog.ids = ids
  waybillDialog.visible = true
  await Promise.all([load(), store.loadBadges(true)])
}

async function printSelected() {
  if (!printable.value.length) {
    ElMessage.warning('当前没有可打单的订单')
    return
  }
  await printShipments(printable.value.map((s) => s.id))
  ElMessage.success(`已发送 ${printable.value.length} 张面单至打印机`)
  clearSelection()
  await Promise.all([load(), store.loadBadges(true)])
}

async function onRowPrint(s) {
  if (s.status === 'printed') {
    ElMessage.success(`已重新发送面单 ${s.waybill} 至打印机`)
    return
  }
  await openWaybill([s.id])
  ElMessage.success(`已生成面单 ${s.waybill}`)
}

/* ---------- 发货 ---------- */
async function deliver(ids) {
  if (!ids.length) {
    ElMessage.warning('请先勾选需要发货的订单')
    return
  }
  try {
    await ElMessageBox.confirm(
      `已选 ${ids.length} 笔订单将标记为已发出并同步物流信息。`,
      '确认发货',
      { type: 'warning', confirmButtonText: '确认发货', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const affected = await deliverShipments(ids)
  ElMessage.success(`已发货 ${affected} 笔订单`)
  clearSelection()
  await Promise.all([load(), store.loadBadges(true)])
}

/**
 * 拉取「全部待发货」订单。
 *
 * 服务端单页上限为 100，因此这里按 total 翻页取全，
 * 避免直接传 size=500 被参数校验拦下、或只取到第一页而静默漏单。
 */
async function fetchAllWaiting() {
  const size = 100
  const first = await fetchShipmentPage({ status: 'wait', size, page: 1, sort: 'late' })
  const list = [...first.list]
  const pages = Math.ceil((first.total || 0) / size)
  for (let p = 2; p <= pages; p += 1) {
    const next = await fetchShipmentPage({ status: 'wait', size, page: p, sort: 'late' })
    list.push(...next.list)
  }
  return list
}

/**
 * 一键打单发货。
 *
 * 刻意重新拉取「全部待发货」而不是用当前页的 list ——
 * 否则用户在第 1 页点「一键」，第 2 页的订单会被静默漏掉。
 */
async function oneKey() {
  const waits = await fetchAllWaiting()
  if (!waits.length) {
    ElMessage.warning('没有待发货的订单')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将为 ${waits.length} 笔待发货订单自动获取面单、打印并标记发货。`,
      '一键打单发货',
      { type: 'warning', confirmButtonText: '开始处理', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await printShipments(waits.map((s) => s.id))
  const affected = await deliverShipments(waits.map((s) => s.id))
  ElMessage.success(`已完成 ${affected} 笔订单的打单与发货`)
  clearSelection()
  await Promise.all([load(), store.loadBadges(true)])
}

/* ---------- 异常处理 ---------- */
const excDialog = reactive({ visible: false, shipment: null })

function openException(s) {
  excDialog.shipment = s
  excDialog.visible = true
}

function handleException(action) {
  const s = excDialog.shipment
  excDialog.visible = false
  const tips = {
    call: `已向 ${s.buyer.name} 发送确认短信`,
    resend: `已通知 ${s.express} 二次派送`,
    back: '已同意退回，退款将由售后流程处理'
  }
  ElMessage.success(tips[action])
}

/* ---------- 详情 ---------- */
const detailDialog = reactive({ visible: false, shipment: null })

function openDetail(s) {
  detailDialog.shipment = s
  detailDialog.visible = true
}

/* ---------- 承运商切换 ---------- */
async function onExpressChange(s, value) {
  s.express = value
  ElMessage.success(`已将 ${s.id} 的承运商改为 ${value}`)
}

/* ---------- 打印设置 ---------- */
const settingDialog = reactive({ visible: false })
function saveSettings() {
  settingDialog.visible = false
  ElMessage.success('打印设置已保存')
}

async function syncOrders() {
  await load()
  ElMessage.success('已同步最新待发货订单')
}

/* ---------- 打印并发货（面单弹窗） ---------- */
async function confirmWaybill() {
  const ids = waybillDialog.ids
  waybillDialog.visible = false
  ElMessage.success(`已发送 ${ids.length} 张面单至打印机，请在交寄后点击「发货」`)
  clearSelection()
  await load()
}
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="Fulfilment · Shipping Desk" title="发货" title-accent="中心">
      <template #desc>
        待发货 <b>{{ extras.stats?.wait || 0 }}</b> 笔 ·
        超时预警 <b>{{ extras.stats?.late || 0 }}</b> 笔 ·
        今日已发 <b>{{ extras.stats?.shipped || 0 }}</b> 笔
      </template>
      <template #actions>
        <el-button @click="syncOrders">
          <el-icon><Refresh /></el-icon> 同步订单
        </el-button>
        <el-button @click="settingDialog.visible = true">
          <el-icon><Printer /></el-icon> 打印设置
        </el-button>
        <el-button type="primary" @click="oneKey">
          <el-icon><Van /></el-icon> 一键打单发货
        </el-button>
      </template>
    </PageHeader>

    <StatStrip :items="statItems" />

    <div class="desk">
      <!-- ============ 左：订单池 ============ -->
      <section class="mz-card">
        <el-tabs v-model="params.status" class="tabs">
          <el-tab-pane v-for="tab in SHIPPING_TABS" :key="tab.key" :name="tab.key">
            <template #label>
              <span>{{ tab.label }}</span>
              <span class="tab-count" :class="{ 'is-hot': tab.key === 'late' && (extras.tabs?.late || 0) > 0 }">
                {{ extras.tabs?.[tab.key] ?? 0 }}
              </span>
            </template>
          </el-tab-pane>
        </el-tabs>

        <div class="mz-toolbar">
          <span class="mz-toolbar__label">承运商</span>
          <el-select v-model="params.express" style="width: 132px">
            <el-option label="全部承运商" value="all" />
            <el-option v-for="e in expressOptions" :key="e" :label="e" :value="e" />
          </el-select>

          <span class="mz-toolbar__label">仓库</span>
          <el-select v-model="params.warehouse" style="width: 124px">
            <el-option label="全部仓库" value="all" />
            <el-option v-for="w in warehouseOptions" :key="w" :label="w" :value="w" />
          </el-select>

          <span class="mz-toolbar__label">排序</span>
          <el-select v-model="params.sort" style="width: 168px">
            <el-option v-for="o in sortOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>

          <el-button text type="primary" @click="reset()">重置筛选</el-button>

          <div class="spacer" />
          <span class="result-hint">共 <b>{{ total }}</b> 笔</span>
        </div>

        <BulkBar :count="selectedCount" unit="笔" label="订单" @clear="clearSelection">
          <button class="bulk-primary" @click="openWaybill(selected)">批量获取面单</button>
          <button class="bulk__btn" @click="printSelected">批量打印</button>
          <button class="bulk__btn" @click="deliver(selected)">确认发货</button>
        </BulkBar>

        <el-table
          v-loading="loading"
          :data="list"
          row-key="id"
          @selection-change="(rows) => (selected.value = rows.map((r) => r.id))"
        >
          <el-table-column type="selection" width="46" />

          <el-table-column label="订单号 / 付款时间" min-width="164">
            <template #default="{ row }">
              <div class="mono order-no">{{ row.id }}</div>
              <div class="sub">{{ fmtDate(row.paidAt) }} 付款</div>
              <StatusTag class="mt" :tone="pick(SHIPPING_STATUS, row.status).tone" size="small">
                {{ pick(SHIPPING_STATUS, row.status).text }}
              </StatusTag>
            </template>
          </el-table-column>

          <el-table-column label="商品" min-width="228">
            <template #default="{ row }">
              <div class="cell-prod">
                <ProductThumb :thumb="row.product.thumb" :tag="row.product.tag" :size="46" />
                <div class="cell-prod__body">
                  <div class="cell-prod__name">{{ row.product.name }}</div>
                  <div class="sub">
                    {{ row.product.spec }} · ×{{ row.qty }} · {{ row.warehouse }}
                  </div>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="收件人" min-width="150">
            <template #default="{ row }">
              <div class="buyer">
                <b>{{ row.buyer.name }}</b>
                <StatusTag tone="neutral" size="small" :dot="false">{{ row.buyer.level }}</StatusTag>
              </div>
              <div class="sub ellipsis">{{ row.area.province }}</div>
            </template>
          </el-table-column>

          <el-table-column label="承运商 / 运单号" min-width="164">
            <template #default="{ row }">
              <el-select
                :model-value="row.express"
                size="small"
                :disabled="row.status === 'shipped' || row.status === 'exc'"
                @change="(v) => onExpressChange(row, v)"
              >
                <el-option v-for="e in expressOptions" :key="e" :label="e" :value="e" />
              </el-select>
              <div class="sub mono waybill">{{ row.waybill || '未获取面单' }}</div>
            </template>
          </el-table-column>

          <el-table-column label="时效 / 操作" min-width="196" align="right">
            <template #default="{ row }">
              <div class="due" :class="{ 'is-urgent': dueOf(row).urgent, 'is-done': dueOf(row).done }">
                <div class="due__text">{{ dueOf(row).text }}</div>
                <div class="due__note">{{ dueOf(row).note }}</div>
              </div>
              <div class="cell-actions">
                <template v-if="row.status === 'exc'">
                  <el-button size="small" @click="openException(row)">处理异常</el-button>
                  <el-button size="small" text @click="openDetail(row)">详情</el-button>
                </template>
                <template v-else-if="row.status === 'shipped'">
                  <el-button size="small" text @click="openDetail(row)">物流轨迹</el-button>
                </template>
                <template v-else-if="row.status === 'printed'">
                  <el-button size="small" type="primary" @click="deliver([row.id])">发货</el-button>
                  <el-button size="small" text @click="onRowPrint(row)">重打</el-button>
                </template>
                <template v-else>
                  <el-button size="small" type="primary" @click="onRowPrint(row)">打单</el-button>
                  <el-button size="small" text @click="openDetail(row)">详情</el-button>
                </template>
              </div>
            </template>
          </el-table-column>

          <template #empty>
            <EmptyHint
              icon="Van"
              :title="params.status === 'late' ? '没有超时订单，发货节奏很好' : '没有匹配的订单'"
              :desc="params.status === 'late' ? '继续保持 24 小时内发货' : '试试切换状态或清空筛选条件'"
            />
          </template>
        </el-table>

        <div v-if="total" class="mz-pager">
          <span class="info">共 <b>{{ total }}</b> 笔</span>
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

      <!-- ============ 右：打单台 ============ -->
      <aside class="side">
        <section class="mz-card mz-card--flush">
          <div class="mz-card__head">
            <span class="head-icon tone-brand"><el-icon><Printer /></el-icon></span>
            <h3>打单台</h3>
          </div>
          <div class="panel">
            <el-form label-position="top" size="default">
              <el-form-item label="打印机">
                <el-select v-model="desk.printer">
                  <el-option label="顺丰云打印 · SP-RT300（在线）" value="顺丰云打印 · SP-RT300（在线）" />
                  <el-option label="菜鸟电子面单打印机 · 已就绪" value="菜鸟电子面单打印机 · 已就绪" />
                  <el-option label="系统默认打印机" value="系统默认打印机" />
                </el-select>
              </el-form-item>
              <el-form-item label="面单模板">
                <el-select v-model="desk.template">
                  <el-option label="标准三联单 100×180mm" value="标准三联单 100×180mm" />
                  <el-option label="顺丰专用单 100×150mm" value="顺丰专用单 100×150mm" />
                  <el-option label="京东物流单 100×180mm" value="京东物流单 100×180mm" />
                </el-select>
              </el-form-item>
              <el-form-item label="默认仓库">
                <el-select v-model="desk.warehouse">
                  <el-option label="深圳总仓" value="深圳总仓" />
                  <el-option label="杭州仓" value="杭州仓" />
                  <el-option label="北京仓" value="北京仓" />
                </el-select>
              </el-form-item>
            </el-form>

            <div class="kv">
              <span>当前选中</span><b class="num">{{ sideStats.selected }} 笔</b>
            </div>
            <div class="kv">
              <span>待打面单</span><b class="num warn">{{ sideStats.todo }} 张</b>
            </div>
            <div class="kv">
              <span>今日已打</span><b class="num good">{{ int(sideStats.printed) }} 张</b>
            </div>

            <div class="cta">
              <el-button
                type="primary"
                size="large"
                :disabled="!printable.length"
                @click="printSelected"
              >
                <el-icon><Printer /></el-icon> 打印所选面单
              </el-button>
              <el-button size="large" @click="openWaybill(printable.map((s) => s.id))">
                <el-icon><View /></el-icon> 预览面单
              </el-button>
            </div>

            <div class="tip" :class="{ 'is-off': !printerOnline }">
              <el-icon><component :is="printerOnline ? 'CircleCheck' : 'WarningFilled'" /></el-icon>
              <span v-if="printerOnline">
                打印机已连接，电子面单余额 {{ desk.balance }} 张，可正常打单
              </span>
              <span v-else>系统默认打印机可能不支持热敏面单，建议切换为云打印机</span>
            </div>
          </div>
        </section>

        <section class="mz-card mz-card--flush">
          <div class="mz-card__head">
            <span class="head-icon tone-teal"><el-icon><Document /></el-icon></span>
            <h3>面单预览</h3>
            <span class="sub">{{ previewShipment?.id || '—' }}</span>
          </div>
          <div class="panel">
            <WaybillCard v-if="waybillData" :data="waybillData" />
            <el-empty v-else description="暂无可预览的订单" :image-size="80" />
          </div>
        </section>
      </aside>
    </div>

    <!-- ============ 面单弹窗 ============ -->
    <el-dialog v-model="waybillDialog.visible" title="电子面单预览" width="720">
      <p class="dialog-desc">
        共 {{ waybillDialog.ids.length }} 张，预览第 1 张 · {{ waybillDialog.ids[0] }}
      </p>
      <div class="waybill-dialog">
        <WaybillCard v-if="waybillData" :data="waybillData" />
        <div class="waybill-side">
          <div class="kv"><span>承运商</span><b>{{ previewShipment?.express }}</b></div>
          <div class="kv"><span>仓库</span><b>{{ previewShipment?.warehouse }}</b></div>
          <div class="kv"><span>内件数</span><b>{{ previewShipment?.qty }} 件</b></div>
          <div class="kv"><span>面单张数</span><b>{{ waybillDialog.ids.length }} 张</b></div>
          <div class="tip">
            <el-icon><WarningFilled /></el-icon>
            <span>请确认打印机已装纸，面单为热敏纸不可重复打印</span>
          </div>
          <div class="tip is-off">
            <el-icon><InfoFilled /></el-icon>
            <span>打印完成后请交寄，并在列表中点击「发货」回填物流状态</span>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="waybillDialog.visible = false">关闭</el-button>
        <el-button type="primary" @click="confirmWaybill">
          打印 {{ waybillDialog.ids.length }} 张面单
        </el-button>
      </template>
    </el-dialog>

    <!-- ============ 异常处理 ============ -->
    <el-dialog v-model="excDialog.visible" title="处理物流异常" width="520">
      <template v-if="excDialog.shipment">
        <div class="exc-reason">
          <el-icon><WarningFilled /></el-icon>
          异常原因：{{ excDialog.shipment.excReason }}
        </div>
        <div class="exc-info">
          收件人：<b>{{ excDialog.shipment.buyer.name }}</b>　{{ excDialog.shipment.phone }}<br />
          地址：<b>{{ excDialog.shipment.area.province }} {{ excDialog.shipment.area.detail }}</b>
        </div>
        <div class="exc-title">处理方式</div>
        <div class="exc-actions">
          <el-button @click="handleException('call')">联系买家确认新地址或改约派送时间</el-button>
          <el-button @click="handleException('resend')">通知快递二次派送</el-button>
          <el-button @click="handleException('back')">同意退回并发起退款</el-button>
        </div>
      </template>
      <template #footer>
        <el-button @click="excDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- ============ 订单详情 ============ -->
    <el-dialog v-model="detailDialog.visible" title="发货详情" width="500">
      <template v-if="detailDialog.shipment">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="订单号">
            <span class="mono">{{ detailDialog.shipment.id }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="商品">
            {{ detailDialog.shipment.product.name }} × {{ detailDialog.shipment.qty }}
          </el-descriptions-item>
          <el-descriptions-item label="收件人">
            {{ detailDialog.shipment.buyer.name }} · {{ detailDialog.shipment.phone }}
          </el-descriptions-item>
          <el-descriptions-item label="收货地址">
            {{ detailDialog.shipment.area.province }} {{ detailDialog.shipment.area.detail }}
          </el-descriptions-item>
          <el-descriptions-item label="发货仓库">{{ detailDialog.shipment.warehouse }}</el-descriptions-item>
          <el-descriptions-item label="承运商">{{ detailDialog.shipment.express }}</el-descriptions-item>
          <el-descriptions-item label="运单号">
            <span class="mono">{{ detailDialog.shipment.waybill || '未获取' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="付款时间">
            {{ fmtDate(detailDialog.shipment.paidAt) }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <el-button @click="detailDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- ============ 打印设置 ============ -->
    <el-dialog v-model="settingDialog.visible" title="打印设置" width="520">
      <el-form label-position="top">
        <el-form-item label="打印机">
          <el-select v-model="desk.printer">
            <el-option label="顺丰云打印 · SP-RT300（在线）" value="顺丰云打印 · SP-RT300（在线）" />
            <el-option label="菜鸟电子面单打印机 · 已就绪" value="菜鸟电子面单打印机 · 已就绪" />
            <el-option label="系统默认打印机" value="系统默认打印机" />
          </el-select>
        </el-form-item>
        <el-form-item label="面单模板">
          <el-select v-model="desk.template">
            <el-option label="标准三联单 100×180mm" value="标准三联单 100×180mm" />
            <el-option label="顺丰专用单 100×150mm" value="顺丰专用单 100×150mm" />
          </el-select>
        </el-form-item>
        <el-form-item label="打印后自动发货">
          <el-select model-value="关闭（需手动确认发货）">
            <el-option label="关闭（需手动确认发货）" value="关闭（需手动确认发货）" />
            <el-option label="开启（打单即发货）" value="开启（打单即发货）" />
          </el-select>
          <div class="field-hint">开启后打单成功即自动标记发货，适合标准化履约</div>
        </el-form-item>
        <el-form-item label="电子面单充值提醒">
          <el-select model-value="余额低于 200 张时提醒">
            <el-option label="余额低于 200 张时提醒" value="余额低于 200 张时提醒" />
            <el-option label="余额低于 500 张时提醒" value="余额低于 500 张时提醒" />
            <el-option label="不提醒" value="不提醒" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="settingDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveSettings">保存设置</el-button>
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
.desk {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 16px;
  align-items: start;
}
.side {
  position: sticky;
  top: calc(var(--topbar-h) + 16px);
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

/* ---------- 表格单元 ---------- */
.order-no {
  font-weight: 600;
}
.sub {
  font-size: 11px;
  color: var(--text-3);
}
.mt {
  margin-top: 5px;
}
.ellipsis {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.waybill {
  margin-top: 5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
.buyer {
  display: flex;
  align-items: center;
  gap: 7px;
}
.buyer b {
  font-size: 12.5px;
}
.due {
  text-align: right;
}
.due__text {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
}
.due__note {
  font-size: 10.5px;
  color: var(--text-3);
  margin-top: 3px;
}
.due.is-urgent .due__text,
.due.is-urgent .due__note {
  color: var(--coral);
}
.due.is-done .due__text {
  color: var(--green);
}
.cell-actions {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
  margin-top: 6px;
  flex-wrap: wrap;
}

/* ---------- 打单台 ---------- */
.head-icon {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.head-icon.tone-brand {
  background: var(--brand-soft);
  color: var(--brand);
}
.head-icon.tone-teal {
  background: var(--teal-soft);
  color: var(--teal);
}
.panel {
  padding: 14px 18px 18px;
}
.panel :deep(.el-form-item) {
  margin-bottom: 13px;
}
.panel :deep(.el-select) {
  width: 100%;
}
.kv {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 0;
  border-top: 1px dashed var(--line-soft);
  font-size: 12.5px;
}
.kv span {
  color: var(--text-2);
}
.kv b {
  font-size: 15px;
  font-weight: 600;
}
.kv b.warn {
  color: var(--amber);
}
.kv b.good {
  color: var(--green);
}
.cta {
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin-top: 14px;
}
.cta :deep(.el-button) {
  margin-left: 0;
  height: 42px;
}
.tip {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 11.5px;
  line-height: 1.65;
  background: var(--green-soft);
  color: #0b6b3b;
}
.tip.is-off {
  background: var(--amber-soft);
  color: #86540a;
}
.tip :deep(.el-icon) {
  margin-top: 2px;
  flex-shrink: 0;
}

/* ---------- 弹窗 ---------- */
.dialog-desc {
  font-size: 12.5px;
  color: var(--text-3);
  margin-bottom: 14px;
}
.waybill-dialog {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 234px;
  gap: 18px;
  align-items: start;
}
.waybill-side :deep(.kv) {
  font-size: 12.5px;
}
.waybill-side :deep(.kv b) {
  font-size: 13px;
  font-family: var(--font-mono);
}
.field-hint {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 5px;
  line-height: 1.6;
}
.exc-reason {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 13px;
  border-radius: 10px;
  background: var(--coral-soft);
  color: #a3300f;
  font-size: 12.5px;
  margin-bottom: 14px;
}
.exc-info {
  font-size: 12.5px;
  line-height: 2;
  color: var(--text-2);
  margin-bottom: 16px;
}
.exc-title {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--text-2);
  margin-bottom: 9px;
}
.exc-actions {
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.exc-actions :deep(.el-button) {
  margin-left: 0;
  justify-content: flex-start;
  height: 40px;
}

/* ---------- 桌面端响应式 ---------- */
@media (max-width: 1400px) {
  .desk {
    grid-template-columns: minmax(0, 1fr) 292px;
  }
  .mz-toolbar__label {
    display: none;
  }
}
@media (max-width: 1240px) {
  .desk {
    grid-template-columns: minmax(0, 1fr);
  }
  .side {
    position: static;
  }
}
</style>
