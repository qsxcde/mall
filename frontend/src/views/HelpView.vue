<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import contentApi from '@/api/content'
import { SERVICE_HOTLINE, SERVICE_HOURS } from '@/data/constants'

const faqs = ref([])

/** 本站没有在线客服系统，统一给出可拨通的热线，而不是假装「已接入客服」 */
const contactService = () => {
  ElMessage.info(`客服热线 ${SERVICE_HOTLINE}（${SERVICE_HOURS}）`)
}


onMounted(async () => {
  try {
    faqs.value = await contentApi.faqs()
  } catch (e) {
    faqs.value = []
  }
})
</script>

<template>
  <div class="container page-wrap">
    <div class="doc-card">
      <div class="doc-title">帮助中心</div>
      <div class="doc-sub">常见问题与自助服务指引</div>

      <el-collapse>
        <el-collapse-item v-for="(f, i) in faqs" :key="i" :name="i">
          <template #title>
            <span class="q"><span class="ic">Q</span>{{ f.q }}</span>
          </template>
          <div class="a">{{ f.a }}</div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <div class="contact-bar">
      <div><b>没找到答案？</b><br />客服热线 {{ SERVICE_HOTLINE }}（{{ SERVICE_HOURS }}）</div>
      <button class="go" @click="contactService">联系客服</button>
    </div>
  </div>
</template>
