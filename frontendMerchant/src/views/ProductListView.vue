<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatStrip from '@/components/StatStrip.vue'
import BulkBar from '@/components/BulkBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import ProductThumb from '@/components/ProductThumb.vue'
import EmptyHint from '@/components/EmptyHint.vue'
import { useTableQuery } from '@/composables/useTableQuery'
import {
  batchUpdatePrice,
  fetchProductFilters,
  fetchProductPage,
  saveProduct,
  stockState,
  updateProductStatus
} from '@/api/catalog'
import { useMerchantStore } from '@/stores/merchant'
import { PRODUCT_CATS, PRODUCT_STATUS, PRODUCT_TABS, pick } from '@/utils/dict'
import { int, money } from '@/utils/format'

/**
 * 商品管理。
 * 网格视图侧重「一眼看清商品长什么样」，列表视图侧重「批量比价、比库存」，
 * 两种视图共用同一份查询状态，切换时不需要重新请求。
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
  allSelected,
  indeterminate,
  load,
  changePage,
  reset,
  clearSelection,
  toggleSelectAll,
  toggleSelect,
  isSelected
} = useTableQuery(fetchProductPage, {
  defaultParams: { status: 'all', keyword: '', cat: 'all', brand: 'all', stock: 'all', sort: 'update_desc' },
  watchKeys: ['status', 'keyword', 'cat', 'brand', 'stock', 'sort'],
  size: 8
})

watch(
  () => store.keyword,
  (v) => {
    params.keyword = v
  }
)

/** 视图模式：grid | list */
const view = ref('grid')

/* ---------- 筛选选项 ---------- */
const cats = ref(PRODUCT_CATS)
const brands = ref([])
fetchProductFilters().then((res) => {
  cats.value = res.categories
  brands.value = res.brands
})

const stockOptions = [
  { value: 'all', label: '全部库存' },
  { value: 'warn', label: '低于安全线' },
  { value: 'out', label: '已售罄' },
  { value: 'plenty', label: '库存充足' }
]
const sortOptions = [
  { value: 'update_desc', label: '更新时间 · 新→旧' },
  { value: 'sales_desc', label: '销量 · 高→低' },
  { value: 'views_desc', label: '浏览量 · 高→低' },
  { value: 'price_desc', label: '售价 · 高→低' },
  { value: 'price_asc', label: '售价 · 低→高' },
  { value: 'stock_asc', label: '库存 · 少→多' }
]

/* ---------- 统计条 ---------- */
const statItems = computed(() => {
  const tabs = extras.value.tabs || {}
  const stats = extras.value.stats || {}
  return [
    {
      key: 'all',
      label: '商品总数',
      value: tabs.all || 0,
      suffix: '个',
      tone: 'brand',
      desc: `回收站 ${tabs.trash || 0} 个`
    },
    {
      key: 'on',
      label: '在售中',
      value: tabs.on || 0,
      suffix: '个',
      tone: 'green',
      desc: `累计销量 ${int(stats.sales || 0)} 件`
    },
    {
      key: 'warn',
      label: '库存预警',
      value: stats.warn || 0,
      suffix: '个',
      tone: 'amber',
      alert: (stats.warn || 0) > 0,
      desc: '低于安全库存线'
    },
    {
      key: 'sold',
      label: '已售罄',
      value: stats.out || 0,
      suffix: '个',
      tone: 'coral',
      desc: '需尽快补货'
    },
    {
      key: 'ware',
      label: '仓库中',
      value: tabs.ware || 0,
      suffix: '个',
      tone: 'violet',
      desc: `审核中 ${tabs.audit || 0} 个 · 已下架 ${tabs.off || 0} 个`
    }
  ]
})

/* ---------- 行内改库存的视觉提示 ---------- */
const stockBarWidth = (p) => Math.min(100, Math.round((p.stock / (p.safeStock * 3)) * 100))

/* ---------- 选中 ---------- */
function toggleCard(row) {
  toggleSelect(row)
}

