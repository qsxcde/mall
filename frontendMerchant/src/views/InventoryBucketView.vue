<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatStrip from '@/components/StatStrip.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyHint from '@/components/EmptyHint.vue'
import { useTableQuery } from '@/composables/useTableQuery'
import { fetchProductPage } from '@/api/catalog'
import {
  allocateBuckets,
  deductBuckets,
  fetchBalance,
  fetchBucketLogs,
  fetchBucketOperations,
  fetchBucketPage,
  fetchBucketReport,
  fetchRuleList,
  mergeBuckets,
  rollbackBuckets,
  saveBucketRule,
  transferBuckets,
  updateRuleStatus
} from '@/api/inventory'
import { BUCKET_BIZ_TYPE, BUCKET_DIMENSION, DEDUCT_POLICY, pick } from '@/utils/dict'
import { fmtDay } from '@/utils/format'

/**
 * 库存分桶。
 *
 * 一个商品同一时刻只使用一种分桶维度；页面顶部固定「商品」上下文，
 * 四个 Tab 共用它：库存桶 / 分桶规则 / 调拨与合并 / 审计日志。
 * 核心不变量 SUM(桶) == 商品总库存，由顶部对账条实时暴露。
 */
const tab = ref('buckets')

/* ==========================================================================
   商品上下文
   ========================================================================== */

const productId = ref(null)
const productOptions = ref([])
const balance = ref(null)
const report = ref(null)

async function loadProducts() {
  const res = await fetchProductPage({ page: 1, size: 100, status: 'all' })
  productOptions.value = (res.list || []).map((p) => ({ id: p.id, name: p.name }))
  if (!productId.value && productOptions.value.length) {
    productId.value = productOptions.value[0].id
  }
}

async function refreshBalance() {
  if (!productId.value) return
  const [b, r] = await Promise.all([
    fetchBalance(productId.value),
    fetchBucketReport(productId.value)
  ])
  balance.value = b
  report.value = r
}

const statItems = computed(() => {
  const b = balance.value
  if (!b) return []
  return [
    {
      key: 'product',
      label: '商品总库存',
      value: b.productStock || 0,
      suffix: '件',
      tone: 'brand',
      desc: 'pms_product.stock · 权威总量'
    },
    {
      key: 'bucket',
      label: '桶余量合计',
      value: b.bucketStockTotal || 0,
      suffix: '件',
      tone: 'teal',
      desc: `共 ${b.bucketCount || 0} 个桶`
    },
    {
      key: 'delta',
      label: '对账差额',
      value: b.delta || 0,
      suffix: '件',
      tone: b.consistent ? 'green' : 'coral',
      alert: !b.consistent,
      desc: b.consistent ? '守恒一致' : '存在差额，需核查'
    }
  ]
})

// 商品切换：三个列表 + 余量 + 规则全部重取（列表由 useTableQuery 的 watchKeys 自动触发）
watch(productId, (id) => {
  bucketParams.productId = id
  logsParams.productId = id
  opsParams.productId = id
  refreshBalance()
  loadRules()
})

/* ==========================================================================
   库存桶 / 调拨单 / 审计日志 三个分页
   ========================================================================== */

const {
  params: bucketParams,
  list: buckets,
  total: bucketTotal,
  loading: bucketLoading,
  pageCount: bucketPages,
  load: loadBuckets,
  changePage: changeBucketPage
} = useTableQuery(fetchBucketPage, {
  defaultParams: { productId: null, dimension: 'all', keyword: '', onlyPositive: true },
  watchKeys: ['productId', 'dimension', 'keyword', 'onlyPositive'],
  size: 10,
  immediate: false
})

const {
  params: opsParams,
  list: ops,
  total: opsTotal,
  loading: opsLoading,
  pageCount: opsPages,
  load: loadOps,
  changePage: changeOpsPage
} = useTableQuery(fetchBucketOperations, {
  defaultParams: { productId: null },
  watchKeys: ['productId'],
  size: 10,
  immediate: false
})

const {
  params: logsParams,
  list: logs,
  total: logsTotal,
  loading: logsLoading,
  pageCount: logsPages,
  load: loadLogs,
  changePage: changeLogsPage
} = useTableQuery(fetchBucketLogs, {
  defaultParams: { productId: null, bizType: 'all' },
  watchKeys: ['productId', 'bizType'],
  size: 10,
  immediate: false
})

