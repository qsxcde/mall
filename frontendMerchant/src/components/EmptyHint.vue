<script setup>
/**
 * 空态提示。
 * 图标用 Element Plus 图标组件名传入，避免为每种场景单独写一个组件。
 */
defineProps({
  icon: { type: String, default: 'Box' },
  title: { type: String, default: '暂无数据' },
  desc: { type: String, default: '' }
})
</script>

<template>
  <div class="empty-hint">
    <div class="empty-hint__icon">
      <el-icon :size="26"><component :is="icon" /></el-icon>
    </div>
    <h4>{{ title }}</h4>
    <p v-if="desc">{{ desc }}</p>
    <div v-if="$slots.default" class="empty-hint__action">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty-hint {
  padding: 58px 20px;
  text-align: center;
}
.empty-hint__icon {
  width: 58px;
  height: 58px;
  border-radius: 18px;
  margin: 0 auto 14px;
  display: grid;
  place-items: center;
  color: var(--text-3);
  background: #f4f1ea;
  border: 1px solid var(--line);
}
.empty-hint h4 {
  font-size: 15px;
  font-weight: 800;
}
.empty-hint p {
  font-size: 12.5px;
  color: var(--text-3);
  margin-top: 6px;
}
.empty-hint__action {
  margin-top: 16px;
}
</style>
