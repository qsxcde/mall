<script setup>
import { computed, ref } from 'vue'
import { useElementSize } from '@/composables/useElementSize'
import { niceMax, toPath } from '@/utils/chart'
import { moneyShort } from '@/utils/format'

/**
 * 面积 + 折线趋势图（自研 SVG）。
 *
 * 能力：
 * - 多条序列，支持「本期实线 + 上期虚线」对比
 * - 悬停十字准星 + 跟随气泡，显示该点各序列的值
 * - 数据点贴近顶部时气泡自动翻转到下方，避免遮挡图例
 * - pathLength=1 + dashoffset 的描线入场动画（与路径真实长度无关）
 */
const props = defineProps({
  labels: { type: Array, default: () => [] },
  /** [{ name, values, color, dashed?, area? }] */
  series: { type: Array, default: () => [] },
  height: { type: Number, default: 260 },
  /** 数值格式化，默认按「万元」简写 */
  formatter: { type: Function, default: null }
})

const wrapRef = ref(null)
const { width } = useElementSize(wrapRef)

const PAD = { top: 20, right: 18, bottom: 28, left: 54 }

const plotW = computed(() => Math.max(0, width.value - PAD.left - PAD.right))
const plotH = computed(() => Math.max(0, props.height - PAD.top - PAD.bottom))

const yMax = computed(() => {
  const all = props.series.flatMap((s) => s.values)
  return niceMax(Math.max(0, ...all) * 1.08)
})

/** y 轴 4 段刻度 */
const yTicks = computed(() =>
  Array.from({ length: 5 }, (_, i) => {
    const value = (yMax.value / 4) * i
    return {
      value,
      y: PAD.top + plotH.value * (1 - i / 4),
      text: format(value)
    }
  })
)

const xFor = (i) => {
  const n = props.labels.length
  if (n <= 1) return PAD.left + plotW.value / 2
  return PAD.left + (plotW.value * i) / (n - 1)
}
const yFor = (v) => PAD.top + plotH.value * (1 - v / yMax.value)

/** x 轴标签抽稀：最多 7 个，避免拥挤 */
const xTicks = computed(() => {
  const n = props.labels.length
  if (!n) return []
  const step = Math.max(1, Math.ceil(n / 7))
  return props.labels
    .map((label, i) => ({ label, i, x: xFor(i) }))
    .filter(({ i }) => i % step === 0 || i === n - 1)
})

const geometry = computed(() => {
  if (!width.value || props.labels.length < 2) return []
  const baseY = PAD.top + plotH.value
  return props.series.map((s) => {
    const pts = s.values.map((v, i) => ({ x: xFor(i), y: yFor(v) }))
    return {
      ...s,
      pts,
      line: toPath(pts),
      area: s.area ? toPath(pts, true, baseY) : ''
    }
  })
})

function format(v) {
  return props.formatter ? props.formatter(v) : moneyShort(v)
}

/* ---------- 悬停 ---------- */
const hoverIndex = ref(-1)

function onMove(e) {
  const rect = e.currentTarget.getBoundingClientRect()
  const x = e.clientX - rect.left
  const n = props.labels.length
  if (n < 2 || !plotW.value) return
  // 反推最近的数据点下标
  const ratio = (x - PAD.left) / plotW.value
  hoverIndex.value = Math.min(n - 1, Math.max(0, Math.round(ratio * (n - 1))))
}

const onLeave = () => {
  hoverIndex.value = -1
}

/** 气泡位置 + 是否翻转到下方 */
const tooltip = computed(() => {
  const i = hoverIndex.value
  if (i < 0 || !geometry.value.length) return null
  const first = geometry.value[0].pts[i]
  const flip = first.y < 92
  return {
    index: i,
    x: first.x,
    y: first.y,
    flip,
    label: props.labels[i],
    rows: geometry.value.map((s) => ({
      name: s.name,
      color: s.color,
      value: format(s.values[i]),
      y: s.pts[i].y
    }))
  }
})
</script>