/** 可调拨 / 可合并的候选桶（取自余量全景，包含余量为 0 的桶以便把货并进去） */
const bucketOptions = computed(() => balance.value?.buckets || [])

const bucketLabel = (b) => `${pick(BUCKET_DIMENSION, b.dimension).text} · ${b.dimensionValue}（余 ${b.stock}）`

/* ==========================================================================
   分桶规则
   ========================================================================== */

const rules = ref([])

async function loadRules() {
  rules.value = await fetchRuleList({ productId: productId.value })
}

const ruleDialog = reactive({ visible: false, isNew: true, model: emptyRule() })

function emptyRule() {
  return {
    id: null,
    ruleName: '',
    dimension: 'BATCH',
    granularity: 'SKU',
    deductPolicy: 'FIFO',
    productId: null,
    enabled: 1,
    remark: ''
  }
}

function openRule(row) {
  ruleDialog.isNew = !row
  ruleDialog.model = row ? { ...row } : emptyRule()
  ruleDialog.visible = true
}

async function confirmRule() {
  const m = ruleDialog.model
  if (!m.ruleName) {
    ElMessage.warning('请填写规则名称')
    return
  }
  await saveBucketRule({ ...m, productId: m.productId || productId.value })
  ruleDialog.visible = false
  ElMessage.success(ruleDialog.isNew ? '规则已创建' : '规则已更新')
  await loadRules()
}

async function toggleRule(row) {
  await updateRuleStatus(row.id, row.enabled ? 0 : 1)
  ElMessage.success(row.enabled ? '规则已停用' : '规则已启用')
  await loadRules()
}

/* ==========================================================================
   分配库存
   ========================================================================== */

const allocateDialog = reactive({ visible: false, ruleId: null, dimension: 'BATCH', items: [] })

function openAllocate() {
  allocateDialog.ruleId = rules.value.find((r) => r.enabled === 1)?.id ?? null
  allocateDialog.dimension = 'BATCH'
  allocateDialog.items = [emptyAllocateItem()]
  allocateDialog.visible = true
}

function emptyAllocateItem() {
  return { dimensionValue: '', qty: 0, expireDate: null }
}

function addAllocateItem() {
  allocateDialog.items.push(emptyAllocateItem())
}

function removeAllocateItem(index) {
  if (allocateDialog.items.length <= 1) {
    ElMessage.warning('至少保留一条明细')
    return
  }
  allocateDialog.items.splice(index, 1)
}

async function confirmAllocate() {
  const items = allocateDialog.items
    .filter((i) => i.dimensionValue && i.qty > 0)
    .map((i) => ({
      dimensionValue: i.dimensionValue,
      qty: i.qty,
      expireDate: i.expireDate || undefined
    }))
  if (!items.length) {
    ElMessage.warning('请至少填写一条有效明细（维度值 + 数量）')
    return
  }
  const res = await allocateBuckets({
    productId: productId.value,
    ruleId: allocateDialog.ruleId || undefined,
    // 未选规则时用显式维度，避免后端落到默认值
    dimension: allocateDialog.ruleId ? undefined : allocateDialog.dimension,
    items
  })
  allocateDialog.visible = false
  ElMessage.success(`已重新分配，共 ${res.bucketCount} 个桶`)
  await Promise.all([refreshBalance(), loadBuckets()])
}

/* ==========================================================================
   调拨 / 合并
   ========================================================================== */

const transferDialog = reactive({ visible: false, fromBucketId: null, toBucketId: null, qty: 1 })

function openTransfer(row) {
  transferDialog.fromBucketId = row?.id ?? null
  transferDialog.toBucketId = null
  transferDialog.qty = 1
  transferDialog.visible = true
}

async function confirmTransfer() {
  if (!transferDialog.fromBucketId || !transferDialog.toBucketId) {
    ElMessage.warning('请选择来源桶与目标桶')
    return
  }
  const opNo = await transferBuckets({
    fromBucketId: transferDialog.fromBucketId,
    toBucketId: transferDialog.toBucketId,
    qty: transferDialog.qty
  })
  transferDialog.visible = false
  ElMessage.success(`调拨完成，单号 ${opNo}`)
  await Promise.all([refreshBalance(), loadBuckets(), loadOps()])
}

