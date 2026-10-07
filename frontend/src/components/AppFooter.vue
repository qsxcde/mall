<script setup>
import { footerCols } from '@/data/constants'
</script>

<template>
  <footer class="site-footer">
    <div class="f-container">
      <div class="f-cols">
        <div v-for="col in footerCols" :key="col.title" class="f-col">
          <h4>{{ col.title }}</h4>
          <router-link v-for="link in col.links" :key="link.text" :to="link.to">
            {{ link.text }}
          </router-link>
        </div>
      </div>
      <div class="f-bottom">
        极客数码商城 · 苏ICP备 0000000 号 · 本网站为演示项目，所有数据均为虚构<br />
        © 2026 极客数码 GEEK DIGITAL. All rights reserved.
      </div>
    </div>
  </footer>
</template>
