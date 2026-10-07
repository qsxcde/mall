<script setup>
import { computed, ref } from 'vue'
import { useElementSize } from '@/composables/useElementSize'
import { niceMax, toneColor } from '@/utils/chart'

/**
 * 柱状图（自研 SVG）。
 * 用于账期实结趋势、评价星级分布这类「离散分组」的对比。
 */
const props = defineProps({
  /** [{ label, value, tone? }] */
  items: { type: Array, default: () => [] },
  height: { type: Number, default: 210 },
  /** 未指定 tone 时的默认配色 */
  tone: { type: String, default: 'brand' },
  formatter: { type: Function, default: (v) => String(v) },
  /** 是否画横向网格与 y 轴刻度 */
  axis: { type: Boolean, default: true }
})

const wrapRef = ref(null)
const { width } = useElementSize(wrapRef)

const PAD = computed(() => ({
  top: 18,
  right: 14,
  bottom: props.axis ? 26 : 22,
  left: props.axis ? 52 : 10
}))

const plotW = computed(() => Math.max(0, width.value - PAD.value.left - PAD.value.right))
const plotH = computed(() => Math.max(0, props.height - PAD.value.top - PAD.value.bottom))

const yMax = computed(() => niceMax(Math.max(0, ...props.items.map((it) => it.value)) * 1.06))

const yTicks = computed(() => {
  if (!props.axis) return []
  return Array.from({ length: 5 }, (_, i) => ({
    value: (yMax.value / 4) * i,
    y: PAD.value.top + plotH.value * (1 - i / 4)
  }))
})

const bars = computed(() => {
  const n = props.items.length
  if (!n || !plotW.value) return []
  const slot = plotW.value / n
  const barW = Math.min(46, slot * 0.52)

  return props.items.map((it, i) => {
    const h = yMax.value ? (it.value / yMax.value) * plotH.value : 0
    return {
      ...it,
      x: PAD.value.left + slot * i + (slot - barW) / 2,
      y: PAD.value.top + plotH.value - h,
      w: barW,
      h: Math.max(h, it.value > 0 ? 2 : 0),
      cx: PAD.value.left + slot * i + slot / 2,
      color: toneColor(it.tone || props.tone),
      text: props.formatter(it.value)
    }
  })
})

const hoverIndex = ref(-1)
</script>

<template>
  <div ref="wrapRef" class="bars" :style="{ height: `${height}px` }">
    <svg v-if="width" :width="width" :height="height">
      <!-- 网格与 y 轴刻度 -->
      <template v-for="t in yTicks" :key="`y${t.value}`">
        <line
          :x1="PAD.left"
          :x2="width - PAD.right"
          :y1="t.y"
          :y2="t.y"
          stroke="var(--line-soft)"
          stroke-width="1"
        />
        <text :x="PAD.left - 10" :y="t.y + 4" text-anchor="end" class="bars__axis">
          {{ formatter(t.value) }}
        </text>
      </template>

      <!-- 柱体 -->
      <g v-for="(b, i) in bars" :key="b.label + i">
        <rect
          class="bars__bar"
          :x="b.x"
          :y="b.y"
          :width="b.w"
          :height="b.h"
          rx="5"
          :fill="b.color"
          :style="{ animationDelay: `${i * 70}ms` }"
          :opacity="hoverIndex === -1 || hoverIndex === i ? 1 : 0.45"
          @mouseenter="hoverIndex = i"
          @mouseleave="hoverIndex = -1"
        />
        <text
          v-if="axis"
          :x="b.cx"
          :y="height - 8"
          text-anchor="middle"
          class="bars__axis"
          :class="{ 'is-active': hoverIndex === i }"
        >
          {{ b.label }}
        </text>
        <!-- 悬停时在柱顶显示数值 -->
        <text
          v-if="hoverIndex === i"
          :x="b.cx"
          :y="b.y - 7"
          text-anchor="middle"
          class="bars__value"
        >
          {{ b.text }}
        </text>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.bars {
  position: relative;
  width: 100%;
}
.bars svg {
  display: block;
}
.bars__axis {
  font-family: var(--font-mono);
  font-size: 10px;
  fill: var(--text-3);
  transition: fill 0.18s;
}
.bars__axis.is-active {
  fill: var(--brand);
  font-weight: 600;
}
.bars__value {
  font-family: var(--font-mono);
  font-size: 10.5px;
  font-weight: 600;
  fill: var(--text);
}
/* 从底部生长：transform-box + transform-origin 让 SVG 元素也能按自身底部缩放 */
.bars__bar {
  transform-box: fill-box;
  transform-origin: bottom;
  animation: grow 0.62s cubic-bezier(0.22, 0.8, 0.2, 1) both;
  transition: opacity 0.18s;
  cursor: pointer;
}
@keyframes grow {
  from {
    transform: scaleY(0);
  }
  to {
    transform: scaleY(1);
  }
}
</style>
