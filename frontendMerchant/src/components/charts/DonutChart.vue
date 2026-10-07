<script setup>
import { computed, ref, watch } from 'vue'
import { toneColor } from '@/utils/chart'

/**
 * 环形图。
 *
 * 实现关键：用 circle + stroke-dasharray 切分段，
 * 每段长度 = 周长 × 占比，偏移量 = 前面所有段长度之和（取负）。
 * 分段用「逐段揭示」动画依次画出，阅读顺序与图例一致。
 */
const props = defineProps({
  /** [{ name, value, tone }] */
  items: { type: Array, default: () => [] },
  size: { type: Number, default: 176 },
  thickness: { type: Number, default: 20 },
  /** 圆心的主文案与副文案 */
  centerValue: { type: String, default: '' },
  centerLabel: { type: String, default: '' }
})

const radius = computed(() => (props.size - props.thickness) / 2)
const circumference = computed(() => 2 * Math.PI * radius.value)
const total = computed(() => props.items.reduce((s, it) => s + (Number(it.value) || 0), 0))

/** 依次累加得到每段的 dasharray / dashoffset */
const segments = computed(() => {
  if (!total.value) return []
  let acc = 0
  return props.items.map((it) => {
    const value = Number(it.value) || 0
    const len = (value / total.value) * circumference.value
    const seg = {
      ...it,
      color: toneColor(it.tone),
      percent: value / total.value,
      len,
      // dasharray 用「极小的空隙占位」，避免浮点误差让相邻段之间露出缝隙
      dasharray: `${len} ${circumference.value - len}`,
      dashoffset: -acc
    }
    acc += len
    return seg
  })
})

/* 分段逐段揭示：每段延迟 120ms 出现 */
const revealed = ref(0)
watch(
  () => segments.value.length,
  (n) => {
    revealed.value = 0
    if (!n) return
    const timer = setInterval(() => {
      revealed.value += 1
      if (revealed.value >= n) clearInterval(timer)
    }, 120)
  },
  { immediate: true }
)
</script>

<template>
  <div class="donut" :style="{ width: `${size}px`, height: `${size}px` }">
    <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`">
      <!-- 轨道底环 -->
      <circle
        :cx="size / 2"
        :cy="size / 2"
        :r="radius"
        fill="none"
        stroke="var(--line-soft)"
        :stroke-width="thickness"
      />
      <!-- 数据分段：从 12 点方向起画 -->
      <circle
        v-for="(seg, i) in segments"
        :key="seg.name"
        class="donut__seg"
        :class="{ 'is-on': i < revealed }"
        :cx="size / 2"
        :cy="size / 2"
        :r="radius"
        fill="none"
        :stroke="seg.color"
        :stroke-width="thickness"
        :stroke-dasharray="seg.dasharray"
        :stroke-dashoffset="seg.dashoffset"
        stroke-linecap="butt"
        :transform="`rotate(-90 ${size / 2} ${size / 2})`"
      />
    </svg>
    <div class="donut__center">
      <b class="num">{{ centerValue }}</b>
      <span>{{ centerLabel }}</span>
    </div>
  </div>
</template>

<style scoped>
.donut {
  position: relative;
  flex-shrink: 0;
}
.donut svg {
  display: block;
}
.donut__seg {
  opacity: 0;
  transition: opacity 0.42s ease, stroke-width 0.2s ease;
}
.donut__seg.is-on {
  opacity: 1;
}
.donut__center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.donut__center b {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.1;
}
.donut__center span {
  font-size: 11.5px;
  color: var(--text-3);
  margin-top: 3px;
}
</style>
