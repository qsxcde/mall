<script setup>
/**
 * 页面页头：眉标 + 大标题 + 说明 + 右侧操作区。
 * 标题支持把关键词包在 <em> 里做成衬线蓝字（设计稿的「订单*管理*」效果）。
 */
defineProps({
  eyebrow: { type: String, default: '' },
  title: { type: String, default: '' },
  /** 用衬线体强调的部分，会拼在 title 之后 */
  titleAccent: { type: String, default: '' },
  desc: { type: String, default: '' }
})
</script>

<template>
  <section class="page-head">
    <div class="page-head__main">
      <div v-if="eyebrow" class="mz-page-head__eyebrow">{{ eyebrow }}</div>
      <h1 class="mz-page-head__title">
        {{ title }}<em v-if="titleAccent">{{ titleAccent }}</em>
      </h1>
      <div class="mz-page-head__desc">
        <!-- 插槽便于各页塞入带样式的统计数字 -->
        <slot name="desc">{{ desc }}</slot>
      </div>
    </div>
    <div class="mz-page-head__acts">
      <slot name="actions" />
    </div>
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-end;
  gap: 20px;
  flex-wrap: wrap;
}
.page-head__main {
  min-width: 0;
}
/* 页头操作区在窄桌面下允许换行并占满宽度 */
@media (max-width: 1360px) {
  .mz-page-head__acts {
    margin-left: 0;
    width: 100%;
  }
}
</style>