const mergeDialog = reactive({ visible: false, sourceBucketIds: [], targetDimensionValue: '' })

function openMerge(row) {
  mergeDialog.sourceBucketIds = row ? [row.id] : []
  mergeDialog.targetDimensionValue = ''
  mergeDialog.visible = true
}

async function confirmMerge() {
  if (!mergeDialog.sourceBucketIds.length) {
    ElMessage.warning('请选择要合并的桶')
    return
  }
  const opNo = await mergeBuckets({
    productId: productId.value,
    sourceBucketIds: mergeDialog.sourceBucketIds,
    targetDimensionValue: mergeDialog.targetDimensionValue || undefined
  })
  mergeDialog.visible = false
  ElMessage.success(`合并完成，单号 ${opNo}`)
  await Promise.all([refreshBalance(), loadBuckets(), loadOps()])
}

/* ==========================================================================
   出库 / 回滚
   ========================================================================== */

const deductDialog = reactive({ visible: false, qty: 1, policy: 'FIFO', orderNo: '' })

function openDeduct() {
  deductDialog.qty = 1
  deductDialog.policy = rules.value.find((r) => r.enabled === 1)?.deductPolicy || 'FIFO'
  deductDialog.orderNo = ''
  deductDialog.visible = true
}

async function confirmDeduct() {
  const res = await deductBuckets({
    productId: productId.value,
    qty: deductDialog.qty,
    policy: deductDialog.policy,
    orderNo: deductDialog.orderNo || undefined
  })
  deductDialog.visible = false
  ElMessage.success(`已出库 ${res.deductedQty} 件，命中 ${res.details?.length || 0} 个桶`)
  await Promise.all([refreshBalance(), loadBuckets(), loadLogs()])
}

