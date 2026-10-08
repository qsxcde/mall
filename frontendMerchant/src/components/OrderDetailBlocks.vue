<script setup>
import { computed } from 'vue'
import ProductThumb from './ProductThumb.vue'
import { fmtDate, maskPhone, money } from '@/utils/format'
import { ORDER_STATUS, pick } from '@/utils/dict'

/**
 * 订单详情的五个信息区块。
 * 抽成独立组件是为了让「表格内展开行」与「右侧详情抽屉」复用同一份渲染逻辑，
 * 避免两处各写一遍导致字段口径不一致。
 */
const props = defineProps({
  order: { type: Object, required: true }
})

const status = computed(() => pick(ORDER_STATUS, props.order.status))

/** 按订单状态推导履约进度节点，未发生的节点不显示时间 */
const timeline = computed(() => {
  const o = props.order
  const steps = [
    { title: '提交订单', time: o.createdAt, done: true },
    { title: '买家付款', time: o.paidAt, done: !!o.paidAt },
    { title: '商家发货', time: o.shipAt, done: !!o.shipAt },
    { title: '买家签收', time: o.recvAt, done: !!o.recvAt },
    {
      title: o.status === 'closed' ? '订单关闭' : '交易完成',
      time: o.status === 'done' ? o.recvAt : null,
      done: o.status === 'done' || o.status === 'closed'
    }
  ]
  // 最后一个已完成的节点标记为「当前」，时间轴会高亮它
  let lastDone = -1
  steps.forEach((s, i) => {
    if (s.done) lastDone = i
  })
  return steps.map((s, i) => ({ ...s, current: i === lastDone }))
})
</script>

<template>
  <div class="blocks">
    <!-- 收货信息 -->
    <section class="block">
      <h4>
        <el-icon><Location /></el-icon> 收货信息
      </h4>
      <div class="kv">
        <span>收货人</span>
        <b>{{ order.buyer.name }} · {{ order.buyer.level }}</b>
      </div>
      <div class="kv">
        <span>联系电话</span>
        <b class="mono">{{ maskPhone(order.phone) }}</b>
      </div>
      <div class="kv">
        <span>收货地址</span>
        <b>{{ order.address }}</b>
      </div>
    </section>

    <!-- 物流信息 -->
    <section class="block">
      <h4>
        <el-icon><Van /></el-icon> 物流信息
      </h4>
      <div class="kv">
        <span>支付方式</span>
        <b>{{ order.payWayLabel || order.payWay || '未支付' }}</b>
      </div>
      <div class="kv">
        <span>发货仓库</span>
        <b>{{ order.warehouse || '—' }}</b>
      </div>
      <div class="kv">
        <span>承运商</span>
        <b>{{ order.shipAt ? order.express : '待发货' }}</b>
      </div>
      <div class="kv">
        <span>运单号</span>
        <b class="mono">{{ order.shipAt ? order.waybill : '—' }}</b>
      </div>
      <div class="kv">
        <span>商家备注</span>
        <b>{{ order.merchantNote || '无' }}</b>
      </div>
      <div class="kv">
        <span>买家留言</span>
        <b>{{ order.note || '无' }}</b>
      </div>
    </section>

    <!-- 商品清单 -->
    <section class="block">
      <h4>
        <el-icon><Box /></el-icon> 商品清单
      </h4>
      <div class="prod">
        <ProductThumb
          :thumb="order.product.thumb"
          :tag="order.product.tag"
          :size="52"
          :radius="12"
        />
        <div class="prod__body">
          <b>{{ order.product.name }}</b>
          <span>{{ order.product.spec }}</span>
        </div>
        <div class="prod__qty">×{{ order.qty }}</div>
        <div class="prod__price num">¥{{ money(order.goodsAmount) }}</div>
      </div>
    </section>

    <!-- 金额明细 -->
    <section class="block">
      <h4>
        <el-icon><Wallet /></el-icon> 金额明细
      </h4>
      <div class="money">
        <div class="money__row">
          <span>商品总额</span>
          <b>¥{{ money(order.goodsAmount) }}</b>
        </div>
        <div class="money__row">
          <span>运费</span>
          <b>{{ order.shipFee ? `¥${money(order.shipFee)}` : '包邮' }}</b>
        </div>
        <div class="money__row is-discount">
          <span>优惠抵扣</span>
          <b>-{{ order.discount ? `¥${money(order.discount)}` : '¥0.00' }}</b>
        </div>
        <div class="money__row is-total">
          <span>买家实付</span>
          <b class="num">¥{{ money(order.payAmount) }}</b>
        </div>
      </div>
    </section>

    <!-- 订单进度 -->
    <section class="block">
      <h4>
        <el-icon><Clock /></el-icon> 订单进度
      </h4>
      <el-timeline class="tl">
        <el-timeline-item
          v-for="(s, i) in timeline"
          :key="i"
          :type="s.current ? 'primary' : s.done ? 'success' : 'info'"
          :hollow="!s.done"
          :timestamp="fmtDate(s.time, true)"
          size="normal"
        >
          <span :class="{ 'is-done': s.done }">{{ s.title }}</span>
        </el-timeline-item>
      </el-timeline>
      <div class="hint">{{ status.hint }}</div>
    </section>
  </div>
</template>

<style scoped>
.blocks {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px 30px;
}
.block h4 {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 800;
  color: var(--text-2);
  letter-spacing: 0.04em;
  margin-bottom: 10px;
}
.block h4 :deep(.el-icon) {
  color: var(--brand);
  font-size: 13px;
}

.kv {
  display: flex;
  gap: 10px;
  font-size: 12.5px;
  line-height: 1.9;
}
.kv span {
  color: var(--text-3);
  flex-shrink: 0;
  width: 60px;
}
.kv b {
  color: var(--text);
  font-weight: 600;
  word-break: break-all;
}

.prod {
  display: flex;
  align-items: center;
  gap: 11px;
}
.prod__body {
  flex: 1;
  min-width: 0;
}
.prod__body b {
  display: block;
  font-size: 12.5px;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.prod__body span {
  font-size: 11px;
  color: var(--text-3);
}
.prod__qty {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-2);
}
.prod__price {
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
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
.money__row.is-discount b {
  color: var(--coral);
}
.money__row.is-total {
  border-top: 1px dashed var(--line);
  margin-top: 5px;
  padding-top: 9px;
  font-size: 13.5px;
}
.money__row.is-total b {
  font-size: 16px;
  font-weight: 600;
  color: var(--brand);
}

.tl {
  padding-left: 2px;
}
.tl :deep(.el-timeline-item__content) {
  font-size: 12.5px;
  color: var(--text-3);
}
.tl :deep(.el-timeline-item__content) .is-done {
  color: var(--text);
  font-weight: 600;
}
.tl :deep(.el-timeline-item) {
  padding-bottom: 12px;
}
.hint {
  font-size: 11.5px;
  color: var(--text-3);
  background: #faf8f4;
  border-radius: 9px;
  padding: 8px 11px;
  line-height: 1.6;
}

@media (max-width: 1300px) {
  .blocks {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
