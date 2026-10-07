<script setup>
import { computed, reactive, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatStrip from '@/components/StatStrip.vue'
import BulkBar from '@/components/BulkBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import ProductThumb from '@/components/ProductThumb.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import EmptyHint from '@/components/EmptyHint.vue'
import { useTableQuery } from '@/composables/useTableQuery'
import { fetchAftersalePage, resolveAftersale } from '@/api/trade'
import { useMerchantStore } from '@/stores/merchant'
import { AFTERSALE_STATUS, AFTERSALE_TABS, AFTERSALE_TYPE, pick } from '@/utils/dict'
import { deadlineText, fmtDate, int, money, percent } from '@/utils/format'

/**
 * 售后管理。
 *
 * 这一页的每个动作都涉及退款金额，因此：
 * - 同意弹窗允许改退款额（支持部分退款），并明确「不可撤销」
 * - 拒绝必须选理由，理由会同步给买家，避免出现「无理由拒绝」被平台判罚
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
} = useTableQuery(fetchAftersalePage, {
  defaultParams: { status: 'all', keyword: '', type: 'all', sort: 'apply_desc' },
  watchKeys: ['status', 'keyword', 'type', 'sort'],
  size: 7
})

watch(
  () => store.keyword,
  (v) => {
    params.keyword = v
  }
)

/* ---------- 筛选 ---------- */
const typeOptions = [
  { value: 'all', label: '全部类型' },
  { value: 'refund', label: '仅退款' },
  { value: 'return', label: '退货退款' },
  { value: 'exchange', label: '换货' },
  { value: 'repair', label: '维修' }
]
const sortOptions = [
  { value: 'apply_desc', label: '申请时间 · 新→旧' },
  { value: 'deadline', label: '处理时限 · 最紧急' },
  { value: 'amount_desc', label: '退款金额 · 高→低' },
  { value: 'apply_asc', label: '申请时间 · 旧→新' }
]

/* ---------- 统计条 ---------- */
const statItems = computed(() => {
  const s = extras.value.stats || {}
  return [
    {
      key: 'pending',
      label: '待处理',
      value: s.pending || 0,
      suffix: '笔',
      tone: 'coral',
      alert: (s.pending || 0) > 0,
      desc: '需 48 小时内响应'
    },
    { key: 'waitReturn', label: '待买家退货', value: s.waitReturn || 0, suffix: '笔', tone: 'brand', desc: '已同意，等待寄回' },
    { key: 'waitReceive', label: '待商家收货', value: s.waitReceive || 0, suffix: '笔', tone: 'teal', desc: '已寄回，待确认收货' },
    {
      key: 'done',
      label: '已完成',
      value: s.done || 0,
      suffix: '笔',
      tone: 'green',
      desc: `已退款 ¥${money(s.doneAmount || 0)}`
    },
    {
      key: 'amount',
      label: '累计退款金额',
      value: s.refundTotal || 0,
      prefix: '¥',
      tone: 'amber',
      desc: `售后率 ${percent(s.refundRate || 0, 1)}`
    }
  ]
})

/* ---------- 处理时限 ---------- */
function dueOf(a) {
  if (!a.deadline) return null
  const hours = (new Date(a.deadline) - Date.now()) / 3600000
  return { hours, text: deadlineText(hours), urgent: hours <= 6 }
}

/* ---------- 行操作 ---------- */
const approveDialog = reactive({
  visible: false,
  ids: [],
  type: 'refund',
  refundAmount: 0,
  orderAmount: 0,
  address: '广东省深圳市南山区科苑南路 2588 号 极客数码 退货组（拒收到付）'
})

function openApprove(ids) {
  const targets = list.value.filter((a) => ids.includes(a.id))
  if (!targets.length) {
    ElMessage.warning('请先勾选工单')
    return
  }
  approveDialog.ids = targets.map((a) => a.id)
  approveDialog.type = targets[0].type
  approveDialog.refundAmount = targets[0].refundAmount
  approveDialog.orderAmount = targets[0].orderAmount
  approveDialog.visible = true
}

async function confirmApprove() {
  if (approveDialog.refundAmount <= 0) {
    ElMessage.warning('退款金额需大于 0')
    return
  }
  if (approveDialog.refundAmount > approveDialog.orderAmount) {
    ElMessage.warning('退款金额不能超过订单实付金额')
    return
  }
  await resolveAftersale(approveDialog.ids, 'approve', {
    refundAmount: approveDialog.refundAmount,
    address: approveDialog.address
  })
  approveDialog.visible = false
  ElMessage.success(
    approveDialog.type === 'refund'
      ? `已同意退款 ${approveDialog.ids.length} 笔工单`
      : `已同意 ${approveDialog.ids.length} 笔工单，等待买家寄回`
  )
  clearSelection()
  await Promise.all([load(), store.loadBadges(true)])
}

const rejectDialog = reactive({ visible: false, ids: [], reason: '', detail: '' })

/** 标准拒绝理由：既规范话术，也便于平台侧统计 */
const REJECT_PRESETS = [
  '超过 7 天无理由退货期限',
  '商品已明显使用，影响二次销售',
  '非商品质量问题，为买家主观原因',
  '缺少配件或包装，无法办理退货',
  '定制类商品不支持无理由退货'
]

function openReject(ids) {
  const targets = list.value.filter((a) => ids.includes(a.id))
  if (!targets.length) {
    ElMessage.warning('请先勾选工单')
    return
  }
  rejectDialog.ids = targets.map((a) => a.id)
  rejectDialog.reason = REJECT_PRESETS[0]
  rejectDialog.detail = ''
  rejectDialog.visible = true
}

async function confirmReject() {
  await resolveAftersale(rejectDialog.ids, 'reject', {
    rejectReason: rejectDialog.detail.trim()
      ? `${rejectDialog.reason}；补充说明：${rejectDialog.detail.trim()}`
      : rejectDialog.reason
  })
  rejectDialog.visible = false
  ElMessage.success(`已拒绝 ${rejectDialog.ids.length} 笔工单`)
  clearSelection()
  await Promise.all([load(), store.loadBadges(true)])
}

async function receive(id) {
  await resolveAftersale([id], 'receive')
  ElMessage.success('已确认收货，退款将原路退回买家账户')
  await Promise.all([load(), store.loadBadges(true)])
}

async function reaudit(id) {
  await resolveAftersale([id], 'reaudit')
  ElMessage.success('已重新进入审核流程')
  await Promise.all([load(), store.loadBadges(true)])
}

const drawer = reactive({ visible: false, item: null })

function openDetail(row) {
  drawer.item = row
  drawer.visible = true
}

/* ---------- 批量 ---------- */
async function bulkApprove() {
  if (!selectedCount.value) {
    ElMessage.warning('请先勾选工单')
    return
  }
  try {
    await ElMessageBox.confirm(
      `已选 ${selectedCount.value} 笔工单将按各自申请金额同意退款，操作不可撤销。`,
      '批量同意退款',
      { type: 'warning', confirmButtonText: '确认同意', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await resolveAftersale(selected.value, 'approve')
  ElMessage.success(`已同意 ${selectedCount.value} 笔工单`)
  clearSelection()
  await Promise.all([load(), store.loadBadges(true)])
}
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="Service · After-Sale Center" title="售后" title-accent="管理">
      <template #desc>
        待处理 <b>{{ extras.stats?.pending || 0 }}</b> 笔 ·
        退款处理中 <b>{{ (extras.stats?.waitReturn || 0) + (extras.stats?.waitReceive || 0) }}</b> 笔 ·
        共 <b>{{ extras.tabs?.all || 0 }}</b> 笔工单
      </template>
      <template #actions>
        <el-button @click="ElMessage.success('工单已导出，完成后将通过站内信通知')">
          <el-icon><Download /></el-icon> 导出工单
        </el-button>
        <el-button type="primary" @click="bulkApprove">
          <el-icon><Select /></el-icon> 批量同意
        </el-button>
      </template>
    </PageHeader>

    <StatStrip :items="statItems" />

    <section class="mz-card">
      <el-tabs v-model="params.status" class="tabs">
        <el-tab-pane v-for="tab in AFTERSALE_TABS" :key="tab.key" :name="tab.key">
          <template #label>
            <span>{{ tab.label }}</span>
            <span class="tab-count" :class="{ 'is-hot': tab.key === 'pending' && (extras.tabs?.pending || 0) > 0 }">
              {{ extras.tabs?.[tab.key] ?? 0 }}
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <div class="mz-toolbar">
        <span class="mz-toolbar__label">售后类型</span>
        <el-select v-model="params.type" style="width: 132px">
          <el-option v-for="o in typeOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>

        <span class="mz-toolbar__label">排序</span>
        <el-select v-model="params.sort" style="width: 168px">
          <el-option v-for="o in sortOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>

        <el-button text type="primary" @click="reset()">重置筛选</el-button>

        <div class="spacer" />
        <span class="result-hint">共 <b>{{ total }}</b> 笔工单</span>
      </div>

      <BulkBar :count="selectedCount" unit="笔" label="工单" @clear="clearSelection">
        <button class="bulk-primary" @click="bulkApprove">批量同意退款</button>
        <button class="bulk__btn" @click="openReject(selected)">批量拒绝</button>
      </BulkBar>

      <el-table
        v-loading="loading"
        :data="list"
        row-key="id"
        @selection-change="(rows) => (selected.value = rows.map((r) => r.id))"
      >
        <el-table-column type="selection" width="46" />

        <el-table-column label="工单号 / 申请时间" min-width="158">
          <template #default="{ row }">
            <div class="mono order-no">{{ row.id }}</div>
            <div class="sub">订单 {{ row.orderId }}</div>
            <div class="sub">{{ fmtDate(row.applyAt) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="98">
          <template #default="{ row }">
            <StatusTag :tone="pick(AFTERSALE_TYPE, row.type).tone" :dot="false">
              {{ pick(AFTERSALE_TYPE, row.type).text }}
            </StatusTag>
          </template>
        </el-table-column>

        <el-table-column label="商品" min-width="212">
          <template #default="{ row }">
            <div class="cell-prod">
              <ProductThumb :thumb="row.product.thumb" :tag="row.product.tag" :size="46" />
              <div class="cell-prod__body">
                <div class="cell-prod__name">{{ row.product.name }}</div>
                <div class="sub">×{{ row.qty }} · {{ row.product.spec }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="买家" min-width="116">
          <template #default="{ row }">
            <div class="cell-buyer">
              <UserAvatar :name="row.buyer.name" :index="row.buyerIndex" :size="30" />
              <div>
                <b>{{ row.buyer.name }}</b>
                <div class="sub">{{ row.buyer.level }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="退款金额" min-width="108" align="right">
          <template #default="{ row }">
            <div class="num refund">¥{{ money(row.refundAmount) }}</div>
            <div class="sub">实付 ¥{{ money(row.orderAmount) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="状态 / 时效" min-width="126">
          <template #default="{ row }">
            <StatusTag :tone="pick(AFTERSALE_STATUS, row.status).tone">
              {{ pick(AFTERSALE_STATUS, row.status).text }}
            </StatusTag>
            <div v-if="dueOf(row)" class="due" :class="{ 'is-urgent': dueOf(row).urgent }">
              {{ dueOf(row).text }}
            </div>
            <div v-else class="sub" style="margin-top: 5px">已办结</div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="196" align="right">
          <template #default="{ row }">
            <div class="cell-actions">
              <template v-if="row.status === 'pending'">
                <el-button size="small" type="primary" @click="openApprove([row.id])">同意退款</el-button>
                <el-button size="small" text type="danger" @click="openReject([row.id])">拒绝</el-button>
                <el-button size="small" text @click="openDetail(row)">详情</el-button>
              </template>
              <template v-else-if="row.status === 'wait_return'">
                <el-button size="small" text @click="openDetail(row)">查看进度</el-button>
              </template>
              <template v-else-if="row.status === 'wait_receive'">
                <el-button size="small" type="primary" @click="receive(row.id)">确认收货</el-button>
                <el-button size="small" text @click="openDetail(row)">详情</el-button>
              </template>
              <template v-else-if="row.status === 'rejected'">
                <el-button size="small" @click="reaudit(row.id)">重新审核</el-button>
                <el-button size="small" text @click="openDetail(row)">详情</el-button>
              </template>
              <template v-else>
                <el-button size="small" text @click="openDetail(row)">详情</el-button>
              </template>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <EmptyHint
            icon="RefreshLeft"
            :title="isFiltered ? '没有匹配的售后工单' : '暂无售后工单'"
            :desc="isFiltered ? '试试调整筛选条件或切换状态' : '店铺运转良好，暂无买家发起售后'"
          />
        </template>
      </el-table>

      <div v-if="total" class="mz-pager">
        <span class="info">共 <b>{{ total }}</b> 笔工单</span>
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

    <!-- ============ 同意售后 ============ -->
    <el-dialog
      v-model="approveDialog.visible"
      :title="`同意售后 · ${approveDialog.ids.length} 笔`"
      width="520"
    >
      <div class="warn-box">
        <el-icon><WarningFilled /></el-icon>
        <span>同意后系统将按填写金额退款，操作不可撤销，请核对后再提交。</span>
      </div>
      <el-form label-position="top">
        <el-form-item label="退款金额（元）">
          <el-input-number v-model="approveDialog.refundAmount" :min="1" :max="approveDialog.orderAmount" />
          <div class="field-hint">
            订单实付 ¥{{ money(approveDialog.orderAmount) }}，支持部分退款（如扣除运费或赠品价值）
          </div>
        </el-form-item>
        <el-form-item v-if="approveDialog.type !== 'refund'" label="退货地址">
          <el-input v-model="approveDialog.address" type="textarea" :rows="2" />
          <div class="field-hint">买家寄回时显示该地址，建议注明「拒收到付」</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="approveDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmApprove">确认同意</el-button>
      </template>
    </el-dialog>

    <!-- ============ 拒绝售后 ============ -->
    <el-dialog
      v-model="rejectDialog.visible"
      :title="`拒绝售后 · ${rejectDialog.ids.length} 笔`"
      width="520"
    >
      <el-form label-position="top">
        <el-form-item label="拒绝理由">
          <el-radio-group v-model="rejectDialog.reason" class="reason-group">
            <el-radio v-for="r in REJECT_PRESETS" :key="r" :value="r">{{ r }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="补充说明">
          <el-input
            v-model="rejectDialog.detail"
            type="textarea"
            :rows="2"
            placeholder="选填，将同步展示给买家"
          />
          <div class="field-hint">拒绝理由会同步给买家与平台，建议如实填写以免被判罚</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialog.visible = false">取消</el-button>
        <el-button type="danger" @click="confirmReject">确认拒绝</el-button>
      </template>
    </el-dialog>

    <!-- ============ 工单详情抽屉 ============ -->
    <el-drawer v-model="drawer.visible" size="580px" :with-header="false">
      <template v-if="drawer.item">
        <div class="drawer-head">
          <div>
            <span class="drawer-kicker">After-Sale Detail</span>
            <b class="mono">{{ drawer.item.id }}</b>
          </div>
          <el-button circle text @click="drawer.visible = false">
            <el-icon><Close /></el-icon>
          </el-button>
        </div>

        <div class="drawer-body">
          <div class="banner" :class="`tone-${pick(AFTERSALE_STATUS, drawer.item.status).tone}`">
            <el-icon :size="20"><RefreshLeft /></el-icon>
            <div>
              <b>{{ pick(AFTERSALE_STATUS, drawer.item.status).text }}</b>
              <span>{{ pick(AFTERSALE_STATUS, drawer.item.status).hint }}</span>
            </div>
          </div>

          <section class="block">
            <h4>售后信息</h4>
            <div class="kv"><span>工单号</span><b class="mono">{{ drawer.item.id }}</b></div>
            <div class="kv"><span>订单号</span><b class="mono">{{ drawer.item.orderId }}</b></div>
            <div class="kv"><span>售后类型</span><b>{{ pick(AFTERSALE_TYPE, drawer.item.type).text }}</b></div>
            <div class="kv"><span>申请时间</span><b>{{ fmtDate(drawer.item.applyAt, true) }}</b></div>
            <div class="kv"><span>买家</span><b>{{ drawer.item.buyer.name }} · {{ drawer.item.phone }}</b></div>
            <div class="kv"><span>凭证</span><b>{{ drawer.item.images ? `${drawer.item.images} 张图片` : '无' }}</b></div>
            <div class="kv"><span>申请原因</span><b>{{ drawer.item.reason }}</b></div>
            <div v-if="drawer.item.rejectReason" class="kv">
              <span>拒绝理由</span><b class="coral">{{ drawer.item.rejectReason }}</b>
            </div>
          </section>

          <section class="block">
            <h4>商品与金额</h4>
            <div class="prod">
              <ProductThumb
                :thumb="drawer.item.product.thumb"
                :tag="drawer.item.product.tag"
                :size="52"
                :radius="12"
              />
              <div class="prod__body">
                <b>{{ drawer.item.product.name }}</b>
                <span>{{ drawer.item.product.spec }} · ×{{ drawer.item.qty }}</span>
              </div>
            </div>
            <div class="money">
              <div class="money__row">
                <span>订单实付</span><b>¥{{ money(drawer.item.orderAmount) }}</b>
              </div>
              <div class="money__row">
                <span>退款方式</span><b>原路退回</b>
              </div>
              <div class="money__row is-total">
                <span>申请退款</span><b class="num">¥{{ money(drawer.item.refundAmount) }}</b>
              </div>
            </div>
          </section>

          <section v-if="drawer.item.returnWaybill" class="block">
            <h4>退回物流</h4>
            <div class="kv"><span>承运商</span><b>{{ drawer.item.returnExpress }}</b></div>
            <div class="kv"><span>运单号</span><b class="mono">{{ drawer.item.returnWaybill }}</b></div>
          </section>

          <section class="block">
            <h4>处理进度</h4>
            <el-timeline>
              <el-timeline-item
                v-for="(t, i) in drawer.item.timeline"
                :key="i"
                :type="t.done ? 'primary' : 'info'"
                :hollow="!t.done"
                :timestamp="t.time ? fmtDate(t.time, true) : t.tip || '待处理'"
              >
                <span :class="{ 'is-done': t.done }">{{ t.title }}</span>
              </el-timeline-item>
            </el-timeline>
          </section>
        </div>

        <div class="drawer-foot">
          <template v-if="drawer.item.status === 'pending'">
            <el-button @click="openReject([drawer.item.id])">拒绝</el-button>
            <el-button type="primary" @click="openApprove([drawer.item.id])">同意售后</el-button>
          </template>
          <template v-else-if="drawer.item.status === 'wait_receive'">
            <el-button type="primary" @click="receive(drawer.item.id)">确认收货并退款</el-button>
          </template>
          <template v-else-if="drawer.item.status === 'rejected'">
            <el-button @click="reaudit(drawer.item.id)">重新审核</el-button>
          </template>
          <el-button v-else @click="drawer.visible = false">关闭</el-button>
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

.order-no {
  font-weight: 600;
}
.sub {
  font-size: 11px;
  color: var(--text-3);
}
.refund {
  font-size: 15px;
  font-weight: 600;
  color: var(--coral);
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
.cell-buyer {
  display: flex;
  align-items: center;
  gap: 9px;
}
.cell-buyer b {
  font-size: 12.5px;
}
.due {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
  margin-top: 5px;
}
.due.is-urgent {
  color: var(--coral);
  font-weight: 600;
}
.cell-actions {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
  flex-wrap: wrap;
}

/* ---------- 弹窗 ---------- */
.warn-box {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 11px 13px;
  border-radius: 10px;
  background: var(--amber-soft);
  color: #86540a;
  font-size: 12.5px;
  line-height: 1.6;
  margin-bottom: 16px;
}
.warn-box :deep(.el-icon) {
  margin-top: 2px;
  flex-shrink: 0;
}
.field-hint {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 6px;
  line-height: 1.6;
}
.reason-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}
.reason-group :deep(.el-radio) {
  height: auto;
  margin-right: 0;
  padding: 5px 0;
}

/* ---------- 抽屉 ---------- */
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
  opacity: 0.85;
}
.banner.tone-amber {
  color: var(--amber);
  background: var(--amber-soft);
}
.banner.tone-brand {
  color: var(--brand);
  background: var(--brand-soft);
}
.banner.tone-teal {
  color: var(--teal);
  background: var(--teal-soft);
}
.banner.tone-green {
  color: var(--green);
  background: var(--green-soft);
}
.banner.tone-neutral {
  color: var(--text-2);
  background: #f1eee6;
}
.banner.tone-coral {
  color: var(--coral);
  background: var(--coral-soft);
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
  gap: 10px;
  font-size: 12.5px;
  line-height: 1.95;
}
.kv span {
  color: var(--text-3);
  flex-shrink: 0;
  width: 66px;
}
.kv b {
  font-weight: 600;
  word-break: break-all;
}
.kv b.coral {
  color: var(--coral);
}

.prod {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-bottom: 12px;
}
.prod__body b {
  display: block;
  font-size: 12.5px;
  line-height: 1.45;
}
.prod__body span {
  font-size: 11px;
  color: var(--text-3);
}
.money {
  max-width: 320px;
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
  color: var(--coral);
}

.drawer-body :deep(.el-timeline-item__content) {
  font-size: 12.5px;
  color: var(--text-3);
}
.drawer-body :deep(.el-timeline-item__content) .is-done {
  color: var(--text);
  font-weight: 600;
}
.drawer-body :deep(.el-timeline-item) {
  padding-bottom: 12px;
}

@media (max-width: 1400px) {
  .mz-toolbar__label {
    display: none;
  }
}
</style>
