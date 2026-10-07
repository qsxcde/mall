<script setup>
import CountUp from './CountUp.vue'
import SparkLine from './charts/SparkLine.vue'

/**
 * 指标条。
 *
 * 一个组件兼容两种密度：
 * - 传 trend → 渲染为「KPI 卡」：大数字 + 迷你走势 + 环比
 * - 不传 trend → 渲染为「概览卡」：大数字 + 一行说明
 *
 * items: [{ key, label, value, prefix?, suffix?, digits?, tone?, delta?, trend?, desc?, alert? }]
 */
defineProps({
  items: { type: Array, default: () => [] }
})
</script>

<template>
  <section class="mz-stat-strip">
    <div
      v-for="it in items"
      :key="it.key || it.label"
      class="stat"
      :class="{ 'is-alert': it.alert }"
    >
      <div class="stat__head">
        <i class="stat__dot" :style="{ background: `var(--${it.tone || 'brand'})` }" />
        <span class="stat__label">{{ it.label }}</span>
      </div>

      <div class="stat__body">
        <CountUp
          class="stat__value"
          :value="it.value"
          :prefix="it.prefix"
          :suffix="it.suffix"
          :decimals="it.digits || 0"
        />
        <SparkLine v-if="it.trend" :values="it.trend" :tone="it.tone" class="stat__spark" />
      </div>

      <div class="stat__foot">
        <template v-if="it.delta !== undefined && it.delta !== null">
          <span class="stat__delta" :class="it.delta >= 0 ? 'is-up' : 'is-down'">
            {{ it.delta >= 0 ? '↑' : '↓' }}{{ (Math.abs(it.delta) * 100).toFixed(1) }}%
          </span>
          <span class="stat__note">较上周期</span>
        </template>
        <span v-else class="stat__note">{{ it.desc }}</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.stat {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--r-m);
  padding: 14px 16px;
  transition: transform 0.22s cubic-bezier(0.2, 0.8, 0.2, 1), box-shadow 0.22s;
}
.stat:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-m);
}
/* 需要立刻被注意到的指标（如超时订单）用暖红边提示 */
.stat.is-alert {
  border-color: #ffd9cc;
  background: linear-gradient(180deg, #fff, #fffaf8);
}

.stat__head {
  display: flex;
  align-items: center;
  gap: 7px;
}
.stat__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.stat__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-2);
}

.stat__body {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  margin-top: 8px;
}
.stat__value {
  font-size: 25px;
  font-weight: 600;
  line-height: 1.1;
}
.stat__spark {
  margin-left: auto;
  margin-bottom: 3px;
}

.stat__foot {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 5px;
  font-size: 11.5px;
  color: var(--text-3);
}
.stat__delta {
  font-family: var(--font-mono);
  font-weight: 600;
}
.stat__delta.is-up {
  color: var(--green);
}
.stat__delta.is-down {
  color: var(--coral);
}
.stat__note {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
