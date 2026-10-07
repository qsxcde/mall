<script setup>
/**
 * 批量操作条。
 * 选中 0 行时高度收为 0（而非 display:none），让展开/收起有过渡动画。
 */
defineProps({
  count: { type: Number, default: 0 },
  unit: { type: String, default: '笔' },
  label: { type: String, default: '订单' }
})

const emit = defineEmits(['clear'])
</script>

<template>
  <div class="bulk" :class="{ 'is-on': count > 0 }">
    <span class="bulk__txt">
      已选 <b>{{ count }}</b> {{ unit }}{{ label }}
    </span>
    <div class="bulk__spacer" />
    <slot />
    <button class="bulk__btn" @click="emit('clear')">取消选择</button>
  </div>
</template>

<style scoped>
.bulk {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  max-height: 0;
  overflow: hidden;
  color: #fff;
  background: linear-gradient(100deg, var(--rail), #17233d);
  transition: max-height 0.28s cubic-bezier(0.22, 0.8, 0.2, 1), padding 0.28s;
}
.bulk.is-on {
  max-height: 68px;
  padding: 13px 16px;
}
.bulk__txt {
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}
.bulk__txt b {
  font-family: var(--font-display);
  font-size: 16px;
  color: #ffd88a;
  margin: 0 3px;
}
.bulk__spacer {
  margin-left: auto;
}
.bulk__btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 13px;
  border-radius: 9px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  background: rgba(255, 255, 255, 0.1);
  color: #e6ecf7;
  font-size: 12.5px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.18s;
}
.bulk__btn:hover {
  background: rgba(255, 255, 255, 0.2);
}
/* 批量条里的主操作（如「批量发货」）用品牌渐变突出 */
.bulk :deep(.bulk-primary) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 14px;
  border-radius: 9px;
  border: none;
  background: linear-gradient(135deg, #4da3ff, var(--brand));
  color: #fff;
  font-size: 12.5px;
  font-weight: 700;
  cursor: pointer;
  white-space: nowrap;
  transition: filter 0.18s;
}
.bulk :deep(.bulk-primary:hover) {
  filter: brightness(1.08);
}
</style>
