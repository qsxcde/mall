<script setup>
/**
 * 业务状态标签。
 *
 * 视图只传 tone（brand/green/teal/amber/coral/violet/neutral），
 * 具体色值统一在 <style> 里映射到设计令牌，改配色只需动这一处。
 */
defineProps({
  tone: { type: String, default: 'neutral' },
  /** 是否显示前置圆点 */
  dot: { type: Boolean, default: true },
  size: { type: String, default: 'default' } // default | small
})
</script>

<template>
  <span class="status-tag" :class="[`tone-${tone}`, { 'is-small': size === 'small' }]">
    <i v-if="dot" class="status-tag__dot" />
    <slot />
  </span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  font-weight: 700;
  line-height: 1.5;
  padding: 3px 9px;
  border-radius: 20px;
  white-space: nowrap;
}
.status-tag.is-small {
  font-size: 10.5px;
  padding: 2px 7px;
}
.status-tag__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

.tone-brand {
  color: var(--brand);
  background: var(--brand-soft);
}
.tone-green {
  color: var(--green);
  background: var(--green-soft);
}
.tone-teal {
  color: var(--teal);
  background: var(--teal-soft);
}
.tone-amber {
  color: var(--amber);
  background: var(--amber-soft);
}
.tone-coral {
  color: var(--coral);
  background: var(--coral-soft);
}
.tone-violet {
  color: var(--violet);
  background: var(--violet-soft);
}
.tone-neutral {
  color: var(--text-2);
  background: #f1eee6;
}
</style>
