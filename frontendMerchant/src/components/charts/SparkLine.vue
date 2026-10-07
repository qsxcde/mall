<script setup>
import { computed } from 'vue'
import { toPath, toneColor } from '@/utils/chart'

/**
 * 迷你走势线（指标卡右下角那种）。
 * 不响应鼠标、不带坐标轴，只表达「趋势方向」。
 */
const props = defineProps({
  values: { type: Array, default: () => [] },
  tone: { type: String, default: 'brand' },
  width: { type: Number, default: 116 },
  height: { type: Number, default: 34 }
})

const gradId = computed(() => `spark-${props.tone}-${Math.random().toString(36).slice(2, 8)}`)

const geometry = computed(() => {
  const vals = props.values
  if (vals.length < 2) return null

  const min = Math.min(...vals)
  const max = Math.max(...vals)
  const span = max - min || 1
  const pad = 3
  const innerH = props.height - pad * 2
  const stepX = props.width / (vals.length - 1)

  const points = vals.map((v, i) => ({
    x: i * stepX,
    // 用 1 - (v-min)/span 把数学坐标翻成屏幕坐标（y 向下）
    y: pad + (1 - (v - min) / span) * innerH
  }))

  return {
    line: toPath(points),
    area: toPath(points, true, props.height),
    last: points[points.length - 1],
    color: toneColor(props.tone)
  }
})
</script>

<template>
  <svg v-if="geometry" class="spark" :width="width" :height="height" role="img" aria-label="走势">
    <defs>
      <linearGradient :id="gradId" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" :stop-color="geometry.color" stop-opacity="0.24" />
        <stop offset="100%" :stop-color="geometry.color" stop-opacity="0" />
      </linearGradient>
    </defs>
    <path :d="geometry.area" :fill="`url(#${gradId})`" />
    <path
      :d="geometry.line"
      fill="none"
      :stroke="geometry.color"
      stroke-width="1.8"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
    <circle :cx="geometry.last.x" :cy="geometry.last.y" r="2.6" :fill="geometry.color" />
  </svg>
</template>

<style scoped>
.spark {
  display: block;
  overflow: visible;
}
</style>
