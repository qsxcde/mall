<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { fmtMoney } from '@/utils/format'

const props = defineProps({
  product: { type: Object, required: true },
  showSpec: { type: Boolean, default: false },
  keyword: { type: String, default: '' }
})

const router = useRouter()

const to = () => router.push({ name: 'product', params: { id: props.product.id } })

// 关键词高亮
const titleParts = computed(() => {
  const t = props.product.title
  const k = props.keyword
  if (!k || !t.toLowerCase().includes(k.toLowerCase())) return [{ text: t }]
  const idx = t.toLowerCase().indexOf(k.toLowerCase())
  return [
    { text: t.slice(0, idx) },
    { text: t.slice(idx, idx + k.length), hl: true },
    { text: t.slice(idx + k.length) }
  ]
})
</script>

<template>
  <div class="product-card" @click="to">
    <div class="product-img" :class="product.c">
      <span v-if="product.tags && product.tags.length" class="tag">{{ product.tags[0] }}</span>
      商品图
    </div>
    <div class="product-info">
      <div class="product-title">
        <template v-for="(part, i) in titleParts" :key="i">
          <em v-if="part.hl">{{ part.text }}</em>
          <template v-else>{{ part.text }}</template>
        </template>
      </div>
      <div v-if="showSpec" class="product-spec">{{ product.spec }}</div>
      <div class="product-price"><small>¥</small>{{ fmtMoney(product.price) }}</div>
      <div class="product-sales">已售 {{ product.sales }}</div>
    </div>
  </div>
</template>
