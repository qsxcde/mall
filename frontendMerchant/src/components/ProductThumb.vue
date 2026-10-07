<script setup>
/**
 * 商品缩略图占位。
 *
 * 设计稿没有真实图片资源，这里用「渐变底色 + 品牌短标」表达商品身份，
 * 比灰色方块更利于在长列表里快速扫读。接入真实图片时替换内部结构即可。
 */
defineProps({
  /** 配色键 th1~th6 */
  thumb: { type: String, default: 'th1' },
  /** 图上的短标签，如 iPhone / SONY */
  tag: { type: String, default: '' },
  size: { type: Number, default: 46 },
  radius: { type: Number, default: 10 }
})
</script>

<template>
  <div
    class="thumb"
    :class="thumb"
    :style="{ width: `${size}px`, height: `${size}px`, borderRadius: `${radius}px` }"
  >
    <span>{{ tag }}</span>
  </div>
</template>

<style scoped>
.thumb {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  overflow: hidden;
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: rgba(23, 31, 46, 0.45);
}
.thumb span {
  padding: 0 3px;
  text-align: center;
  line-height: 1.1;
}
.th1 {
  background: linear-gradient(135deg, #d9e8ff, #a9ccff);
}
.th2 {
  background: linear-gradient(135deg, #ffe3d6, #ffc0a3);
}
.th3 {
  background: linear-gradient(135deg, #d8f4ea, #a8e6d3);
}
.th4 {
  background: linear-gradient(135deg, #ffeec9, #ffdb96);
}
.th5 {
  background: linear-gradient(135deg, #e6dcff, #cbb9ff);
}
.th6 {
  background: linear-gradient(135deg, #d9f0ff, #a8dcf7);
}
</style>