async function rollback(row) {
  try {
    await ElMessageBox.confirm(
      `将把单号 ${row.orderNo} 的出库量还回原桶，且商品总库存同步回补。`,
      '回滚出库',
      { type: 'warning', confirmButtonText: '确认回滚', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await rollbackBuckets(row.orderNo)
  ElMessage.success('已回滚到原桶')
  await Promise.all([refreshBalance(), loadBuckets(), loadLogs()])
}

/* ==========================================================================
   初始化
   ========================================================================== */

onMounted(async () => {
  await loadProducts()
  if (productId.value) {
    // watch 在首次赋值时也会触发（null -> id），此处仅兜底首个商品相同的情况
    await Promise.all([refreshBalance(), loadRules()])
  }
})
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="Inventory · Bucketing" title="库存" title-accent="分桶">
      <template #desc>
        把一份库存按维度拆成多个桶，让出库扣减分散到不同行上并行推进
      </template>
      <template #actions>
        <el-select v-model="productId" filterable placeholder="选择商品" style="width: 240px">
          <el-option v-for="p in productOptions" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-button @click="openAllocate">
          <el-icon><Sort /></el-icon> 分配库存
        </el-button>
        <el-button @click="openMerge()">
          <el-icon><FolderOpened /></el-icon> 合并
        </el-button>
        <el-button @click="openTransfer()">
          <el-icon><Switch /></el-icon> 调拨
        </el-button>
        <el-button type="primary" @click="openDeduct">
          <el-icon><Upload /></el-icon> 出库
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="balance && !balance.consistent"
      type="error"
      :closable="false"
      show-icon
      title="对账不一致：桶合计 ≠ 商品总库存"
      :description="`商品总库存 ${balance.productStock} 件，桶合计 ${balance.bucketStockTotal} 件，差额 ${balance.delta} 件，请核查。`"
    />

    <StatStrip :items="statItems" />

    <section class="mz-card">
      <el-tabs v-model="tab">
        <!-- ============ 库存桶 ============ -->
        <el-tab-pane label="库存桶" name="buckets">
          <div class="mz-toolbar">
            <span class="mz-toolbar__label">维度</span>
            <el-select v-model="bucketParams.dimension" style="width: 130px">
              <el-option label="全部维度" value="all" />
              <el-option v-for="(v, k) in BUCKET_DIMENSION" :key="k" :label="v.text" :value="k" />
            </el-select>

            <span class="mz-toolbar__label">维度值</span>
            <el-input
              v-model="bucketParams.keyword"
              placeholder="搜索仓库 / 批次 / 效期"
              clearable
              style="width: 200px"
            />

            <el-checkbox v-model="bucketParams.onlyPositive">只看有货</el-checkbox>

            <div class="spacer" />
            <span class="result-hint">共 <b>{{ bucketTotal }}</b> 个桶</span>
          </div>

          <el-table v-loading="bucketLoading" :data="buckets" row-key="id">
            <el-table-column label="维度" width="120">
              <template #default="{ row }">
                <StatusTag :tone="pick(BUCKET_DIMENSION, row.dimension).tone">
                  {{ pick(BUCKET_DIMENSION, row.dimension).text }}
                </StatusTag>
              </template>
            </el-table-column>

            <el-table-column label="维度值" min-width="180">
              <template #default="{ row }">
                <div class="cell-main">{{ row.dimensionValue }}</div>
                <div class="sub">
                  <span v-if="row.warehouse">仓 {{ row.warehouse }}</span>
                  <span v-if="row.batchNo"> · 批次 {{ row.batchNo }}</span>
                  <span v-if="row.region"> · {{ row.region }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="效期" width="120">
              <template #default="{ row }">
                <span class="mono">{{ row.expireDate ? fmtDay(row.expireDate) : '—' }}</span>
              </template>
            </el-table-column>

            <el-table-column label="桶余量" width="110" align="right">
              <template #default="{ row }">
                <span class="num stock" :class="{ 'is-out': row.stock === 0 }">{{ row.stock }}</span>
              </template>
            </el-table-column>

            <el-table-column label="累计入桶" width="110" align="right">
              <template #default="{ row }">
                <span class="mono">{{ row.total }}</span>
              </template>
            </el-table-column>

            <el-table-column label="优先级" width="90" align="right">
              <template #default="{ row }">
                <span class="mono">{{ row.priority }}</span>
              </template>
            </el-table-column>

            <el-table-column label="状态" width="96">
              <template #default="{ row }">
                <StatusTag :tone="row.status === 1 ? 'green' : 'neutral'">
                  {{ row.status === 1 ? '正常' : '冻结' }}
                </StatusTag>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="150" align="right">
              <template #default="{ row }">
                <div class="cell-actions">
                  <el-button text type="primary" size="small" @click="openTransfer(row)">调拨</el-button>
                  <el-button text type="primary" size="small" @click="openMerge(row)">合并</el-button>
                </div>
              </template>
            </el-table-column>

            <template #empty>
              <EmptyHint
                icon="Coin"
                title="该商品还没有任何桶"
                desc="点击右上角「分配库存」，把现有库存按仓库 / 批次 / 效期 / 地区拆成多个桶"
              />
            </template>
          </el-table>

          <div v-if="bucketTotal" class="mz-pager">
            <span class="info">共 <b>{{ bucketTotal }}</b> 个桶</span>
            <div class="spacer" />
            <el-pagination
              layout="prev, pager, next"
              :current-page="bucketParams.page"
              :page-count="bucketPages"
              background
              @current-change="changeBucketPage"
            />
          </div>
        </el-tab-pane>

        <!-- ============ 分桶规则 ============ -->
        <el-tab-pane label="分桶规则" name="rules">
          <div class="mz-toolbar">
            <span class="result-hint">规则决定「按什么维度拆桶」与「出库先扣哪个桶」</span>
            <div class="spacer" />
            <el-button type="primary" @click="openRule(null)">
              <el-icon><Plus /></el-icon> 新建规则
            </el-button>
          </div>

          <el-table :data="rules">
            <el-table-column prop="ruleName" label="规则名称" min-width="160" />
            <el-table-column label="维度" width="120">
              <template #default="{ row }">
                <StatusTag :tone="pick(BUCKET_DIMENSION, row.dimension).tone">
                  {{ pick(BUCKET_DIMENSION, row.dimension).text }}
                </StatusTag>
              </template>
            </el-table-column>
            <el-table-column prop="granularity" label="粒度" width="110" />
            <el-table-column label="出库策略" width="130">
              <template #default="{ row }">
                <StatusTag :tone="pick(DEDUCT_POLICY, row.deductPolicy).tone">
                  {{ pick(DEDUCT_POLICY, row.deductPolicy).text }}
                </StatusTag>
              </template>
            </el-table-column>
            <el-table-column label="适用范围" min-width="120">
              <template #default="{ row }">
                <span class="sub">{{ row.productId ? `商品 #${row.productId}` : '全店通用' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="96">
              <template #default="{ row }">
                <StatusTag :tone="row.enabled === 1 ? 'green' : 'neutral'">
                  {{ row.enabled === 1 ? '启用' : '停用' }}
                </StatusTag>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="140" />
            <el-table-column label="操作" width="150" align="right">
              <template #default="{ row }">
                <div class="cell-actions">
                  <el-button text type="primary" size="small" @click="openRule(row)">编辑</el-button>
                  <el-button text size="small" @click="toggleRule(row)">
                    {{ row.enabled === 1 ? '停用' : '启用' }}
                  </el-button>
                </div>
              </template>
            </el-table-column>

            <template #empty>
              <EmptyHint icon="SetUp" title="还没有分桶规则" desc="新建规则后即可按仓库 / 批次 / 效期 / 地区拆分库存" />
            </template>
          </el-table>
        </el-tab-pane>

        <!-- ============ 调拨与合并 ============ -->
        <el-tab-pane label="调拨与合并" name="ops">
          <el-table v-loading="opsLoading" :data="ops" row-key="id">
            <el-table-column prop="opNo" label="单号" min-width="190" />
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <StatusTag :tone="row.opType === 'TRANSFER' ? 'brand' : 'violet'">
                  {{ row.opType === 'TRANSFER' ? '调拨' : '合并' }}
                </StatusTag>
              </template>
            </el-table-column>
            <el-table-column label="来源桶" width="100">
              <template #default="{ row }">
                <span class="mono">{{ row.fromBucketId ? `#${row.fromBucketId}` : '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="目标桶" width="100">
              <template #default="{ row }">
                <span class="mono">{{ row.toBucketId ? `#${row.toBucketId}` : '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="90" align="right">
              <template #default="{ row }">
                <span class="num">{{ row.qty }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="operator" label="操作人" width="120" />
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                <span class="mono">{{ row.createTime?.replace('T', ' ').slice(0, 16) }}</span>
              </template>
            </el-table-column>

            <template #empty>
              <EmptyHint icon="Switch" title="暂无调拨 / 合并记录" desc="桶之间的搬动只在桶内部进行，不影响商品总库存" />
            </template>
          </el-table>

          <div v-if="opsTotal" class="mz-pager">
            <span class="info">共 <b>{{ opsTotal }}</b> 条</span>
            <div class="spacer" />
            <el-pagination
              layout="prev, pager, next"
              :current-page="opsParams.page"
              :page-count="opsPages"
              background
              @current-change="changeOpsPage"
            />
          </div>
        </el-tab-pane>

        <!-- ============ 审计日志 ============ -->
        <el-tab-pane label="审计日志" name="logs">
          <div class="mz-toolbar">
            <span class="mz-toolbar__label">类型</span>
            <el-select v-model="logsParams.bizType" style="width: 150px">
              <el-option label="全部类型" value="all" />
              <el-option
                v-for="(v, k) in BUCKET_BIZ_TYPE"
                :key="k"
                :label="v.text"
                :value="k"
              />
            </el-select>
            <div class="spacer" />
            <span class="result-hint">任何桶余量变化都会留痕</span>
          </div>

          <el-table v-loading="logsLoading" :data="logs" row-key="id">
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                <span class="mono">{{ row.createTime?.replace('T', ' ').slice(0, 19) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <StatusTag :tone="pick(BUCKET_BIZ_TYPE, row.bizType).tone">
                  {{ pick(BUCKET_BIZ_TYPE, row.bizType).text }}
                </StatusTag>
              </template>
            </el-table-column>

            <el-table-column label="桶" width="120">
              <template #default="{ row }">
                <div class="mono">#{{ row.bucketId }}</div>
                <div class="sub">{{ row.bucketKey }}</div>
              </template>
            </el-table-column>

            <el-table-column label="变化量" width="100" align="right">
              <template #default="{ row }">
                <span class="num" :class="row.changeQty >= 0 ? 'is-plus' : 'is-minus'">
                  {{ row.changeQty > 0 ? '+' : '' }}{{ row.changeQty }}
                </span>
              </template>
            </el-table-column>

            <el-table-column label="变更前 → 后" width="130" align="right">
              <template #default="{ row }">
                <span class="mono">{{ row.beforeStock }} → {{ row.afterStock }}</span>
              </template>
            </el-table-column>

            <el-table-column label="关联单号" min-width="180">
              <template #default="{ row }">
                <span class="mono">{{ row.orderNo || '—' }}</span>
              </template>
            </el-table-column>

            <el-table-column prop="operator" label="操作人" width="110" />

            <el-table-column label="操作" width="110" align="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.bizType === 'OUTBOUND' && row.orderNo"
                  text
                  type="primary"
                  size="small"
                  @click="rollback(row)"
                >
                  回滚
                </el-button>
                <span v-else class="sub">—</span>
              </template>
            </el-table-column>

            <template #empty>
              <EmptyHint icon="Document" title="暂无审计流水" desc="分配、出库、调拨、合并、回滚都会写入流水" />
            </template>
          </el-table>

          <div v-if="logsTotal" class="mz-pager">
            <span class="info">共 <b>{{ logsTotal }}</b> 条</span>
            <div class="spacer" />
            <el-pagination
              layout="prev, pager, next"
              :current-page="logsParams.page"
              :page-count="logsPages"
              background
              @current-change="changeLogsPage"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <!-- ============ 分配库存 ============ -->
    <el-dialog v-model="allocateDialog.visible" title="分配库存到各桶" width="640">
      <p class="dialog-desc">
        只把现有库存拆到桶上，<b>不改变商品总库存</b>；未列出的余量会自动落入「未分配」桶。
      </p>

      <el-form label-position="top">
        <div class="form-row">
          <el-form-item label="应用规则（决定维度与出库策略）">
            <el-select v-model="allocateDialog.ruleId" clearable placeholder="不选则用右侧维度" style="width: 100%">
              <el-option
                v-for="r in rules"
                :key="r.id"
                :label="`${r.ruleName} · ${pick(BUCKET_DIMENSION, r.dimension).text}`"
                :value="r.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="分桶维度">
            <el-select v-model="allocateDialog.dimension" :disabled="!!allocateDialog.ruleId" style="width: 100%">
              <el-option v-for="(v, k) in BUCKET_DIMENSION" :key="k" :label="v.text" :value="k" />
            </el-select>
          </el-form-item>
        </div>
      </el-form>

      <div class="alloc">
        <div class="alloc__head">
          <span>维度值</span><span>数量</span><span>效期</span><span />
        </div>
        <div v-for="(item, i) in allocateDialog.items" :key="i" class="alloc__row">
          <el-input v-model="item.dimensionValue" placeholder="如：深圳总仓 / 批次B2026" />
          <el-input-number v-model="item.qty" :min="0" :controls="false" />
          <el-date-picker v-model="item.expireDate" type="date" value-format="YYYY-MM-DD" placeholder="可选" />
          <el-button text @click="removeAllocateItem(i)">
            <el-icon><Close /></el-icon>
          </el-button>
        </div>
        <el-button text type="primary" @click="addAllocateItem">
          <el-icon><Plus /></el-icon> 添加一行
        </el-button>
      </div>

      <template #footer>
        <el-button @click="allocateDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmAllocate">确认分配</el-button>
      </template>
    </el-dialog>

    <!-- ============ 调拨 ============ -->
    <el-dialog v-model="transferDialog.visible" title="跨桶调拨" width="520">
      <p class="dialog-desc">在桶之间搬动库存，商品总库存不变。</p>
      <el-form label-position="top">
        <el-form-item label="来源桶">
          <el-select v-model="transferDialog.fromBucketId" filterable style="width: 100%">
            <el-option v-for="b in bucketOptions" :key="b.id" :label="bucketLabel(b)" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标桶">
          <el-select v-model="transferDialog.toBucketId" filterable style="width: 100%">
            <el-option v-for="b in bucketOptions" :key="b.id" :label="bucketLabel(b)" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="调拨数量">
          <el-input-number v-model="transferDialog.qty" :min="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmTransfer">确认调拨</el-button>
      </template>
    </el-dialog>

    <!-- ============ 合并 ============ -->
    <el-dialog v-model="mergeDialog.visible" title="跨桶合并" width="560">
      <p class="dialog-desc">把多个桶的余量并入一个目标桶，商品总库存不变。</p>
      <el-form label-position="top">
        <el-form-item label="要合并的桶">
          <el-select v-model="mergeDialog.sourceBucketIds" multiple filterable style="width: 100%">
            <el-option v-for="b in bucketOptions" :key="b.id" :label="bucketLabel(b)" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标维度值">
          <el-input
            v-model="mergeDialog.targetDimensionValue"
            placeholder="留空则并入第一个来源桶"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mergeDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmMerge">确认合并</el-button>
      </template>
    </el-dialog>

    <!-- ============ 出库 ============ -->
    <el-dialog v-model="deductDialog.visible" title="分桶出库" width="520">
      <p class="dialog-desc">
        按优先级挑有货的桶逐个扣减，<b>桶空了才换下一桶</b>；同时扣减商品总库存。
      </p>
      <el-form label-position="top">
        <el-form-item label="出库数量">
          <el-input-number v-model="deductDialog.qty" :min="1" />
        </el-form-item>
        <el-form-item label="挑桶优先级">
          <el-select v-model="deductDialog.policy" style="width: 100%">
            <el-option
              v-for="(v, k) in DEDUCT_POLICY"
              :key="k"
              :label="v.text"
              :value="k"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="业务单号（幂等键，可留空）">
          <el-input v-model="deductDialog.orderNo" placeholder="填写后同一单号重复出库不会重复扣减" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deductDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmDeduct">确认出库</el-button>
      </template>
    </el-dialog>

    <!-- ============ 规则编辑 ============ -->
    <el-dialog v-model="ruleDialog.visible" :title="ruleDialog.isNew ? '新建分桶规则' : '编辑分桶规则'" width="560">
      <el-form label-position="top">
        <el-form-item label="规则名称">
          <el-input v-model="ruleDialog.model.ruleName" placeholder="如：按效期分桶" />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="分桶维度">
            <el-select v-model="ruleDialog.model.dimension" style="width: 100%">
              <el-option
                v-for="(v, k) in BUCKET_DIMENSION"
                :key="k"
                :label="v.text"
                :value="k"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="分桶粒度">
            <el-select v-model="ruleDialog.model.granularity" style="width: 100%">
              <el-option label="SKU（商品级）" value="SKU" />
              <el-option label="SKU_BATCH（商品 + 批次）" value="SKU_BATCH" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="出库挑桶优先级">
          <el-select v-model="ruleDialog.model.deductPolicy" style="width: 100%">
            <el-option v-for="(v, k) in DEDUCT_POLICY" :key="k" :label="v.text" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="ruleDialog.model.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmRule">
          {{ ruleDialog.isNew ? '创建规则' : '保存修改' }}
        </el-button>
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
.result-hint {
  font-size: 12px;
  color: var(--text-3);
}
.result-hint b {
  color: var(--brand);
  font-family: var(--font-mono);
}
.cell-main {
  font-size: 13px;
  font-weight: 600;
}
.cell-actions {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
}
.stock {
  font-size: 14px;
  font-weight: 600;
}
.stock.is-out {
  color: var(--coral);
}
.is-plus {
  color: var(--green);
}
.is-minus {
  color: var(--coral);
}
.dialog-desc {
  font-size: 12.5px;
  color: var(--text-3);
  margin-bottom: 14px;
  line-height: 1.7;
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}

/* ---------- 分配明细编辑器 ---------- */
.alloc {
  border: 1px solid var(--line);
  border-radius: var(--r-m);
  padding: 12px;
  background: #fdfcfa;
}
.alloc__head,
.alloc__row {
  display: grid;
  grid-template-columns: 1.6fr 0.9fr 1.1fr 40px;
  gap: 10px;
  align-items: center;
}
.alloc__head {
  font-size: 10.5px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-3);
  font-weight: 800;
  padding-bottom: 8px;
}
.alloc__row {
  margin-bottom: 9px;
}
.alloc__row :deep(.el-input-number),
.alloc__row :deep(.el-date-editor) {
  width: 100%;
}
</style>
