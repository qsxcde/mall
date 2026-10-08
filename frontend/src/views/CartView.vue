<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCartStore } from '@/stores/cart'
import { fmtMoney } from '@/utils/format'

const router = useRouter()
const cart = useCartStore()
const { items, allChecked, checkedQty, checkedAmount } = storeToRefs(cart)

const tableRef = ref()
/** 默认配送方式「标准配送」的运费为 0（与后端 SHIPPING_FEES.standard 一致） */
const DEFAULT_SHIPPING_FEE = 0

/**
 * 把服务端的勾选状态回填到表格。
 *
 * 关键点：toggleRowSelection 会触发 table 的 selection-change，
 * 若不屏蔽就会把「同步过程中的中间态」写回服务端，
 * 结果反而把未处理的行覆盖成未勾选。因此同步期间置 syncing 标记，
 * 让 onSelectionChange 直接跳过。
 */
let syncing = false
const syncSelection = () => {
  syncing = true
  nextTick(() => {
    items.value.forEach((row) => tableRef.value?.toggleRowSelection(row, row.checked))
    // 等 selection-change 事件派发完再解除，避免同 tick 内被误判为用户操作
    setTimeout(() => {
      syncing = false
    }, 0)
  })
}

// 购物车来自服务端，进入页面时拉一次
onMounted(async () => {
  await cart.load().catch(() => {})
  syncSelection()
})
watch(() => items.value.map((i) => i.id).join(','), syncSelection)

/** 表格多选：把变动同步到服务端（仅响应用户操作） */
const onSelectionChange = (rows) => {
  if (syncing) return
  cart.syncChecked(rows).catch(() => {})
}

const onCheckAll = (val) => {
  cart.toggleAll(val).then(syncSelection).catch(() => {})
}

const removeRow = (row) => {
  ElMessageBox.confirm(`确定从购物车删除「${row.title}」吗？`, '提示', { type: 'warning' })
    .then(async () => {
      await cart.remove(row.id)
      ElMessage.success('已删除')
    })
    .catch(() => {})
}

const clearChecked = () => {
  if (!checkedQty.value) return ElMessage.info('请先选择要删除的商品')
  ElMessageBox.confirm('确定删除选中的商品吗？', '提示', { type: 'warning' })
    .then(async () => {
      await cart.clearChecked()
      ElMessage.success('已删除选中商品')
    })
    .catch(() => {})
}

const checkout = () => {
  if (!items.value.length) return ElMessage.info('购物车是空的')
  if (!checkedQty.value) return ElMessage.info('请先勾选要结算的商品')
  router.push({ name: 'checkout' })
}

/** 优惠券是「领券 → 结算页选择」的模式，没有优惠码输入框，这里引导到领券中心 */
const goCouponCenter = () => router.push({ name: 'coupon' })
</script>

<template>
  <div class="container cart-wrap">
    <div class="cart-main">
      <el-table
        ref="tableRef"
        :data="items"
        class="cart-table"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="48" />

        <el-table-column label="商品" min-width="220">
          <template #default="{ row }">
            <div style="display:flex;gap:14px;align-items:center">
              <div class="c-thumb" :class="row.c">图</div>
              <div>
                <div class="c-name" @click="router.push({ name: 'product', params: { id: row.id } })">{{ row.title }}</div>
                <div class="c-spec">{{ row.spec }}</div>
                <span v-if="row.tag" class="c-tag">{{ row.tag }}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="单价" width="110" align="center" class-name="cart-col-sm-hide">
          <template #default="{ row }">¥{{ fmtMoney(row.price) }}</template>
        </el-table-column>

        <el-table-column label="数量" width="160" align="center">
          <template #default="{ row }">
            <el-input-number
              :model-value="row.qty"
              :min="1"
              :max="99"
              size="small"
              @change="(v) => cart.setQty(row.id, v)"
            />
          </template>
        </el-table-column>

        <el-table-column label="小计" width="130" align="center">
          <template #default="{ row }">
            <b style="color:var(--primary)">¥{{ fmtMoney(row.price * row.qty) }}</b>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="90" align="center">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeRow(row)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty><el-empty description="购物车还是空的，快去逛逛吧" /></template>
      </el-table>

      <div class="cart-foot-bar">
        <el-checkbox :model-value="allChecked" @change="onCheckAll">全选</el-checkbox>
        <a class="clear" @click="clearChecked">删除选中</a>
        <span style="margin-left:auto;font-size:13px;color:var(--text-light)">
          已选 <b style="color:var(--primary)">{{ checkedQty }}</b> 件商品
        </span>
      </div>
    </div>

    <aside class="cart-side">
      <div class="side-title">结算摘要</div>
      <div class="side-row"><span>商品总额</span><b>¥{{ fmtMoney(checkedAmount) }}</b></div>
      <div class="side-row"><span>运费</span><b>¥{{ fmtMoney(DEFAULT_SHIPPING_FEE) }}</b></div>
      <div class="side-row"><span>优惠券</span><b class="side-hint">结算页选择</b></div>
      <a class="side-coupon" @click="goCouponCenter">有优惠券？去领券中心 →</a>
      <div class="side-divider" />
      <div class="side-total">
        <span class="label">预计应付</span>
        <span class="amount"><small>¥</small>{{ fmtMoney(checkedAmount + DEFAULT_SHIPPING_FEE) }}</span>
      </div>
      <div class="side-count">已选 {{ checkedQty }} 件 · 标准配送；优惠券与配送方式在结算页确定</div>
      <button class="checkout-btn" :disabled="!checkedQty" @click="checkout">去结算</button>
    </aside>
  </div>
</template>
