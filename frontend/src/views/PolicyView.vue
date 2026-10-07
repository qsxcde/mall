<script setup>
import { onMounted, ref } from 'vue'
import contentApi from '@/api/content'

const policies = ref([])

onMounted(async () => {
  try {
    policies.value = await contentApi.policies()
  } catch (e) {
    policies.value = []
  }
})
</script>

<template>
  <div class="container page-wrap">
    <div class="doc-card">
      <div class="doc-title">退换货政策</div>
      <template v-for="p in policies" :key="p.title">
        <h3>{{ p.title }}</h3>
        <ul>
          <li v-for="(item, i) in p.items" :key="i">{{ item }}</li>
        </ul>
      </template>
      <div class="tip">
        提示：本政策为演示说明，具体以实际订单与客服沟通为准。如有疑问请前往「帮助中心」或联系在线客服。
      </div>
    </div>
  </div>
</template>
