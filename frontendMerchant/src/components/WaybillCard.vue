<script setup>
import { computed } from 'vue'

/**
 * 电子面单。
 *
 * 面单是「纸质凭证」的数字化复刻，因此刻意不跟随全站圆角/暖白风格：
 * 保留直角、等宽字体、条纹底纹与二值化条码，让人一眼认出这是面单。
 */
const props = defineProps({
  /** { express, waybill, buyerName, phone, address, productName, productSpec, qty, warehouse, amount } */
  data: { type: Object, required: true }
})

/** 各仓库的寄件地址，与面单上的「寄件」栏对应 */
const WAREHOUSE_ADDR = {
  深圳总仓: '广东省深圳市南山区科苑南路 2588 号 A 座 1F',
  杭州仓: '浙江省杭州市余杭区文一西路 969 号 3 号库',
  北京仓: '北京市大兴区亦庄经济开发区科创十一街 8 号'
}

const expressShort = computed(() =>
  (props.data.express || '').replace('速运', '').replace('快递', '').replace('物流', '')
)

const originAddress = computed(() => WAREHOUSE_ADDR[props.data.warehouse] || WAREHOUSE_ADDR['深圳总仓'])

/**
 * 条码：由运单号做散列后生成的确定性条纹。
 * 不是真实可扫的 Code128，但宽度分布、随机感与真实面单一致，
 * 用于设计稿的视觉还原；接入真实打印时替换为后端返回的条码图。
 */
const barcode = computed(() => {
  const text = props.data.waybill || ''
  let seed = 0
  for (let i = 0; i < text.length; i += 1) {
    seed = (seed * 31 + text.charCodeAt(i)) % 2147483648
  }
  const bars = []
  let x = 0
  for (let k = 0; k < 76; k += 1) {
    seed = (seed * 1103515245 + 12345) % 2147483648
    const w = 1 + (seed % 3)
    if ((seed >> 6) % 2 === 0) bars.push({ x, w })
    x += w + 1
  }
  return { bars, width: x }
})
</script>

<template>
  <div class="waybill">
    <span class="waybill__tag">{{ expressShort }} 电子面单</span>

    <div class="waybill__top">
      <div class="waybill__brand">
        <el-icon :size="16"><Van /></el-icon>
        <b>{{ data.express }}</b>
      </div>
      <div class="waybill__code">
        <b>{{ data.waybill || '未获取' }}</b>
        <span>TRACKING NO.</span>
      </div>
    </div>

    <div class="waybill__sec">
      <div class="waybill__line">
        <em>收件</em>
        <span class="is-big">{{ data.buyerName }}　{{ data.phone }}</span>
      </div>
      <div class="waybill__line">
        <em>地址</em>
        <span>{{ data.address }}</span>
      </div>
    </div>

    <div class="waybill__sec">
      <div class="waybill__line">
        <em>寄件</em>
        <span class="is-big">极客数码旗舰店</span>
      </div>
      <div class="waybill__line">
        <em>地址</em>
        <span>{{ originAddress }}</span>
      </div>
    </div>

    <div class="waybill__sec">
      <div class="waybill__line">
        <em>订单</em>
        <span>{{ data.orderId }}　共 {{ data.qty }} 件</span>
      </div>
      <div class="waybill__line">
        <em>内件</em>
        <span>{{ data.productName }} × {{ data.qty }}</span>
      </div>
      <div class="waybill__line">
        <em>备注</em>
        <span>{{ data.amount > 5000 ? '贵重物品 · 请当面签收' : '易碎 · 轻拿轻放' }}</span>
      </div>
    </div>

    <div class="waybill__barcode">
      <svg :viewBox="`0 0 ${barcode.width} 54`" preserveAspectRatio="none">
        <rect
          v-for="(bar, i) in barcode.bars"
          :key="i"
          :x="bar.x"
          y="0"
          :width="bar.w"
          height="54"
          fill="#0c1322"
        />
      </svg>
      <p>{{ data.waybill }}</p>
    </div>
  </div>
</template>

<style scoped>
.waybill {
  position: relative;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 16px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: #0c1322;
}
/* 走纸纹底纹：让面单有「热敏纸」的质感 */
.waybill::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.35;
  background-image: repeating-linear-gradient(
    0deg,
    transparent 0 7px,
    rgba(12, 19, 34, 0.035) 7px 8px
  );
}
.waybill__tag {
  position: absolute;
  top: 0;
  right: 0;
  padding: 3px 9px;
  border-radius: 0 12px 0 8px;
  background: var(--rail);
  color: #fff;
  font-family: var(--font-ui);
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.waybill__top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding-bottom: 10px;
  border-bottom: 2px solid #0c1322;
  position: relative;
}
.waybill__brand {
  display: flex;
  align-items: center;
  gap: 7px;
}
.waybill__brand b {
  font-family: var(--font-ui);
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.06em;
}
.waybill__code {
  text-align: right;
}
.waybill__code b {
  font-size: 15px;
  letter-spacing: 0.04em;
}
.waybill__code span {
  display: block;
  font-size: 9px;
  color: #6b7484;
  letter-spacing: 0.14em;
}

.waybill__sec {
  padding: 9px 0;
  border-bottom: 1px dashed #c9cfd8;
  position: relative;
}
.waybill__sec:last-child {
  border-bottom: none;
}
.waybill__line {
  display: flex;
  gap: 7px;
  line-height: 1.7;
}
.waybill__line em {
  font-style: normal;
  color: #6b7484;
  flex-shrink: 0;
  width: 38px;
}
.waybill__line span {
  word-break: break-all;
  font-family: var(--font-ui);
  font-size: 11.5px;
}
.waybill__line .is-big {
  font-size: 13px;
  font-weight: 700;
}

.waybill__barcode {
  margin-top: 10px;
  position: relative;
}
.waybill__barcode svg {
  width: 100%;
  height: 54px;
}
.waybill__barcode p {
  text-align: center;
  font-size: 11px;
  letter-spacing: 0.28em;
  margin-top: 4px;
}
</style>
