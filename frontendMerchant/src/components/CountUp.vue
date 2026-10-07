<script setup>
import { computed, toRef } from 'vue'
import { useCountUp } from '@/composables/useCountUp'
import { int, money } from '@/utils/format'

/**
 * 数字滚动展示。
 * 关键金额用衬线体（.num）呈现，配合 tabular-nums 保证滚动时宽度不抖。
 */
const props = defineProps({
  value: { type: Number, default: 0 },
  prefix: { type: String, default: '' },
  suffix: { type: String, default: '' },
  /** 小数位，>0 时自动启用千分位 */
  decimals: { type: Number, default: 0 },
  duration: { type: Number, default: 900 }
})

const { display } = useCountUp(toRef(props, 'value'), {
  duration: props.duration,
  decimals: props.decimals
})

const text = computed(() => (props.decimals > 0 ? money(display.value) : int(display.value)))
</script>

<template>
  <span class="count-up num">
    <small v-if="prefix" class="affix prefix">{{ prefix }}</small>
    <span class="value">{{ text }}</span>
    <small v-if="suffix" class="affix suffix">{{ suffix }}</small>
  </span>
</template>

<style scoped>
.count-up {
  display: inline-flex;
  align-items: baseline;
  gap: 1px;
}
.affix {
  font-family: var(--font-ui);
  font-size: 13px;
  font-weight: 600;
  opacity: 0.55;
}
.suffix {
  margin-left: 3px;
}
</style>