/* ---------- 状态操作 ---------- */
async function setStatus(rows, status, tip) {
  if (!rows.length) {
    ElMessage.warning('请先勾选商品')
    return
  }
  await updateProductStatus(rows.map((r) => r.id), status)
  ElMessage.success(tip || `已更新 ${rows.length} 个商品状态`)
  clearSelection()
  await Promise.all([load(), store.loadBadges(true)])
}

async function trashOne(row) {
  try {
    await ElMessageBox.confirm(
      `「${row.name}」将移入回收站，可随时恢复。`,
      '移入回收站',
      { type: 'warning', confirmButtonText: '移入回收站', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await setStatus([row], 'trash', '已移入回收站')
}

async function onToggle(row) {
  const target = row.status === 'on' ? 'off' : 'ware'
  await setStatus([row], target, `「${row.name.slice(0, 10)}…」已${target === 'on' ? '上架' : '下架'}`)
}

/* ---------- 批量改价 ---------- */
const priceDialog = reactive({ visible: false, mode: 'pct', value: -10 })

async function confirmPrice() {
  await batchUpdatePrice(selected.value, priceDialog.mode, priceDialog.value)
  priceDialog.visible = false
  ElMessage.success(`已更新 ${selectedCount.value} 个商品的价格`)
  clearSelection()
  await load()
}

/* ---------- 编辑抽屉 ---------- */
const drawer = reactive({ visible: false, isNew: false, model: null })
const formRef = ref(null)

function emptyProduct() {
  return {
    id: null,
    name: '',
    spec: '',
    cat: PRODUCT_CATS[0],
    brand: '',
    price: 0,
    listPrice: 0,
    cost: 0,
    stock: 0,
    safeStock: 10,
    status: 'ware',
    sales: 0,
    views: 0,
    thumb: 'th1',
    tag: 'NEW',
    skus: [{ spec: '标准版', price: 0, stock: 0, code: '' }]
  }
}

function openCreate() {
  drawer.isNew = true
  drawer.model = emptyProduct()
  drawer.visible = true
}

function openEdit(row) {
  drawer.isNew = false
  // 深拷贝，取消时不影响列表里的原对象
  drawer.model = JSON.parse(JSON.stringify(row))
  drawer.visible = true
}

const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  price: [
    {
      validator: (_r, v, cb) => (Number(v) > 0 ? cb() : cb(new Error('售价需大于 0'))),
      trigger: 'blur'
    }
  ]
}

function addSku() {
  drawer.model.skus.push({
    spec: '',
    price: drawer.model.price,
    stock: 0,
    code: ''
  })
}

function removeSku(index) {
  if (drawer.model.skus.length <= 1) {
    ElMessage.warning('至少保留一个规格')
    return
  }
  drawer.model.skus.splice(index, 1)
}

async function submit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const payload = drawer.model
  await saveProduct(payload)
  drawer.visible = false
  ElMessage.success(drawer.isNew ? `商品「${payload.name.slice(0, 10)}…」已发布` : '商品信息已保存')
  params.page = 1
  await Promise.all([load(), store.loadBadges(true)])
}

/* ---------- 顶部操作 ---------- */
function importProducts() {
  ElMessage.success('请下载模板后上传商品表格')
}

async function syncStock() {
  ElMessage.success('已同步仓库库存数据')
  await load()
}
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="Catalog · Product Center" title="商品" title-accent="管理">
      <template #desc>
        共 <b>{{ extras.tabs?.all || 0 }}</b> 个商品 ·
        在售 <b>{{ extras.tabs?.on || 0 }}</b> ·
        库存预警 <b>{{ extras.stats?.warn || 0 }}</b>
      </template>
      <template #actions>
        <el-button @click="importProducts">
          <el-icon><Upload /></el-icon> 批量导入
        </el-button>
        <el-button @click="syncStock">
          <el-icon><Refresh /></el-icon> 同步库存
        </el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon> 发布新商品
        </el-button>
      </template>
    </PageHeader>

    <StatStrip :items="statItems" />

    <section class="mz-card">
      <el-tabs v-model="params.status" class="tabs">
        <el-tab-pane v-for="tab in PRODUCT_TABS" :key="tab.key" :name="tab.key">
          <template #label>
            <span>{{ tab.label }}</span>
            <span class="tab-count">{{ extras.tabs?.[tab.key] ?? 0 }}</span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <div class="mz-toolbar">
        <span class="mz-toolbar__label">分类</span>
        <el-select v-model="params.cat" style="width: 130px">
          <el-option label="全部分类" value="all" />
          <el-option v-for="c in cats" :key="c" :label="c" :value="c" />
        </el-select>

        <span class="mz-toolbar__label">品牌</span>
        <el-select v-model="params.brand" style="width: 130px">
          <el-option label="全部品牌" value="all" />
          <el-option v-for="b in brands" :key="b" :label="b" :value="b" />
        </el-select>

        <span class="mz-toolbar__label">库存</span>
        <el-select v-model="params.stock" style="width: 132px">
          <el-option v-for="o in stockOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>

        <span class="mz-toolbar__label">排序</span>
        <el-select v-model="params.sort" style="width: 168px">
          <el-option v-for="o in sortOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>

        <el-button text type="primary" @click="reset()">重置筛选</el-button>

        <div class="spacer" />
        <span class="result-hint">共 <b>{{ total }}</b> 个商品</span>

        <el-radio-group v-model="view" size="small">
          <el-radio-button value="grid">
            <el-icon><Grid /></el-icon>
          </el-radio-button>
          <el-radio-button value="list">
            <el-icon><Menu /></el-icon>
          </el-radio-button>
        </el-radio-group>
      </div>

      <BulkBar :count="selectedCount" unit="个" label="商品" @clear="clearSelection">
        <button class="bulk-primary" @click="setStatus(selected, 'on')">批量上架</button>
        <button class="bulk__btn" @click="setStatus(selected, 'off')">批量下架</button>
        <button class="bulk__btn" @click="priceDialog.visible = true">批量改价</button>
        <button class="bulk__btn" @click="setStatus(selected, 'trash')">移入回收站</button>
      </BulkBar>

      <!-- ============ 网格视图 ============ -->
      <div v-if="view === 'grid'" v-loading="loading" class="grid-wrap">
        <div v-if="allSelected && list.length" class="grid-selectall">
          <el-checkbox :model-value="true" @change="clearSelection">已全选当前页 {{ list.length }} 个商品</el-checkbox>
        </div>

        <div v-if="list.length" class="pgrid">
          <article
            v-for="p in list"
            :key="p.id"
            class="pcard"
            :class="{ 'is-selected': isSelected(p) }"
          >
            <div class="pcard__img" :class="p.thumb">
              <span class="pcard__tag">{{ p.tag }}</span>
              <div class="pcard__badges">
                <StatusTag :tone="pick(PRODUCT_STATUS, p.status).tone" size="small">
                  {{ pick(PRODUCT_STATUS, p.status).text }}
                </StatusTag>
                <StatusTag v-if="p.stock === 0 && p.status !== 'trash'" tone="coral" size="small">无货</StatusTag>
                <StatusTag
                  v-else-if="stockState(p) === 'warn'"
                  tone="amber"
                  size="small"
                >
                  库存紧张
                </StatusTag>
                <StatusTag v-else-if="p.sales > 900" tone="brand" size="small">热销</StatusTag>
              </div>
              <!-- 复选框只在 hover / 已选时出现，避免网格视图过于嘈杂 -->
              <el-checkbox
                class="pcard__pick"
                :model-value="isSelected(p)"
                @change="toggleCard(p)"
                @click.stop
              />
            </div>

            <div class="pcard__body">
              <div class="pcard__name">{{ p.name }}</div>
              <div class="pcard__meta">编码 {{ p.code }}</div>
              <div class="pcard__meta">{{ p.cat }} · {{ p.brand }}</div>

              <div class="pcard__row">
                <div>
                  <div class="pcard__price num"><small>¥</small>{{ money(p.price) }}</div>
                  <div class="pcard__list">¥{{ money(p.listPrice) }}</div>
                </div>
                <div class="pcard__metrics">
                  库存 <b>{{ int(p.stock) }}</b><br />
                  销量 <b>{{ int(p.sales) }}</b>
                </div>
              </div>

              <div class="stockbar">
                <i
                  :class="{ 'is-warn': p.stock <= p.safeStock, 'is-out': p.stock === 0 }"
                  :style="{ width: `${stockBarWidth(p)}%` }"
                />
              </div>

              <div class="pcard__acts">
                <el-button size="small" @click="openEdit(p)">
                  {{ p.status === 'trash' ? '查看' : '编辑' }}
                </el-button>
                <el-button
                  v-if="p.status !== 'trash' && p.status !== 'audit'"
                  size="small"
                  @click="onToggle(p)"
                >
                  {{ p.status === 'on' ? '下架' : '上架' }}
                </el-button>
                <el-button v-if="p.status === 'trash'" size="small" @click="setStatus([p], 'ware', '已恢复到仓库中')">
                  恢复
                </el-button>
                <el-button v-else size="small" type="danger" text @click="trashOne(p)">删除</el-button>
              </div>
            </div>
          </article>
        </div>

        <EmptyHint
          v-else-if="!loading"
          icon="Box"
          :title="params.status === 'trash' ? '回收站是空的' : isFiltered ? '没有找到匹配的商品' : '还没有任何商品'"
          :desc="
            params.status === 'trash'
              ? '被删除的商品会暂存在这里，可随时恢复上架'
              : isFiltered
                ? '试试调整筛选条件，或清空关键词重新搜索'
                : '点击右上角「发布新商品」开始上架销售'
          "
        />
      </div>

      <!-- ============ 列表视图 ============ -->
      <el-table
        v-else
        v-loading="loading"
        :data="list"
        row-key="id"
        @selection-change="(rows) => (selected.value = rows.map((r) => r.id))"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column type="expand" width="34">
          <template #default="{ row }">
            <div class="sku-box">
              <div class="sku-head">
                <span>规格</span><span>售价</span><span>库存</span><span>规格编码</span><span>占比</span>
              </div>
              <div v-for="s in row.skus" :key="s.code" class="sku-row">
                <span>{{ s.spec }}</span>
                <span class="mono">¥{{ money(s.price) }}</span>
                <span class="mono">{{ s.stock }}</span>
                <span class="mono">{{ s.code }}</span>
                <span class="mono">{{ row.stock ? Math.round((s.stock / row.stock) * 100) : 0 }}%</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="商品" min-width="260">
          <template #default="{ row }">
            <div class="cell-prod">
              <ProductThumb :thumb="row.thumb" :tag="row.tag" :size="46" />
              <div>
                <div class="cell-prod__name">{{ row.name }}</div>
                <div class="sub">编码 {{ row.code }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="分类" min-width="112">
          <template #default="{ row }">
            <div>{{ row.cat }}</div>
            <div class="sub">{{ row.brand }}</div>
          </template>
        </el-table-column>

        <el-table-column label="售价" min-width="110" align="right">
          <template #default="{ row }">
            <div class="num" style="font-size: 14px; font-weight: 600">¥{{ money(row.price) }}</div>
            <div class="sub" style="text-decoration: line-through">¥{{ money(row.listPrice) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="库存" min-width="118" align="right">
          <template #default="{ row }">
            <div
              class="num"
              :style="{
                fontSize: '14px',
                fontWeight: 600,
                color:
                  row.stock === 0 ? 'var(--coral)' : row.stock <= row.safeStock ? 'var(--amber)' : 'inherit'
              }"
            >
              {{ int(row.stock) }}
            </div>
            <div class="sub">安全 {{ row.safeStock }}</div>
          </template>
        </el-table-column>

        <el-table-column label="销量" min-width="86" align="right">
          <template #default="{ row }">
            <span class="mono">{{ int(row.sales) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="104">
          <template #default="{ row }">
            <StatusTag :tone="pick(PRODUCT_STATUS, row.status).tone">
              {{ pick(PRODUCT_STATUS, row.status).text }}
            </StatusTag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="188" align="right">
          <template #default="{ row }">
            <div class="cell-actions">
              <el-button text type="primary" size="small" @click="openEdit(row)">
                {{ row.status === 'trash' ? '查看' : '编辑' }}
              </el-button>
              <el-button
                v-if="row.status === 'trash'"
                text
                size="small"
                @click="setStatus([row], 'ware', '已恢复到仓库中')"
              >
                恢复
              </el-button>
              <template v-else>
                <el-button
                  v-if="row.status !== 'audit'"
                  text
                  size="small"
                  @click="onToggle(row)"
                >
                  {{ row.status === 'on' ? '下架' : '上架' }}
                </el-button>
                <el-button text type="danger" size="small" @click="trashOne(row)">删除</el-button>
              </template>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <EmptyHint
            icon="Box"
            :title="params.status === 'trash' ? '回收站是空的' : '没有找到匹配的商品'"
            desc="试试调整筛选条件，或清空关键词重新搜索"
          />
        </template>
      </el-table>

      <div v-if="total" class="mz-pager">
        <span class="info">共 <b>{{ total }}</b> 个商品</span>
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

    <!-- ============ 批量改价 ============ -->
    <el-dialog v-model="priceDialog.visible" :title="`批量改价 · ${selectedCount} 个商品`" width="460">
      <p class="dialog-desc">改价将同步更新前台售价，请谨慎操作</p>
      <el-form label-position="top">
        <el-form-item label="调整方式">
          <el-radio-group v-model="priceDialog.mode">
            <el-radio-button value="pct">按比例（%）</el-radio-button>
            <el-radio-button value="fix">按固定金额（元）</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="调整数值">
          <el-input-number v-model="priceDialog.value" :step="priceDialog.mode === 'pct' ? 5 : 50" />
          <div class="field-hint">
            示例：比例 -10 表示降价 10%；固定 -50 表示每件减 50 元
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="priceDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmPrice">确认改价</el-button>
      </template>
    </el-dialog>

    <!-- ============ 发布 / 编辑抽屉 ============ -->
    <el-drawer v-model="drawer.visible" size="600px" :with-header="false" destroy-on-close>
      <template v-if="drawer.model">
        <div class="drawer-head">
          <div>
            <span class="drawer-kicker">{{ drawer.isNew ? 'Create Product' : 'Edit Product' }}</span>
            <b>{{ drawer.isNew ? '发布新商品' : drawer.model.name }}</b>
          </div>
          <el-button circle text @click="drawer.visible = false">
            <el-icon><Close /></el-icon>
          </el-button>
        </div>

        <el-form
          ref="formRef"
          :model="drawer.model"
          :rules="rules"
          label-position="top"
          class="drawer-body"
        >
          <section class="form-sec">
            <h4>基本信息</h4>
            <el-form-item label="商品名称" prop="name">
              <el-input v-model="drawer.model.name" placeholder="请输入商品名称" />
            </el-form-item>
            <el-form-item label="商品副标题">
              <el-input v-model="drawer.model.spec" placeholder="一句话卖点，展示在商品标题下方" />
            </el-form-item>
            <div class="form-row">
              <el-form-item label="商品分类">
                <el-select v-model="drawer.model.cat">
                  <el-option v-for="c in PRODUCT_CATS" :key="c" :label="c" :value="c" />
                </el-select>
              </el-form-item>
              <el-form-item label="品牌">
                <el-input v-model="drawer.model.brand" />
              </el-form-item>
            </div>
          </section>

          <section class="form-sec">
            <h4>价格与库存</h4>
            <div class="form-row form-row--3">
              <el-form-item label="售价" prop="price">
                <el-input-number v-model="drawer.model.price" :min="0" :controls="false" />
              </el-form-item>
              <el-form-item label="划线价">
                <el-input-number v-model="drawer.model.listPrice" :min="0" :controls="false" />
              </el-form-item>
              <el-form-item label="成本价">
                <el-input-number v-model="drawer.model.cost" :min="0" :controls="false" />
              </el-form-item>
            </div>
            <div class="form-row">
              <el-form-item label="总库存">
                <el-input-number v-model="drawer.model.stock" :min="0" />
              </el-form-item>
              <el-form-item label="安全库存">
                <el-input-number v-model="drawer.model.safeStock" :min="0" />
                <div class="field-hint">低于该值时触发库存预警</div>
              </el-form-item>
            </div>
          </section>

          <section class="form-sec">
            <h4>商品图片</h4>
            <div class="imgpick">
              <div class="imgpick__main" :class="drawer.model.thumb">{{ drawer.model.tag }}</div>
              <div class="imgpick__slot" :class="drawer.model.thumb">图 2</div>
              <div class="imgpick__slot" :class="drawer.model.thumb">图 3</div>
              <button class="imgpick__add" type="button" @click="ElMessage.success('已打开本地图片选择器（演示）')">
                <el-icon><Plus /></el-icon>
              </button>
            </div>
            <div class="field-hint">建议 800×800px，支持 JPG / PNG，最多 9 张</div>
          </section>

          <section class="form-sec">
            <h4>规格 SKU</h4>
            <div class="sku-edit">
              <div class="sku-edit__head">
                <span>规格名称</span><span>价格</span><span>库存</span><span />
              </div>
              <div v-for="(s, i) in drawer.model.skus" :key="i" class="sku-edit__row">
                <el-input v-model="s.spec" placeholder="如：黑色 · 512G" />
                <el-input-number v-model="s.price" :min="0" :controls="false" />
                <el-input-number v-model="s.stock" :min="0" :controls="false" />
                <el-button text @click="removeSku(i)">
                  <el-icon><Close /></el-icon>
                </el-button>
              </div>
              <el-button text type="primary" @click="addSku">
                <el-icon><Plus /></el-icon> 添加规格
              </el-button>
            </div>
          </section>

          <section class="form-sec">
            <h4>上架与描述</h4>
            <el-form-item label="上架状态">
              <el-switch
                v-model="drawer.model.status"
                active-value="on"
                inactive-value="ware"
                active-text="立即上架销售"
                inactive-text="保存到仓库中"
                inline-prompt
              />
            </el-form-item>
            <el-form-item label="商品描述">
              <el-input
                type="textarea"
                :rows="3"
                placeholder="介绍商品的核心卖点、参数与售后服务"
              />
            </el-form-item>
          </section>
        </el-form>

        <div class="drawer-foot">
          <el-button @click="drawer.visible = false">取消</el-button>
          <el-button type="primary" @click="submit">
            {{ drawer.isNew ? '发布商品' : '保存修改' }}
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

/* ============ 网格视图 ============ */
.grid-wrap {
  min-height: 320px;
}
.grid-selectall {
  padding: 10px 18px;
  background: #f5f9ff;
  border-bottom: 1px solid var(--line);
}
.pgrid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  padding: 18px;
}
.pcard {
  border: 1px solid var(--line);
  border-radius: var(--r-m);
  overflow: hidden;
  background: #fff;
  transition: transform 0.22s cubic-bezier(0.2, 0.8, 0.2, 1), box-shadow 0.22s, border-color 0.18s;
}
.pcard:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-m);
}
.pcard.is-selected {
  border-color: var(--brand);
  box-shadow: 0 0 0 3px rgba(26, 109, 255, 0.12);
}
.pcard__img {
  position: relative;
  height: 132px;
  display: grid;
  place-items: center;
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 600;
  color: rgba(23, 31, 46, 0.5);
}
.pcard__tag {
  letter-spacing: 0.04em;
}
.pcard__badges {
  position: absolute;
  top: 9px;
  left: 9px;
  display: flex;
  gap: 5px;
}
.pcard__pick {
  position: absolute;
  top: 9px;
  right: 9px;
  opacity: 0;
  transition: opacity 0.18s;
}
.pcard:hover .pcard__pick,
.pcard.is-selected .pcard__pick {
  opacity: 1;
}

.pcard__body {
  padding: 13px 14px 14px;
}
.pcard__name {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.45;
  height: 38px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.pcard__meta {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 5px;
}
.pcard__row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-top: 11px;
}
.pcard__price {
  font-size: 18px;
  font-weight: 600;
  color: var(--brand);
}
.pcard__price small {
  font-size: 11px;
}
.pcard__list {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
  text-decoration: line-through;
  margin-top: 3px;
}
.pcard__metrics {
  font-size: 11px;
  color: var(--text-3);
  line-height: 1.7;
  text-align: right;
}
.pcard__metrics b {
  font-family: var(--font-mono);
  color: var(--text-2);
}
.stockbar {
  height: 4px;
  border-radius: 3px;
  background: #f1eee6;
  margin-top: 11px;
  overflow: hidden;
}
.stockbar i {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(90deg, #8dbbff, var(--brand));
  transition: width 0.7s cubic-bezier(0.22, 0.8, 0.2, 1);
}
.stockbar i.is-warn {
  background: linear-gradient(90deg, #ffd08a, var(--amber));
}
.stockbar i.is-out {
  background: var(--coral);
}
.pcard__acts {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 12px;
}

/* ============ 表格单元 ============ */
.cell-prod {
  display: flex;
  align-items: center;
  gap: 11px;
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
.sub {
  font-size: 11px;
  color: var(--text-3);
}
.cell-actions {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
  flex-wrap: wrap;
}

/* ============ SKU 展开 ============ */
.sku-box {
  padding: 6px 18px 16px;
  background: #fdfcfa;
}
.sku-head,
.sku-row {
  display: grid;
  grid-template-columns: 1.6fr 1fr 1fr 1.4fr 0.8fr;
  gap: 12px;
  padding: 8px 0;
  font-size: 12.5px;
}
.sku-head {
  color: var(--text-3);
  font-size: 10.5px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  font-weight: 800;
  border-bottom: 1px solid var(--line);
}
.sku-row {
  border-bottom: 1px dashed var(--line-soft);
}
.sku-row:last-child {
  border-bottom: none;
}
.sku-row .mono {
  color: var(--text-2);
}

/* ============ 抽屉 ============ */
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
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 420px;
}
.drawer-head .el-button {
  margin-left: auto;
}
.drawer-body {
  padding: 18px 22px;
  max-height: calc(100vh - 170px);
  overflow-y: auto;
}
.form-sec {
  margin-bottom: 22px;
}
.form-sec h4 {
  font-size: 12px;
  font-weight: 800;
  color: var(--text-2);
  letter-spacing: 0.04em;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--line);
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}
.form-row--3 {
  grid-template-columns: repeat(3, 1fr);
}
.form-sec :deep(.el-form-item) {
  margin-bottom: 14px;
}
.form-sec :deep(.el-select),
.form-sec :deep(.el-input-number) {
  width: 100%;
}
.field-hint {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 5px;
  line-height: 1.6;
}
.dialog-desc {
  font-size: 12.5px;
  color: var(--text-3);
  margin-bottom: 14px;
}

.imgpick {
  display: flex;
  gap: 10px;
}
.imgpick__main,
.imgpick__slot,
.imgpick__add {
  width: 78px;
  height: 78px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-family: var(--font-mono);
  font-size: 11px;
  color: rgba(23, 31, 46, 0.5);
}
.imgpick__main {
  position: relative;
  border: 1px solid var(--line);
}
.imgpick__main::after {
  content: "主图";
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  font-size: 9.5px;
  text-align: center;
  background: rgba(12, 19, 34, 0.72);
  color: #fff;
  padding: 2px 0;
  border-radius: 0 0 12px 12px;
}
.imgpick__slot {
  opacity: 0.7;
}
.imgpick__add {
  border: 1px dashed var(--line);
  background: #faf8f4;
  color: var(--text-3);
  cursor: pointer;
  font-size: 18px;
}

.sku-edit__head,
.sku-edit__row {
  display: grid;
  grid-template-columns: 1.8fr 1fr 1fr 40px;
  gap: 10px;
  align-items: center;
}
.sku-edit__head {
  font-size: 10.5px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-3);
  font-weight: 800;
  padding-bottom: 7px;
}
.sku-edit__row {
  margin-bottom: 9px;
}
.sku-edit__row :deep(.el-input-number) {
  width: 100%;
}

/* ============ 桌面端响应式 ============ */
@media (max-width: 1560px) {
  .pgrid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 1360px) {
  .mz-toolbar__label {
    display: none;
  }
  .pgrid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