<template>
  <div
    ref="wrapRef"
    class="chart"
    :style="{ height: `${height}px` }"
    @mousemove="onMove"
    @mouseleave="onLeave"
  >
    <svg v-if="width" :width="width" :height="height" class="chart__svg">
      <defs>
        <linearGradient
          v-for="(s, si) in geometry"
          :id="`area-${si}`"
          :key="`g${si}`"
          x1="0"
          y1="0"
          x2="0"
          y2="1"
        >
          <stop offset="0%" :stop-color="s.color" stop-opacity="0.22" />
          <stop offset="100%" :stop-color="s.color" stop-opacity="0" />
        </linearGradient>
      </defs>

      <!-- 横向网格 + y 轴刻度 -->
      <g>
        <template v-for="t in yTicks" :key="`y${t.value}`">
          <line
            :x1="PAD.left"
            :x2="width - PAD.right"
            :y1="t.y"
            :y2="t.y"
            stroke="var(--line-soft)"
            stroke-width="1"
          />
          <text :x="PAD.left - 10" :y="t.y + 4" text-anchor="end" class="chart__axis">
            {{ t.text }}
          </text>
        </template>
      </g>

      <!-- x 轴刻度 -->
      <g>
        <text
          v-for="t in xTicks"
          :key="`x${t.i}`"
          :x="t.x"
          :y="height - 8"
          text-anchor="middle"
          class="chart__axis"
        >
          {{ t.label }}
        </text>
      </g>

      <!-- 面积 + 折线：虚线序列不填充，作为对比基准 -->
      <g v-for="(s, si) in geometry" :key="`s${si}`">
        <path v-if="s.area" :d="s.area" :fill="`url(#area-${si})`" />
        <path
          class="chart__line"
          :class="{ 'is-dashed': s.dashed }"
          :d="s.line"
          fill="none"
          :stroke="s.color"
          :stroke-width="s.dashed ? 1.8 : 2.4"
          stroke-linecap="round"
          stroke-linejoin="round"
          pathLength="1"
        />
      </g>

      <!-- 悬停准星与数据点 -->
      <g v-if="tooltip">
        <line
          :x1="tooltip.x"
          :x2="tooltip.x"
          :y1="PAD.top"
          :y2="PAD.top + plotH"
          stroke="var(--brand)"
          stroke-width="1"
          stroke-dasharray="3 3"
          opacity="0.5"
        />
        <circle
          v-for="(row, ri) in tooltip.rows"
          :key="`d${ri}`"
          :cx="tooltip.x"
          :cy="row.y"
          r="4.5"
          :fill="row.color"
          stroke="#fff"
          stroke-width="2"
        />
      </g>
    </svg>

    <!-- 悬停气泡：用 HTML 而非 SVG text，避免字体与换行受限 -->
    <div
      v-if="tooltip"
      class="chart__tip"
      :class="{ 'is-flip': tooltip.flip }"
      :style="{ left: `${tooltip.x}px`, top: `${tooltip.y}px` }"
    >
      <div class="chart__tip-date">{{ tooltip.label }}</div>
      <div v-for="(row, ri) in tooltip.rows" :key="`r${ri}`" class="chart__tip-row">
        <i :style="{ background: row.color }" />
        <span>{{ row.name }}</span>
        <b>{{ row.value }}</b>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chart {
  position: relative;
  width: 100%;
}
.chart__svg {
  display: block;
}
.chart__axis {
  font-family: var(--font-mono);
  font-size: 10px;
  fill: var(--text-3);
}

/* 描线入场：pathLength=1 让动画与真实路径长度解耦 */
.chart__line {
  stroke-dasharray: 1;
  stroke-dashoffset: 1;
  animation: draw 1.1s cubic-bezier(0.22, 0.8, 0.2, 1) forwards;
}
/* 对比线不描线，改为延迟淡入，避免两条线同时动画抢注意力 */
.chart__line.is-dashed {
  stroke-dasharray: 5 5;
  stroke-dashoffset: 0;
  opacity: 0;
  animation: fade-in 0.9s ease forwards;
  animation-delay: 0.25s;
}
@keyframes draw {
  to {
    stroke-dashoffset: 0;
  }
}
@keyframes fade-in {
  to {
    opacity: 0.72;
  }
}

.chart__tip {
  position: absolute;
  transform: translate(-50%, -118%);
  background: var(--rail);
  color: #fff;
  border-radius: 10px;
  padding: 9px 12px;
  font-size: 11.5px;
  line-height: 1.7;
  pointer-events: none;
  white-space: nowrap;
  box-shadow: var(--shadow-l);
  z-index: 5;
}
/* 靠近顶部时翻转到数据点下方 */
.chart__tip.is-flip {
  transform: translate(-50%, 18%);
}
.chart__tip-date {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: #8c9ab5;
  margin-bottom: 3px;
}
.chart__tip-row {
  display: flex;
  align-items: center;
  gap: 7px;
}
.chart__tip-row i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}
.chart__tip-row span {
  color: #a9b4c8;
}
.chart__tip-row b {
  margin-left: auto;
  font-family: var(--font-mono);
  font-weight: 600;
}
</style>
