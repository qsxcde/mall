<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import contentApi from '@/api/content'

const faqs = ref([])

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
      <div><b>没找到答案？</b><br />工作时间 9:00-22:00 在线客服为你服务</div>
      <button class="go" @click="ElMessage.success('已为您接入在线客服（演示）')">联系在线客服</button>
    </div>
  </div>
</template>
