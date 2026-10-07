<script setup>
import { onMounted, ref } from 'vue'
import contentApi from '@/api/content'

// 统计数字由后端实时统计（在售商品 / 注册用户 / 累计订单）
const aboutStats = ref([])

onMounted(async () => {
  try {
    aboutStats.value = await contentApi.about()
  } catch (e) {
    aboutStats.value = []
  }
})
</script>

<template>
  <div class="container page-wrap">
    <div class="doc-card">
      <div class="doc-title">关于极客数码</div>
      <p>
        极客数码成立于 2018 年，是一家专注数码好物的新零售平台，覆盖手机、电脑、平板、耳机音响与智能穿戴等品类，
        致力于为用户提供「正品保障、极速发货、无忧售后」的购物体验。
      </p>
      <div class="stats">
        <div v-for="s in aboutStats" :key="s.lab" class="stat">
          <div class="num">{{ s.num }}</div>
          <div class="lab">{{ s.lab }}</div>
        </div>
      </div>
      <h3>我们的承诺</h3>
      <p>
        · 全场正品：品牌直供与官方授权，假一赔十；<br />
        · 价格保护：降价自动补差，买贵不怕；<br />
        · 售后无忧：7 天无理由退换、全国联保、上门维修。
      </p>
      <h3>联系我们</h3>
      <p>
        客服热线：400-000-0000（9:00-22:00）<br />
        商务合作：bd@geekdigital.example<br />
        公司地址：江苏省无锡市滨湖区蠡园经济开发区创意园 A 座
      </p>
    </div>
  </div>
</template>
