<script setup>
import { onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import SideNav from './SideNav.vue'
import TopBar from './TopBar.vue'
import { useMerchantStore } from '@/stores/merchant'

/**
 * 商家端主布局：侧栏 + 顶栏 + 内容区。
 *
 * 角标只在布局挂载时加载一次；页面内的操作若要刷新角标，
 * 调用 store.loadBadges(true) 即可，不必各自维护一份计数。
 */
const store = useMerchantStore()
const route = useRoute()

onMounted(() => store.loadBadges())

// 切换页面时清空顶栏搜索词，避免上一页的关键词把下一页的列表过滤成空
watch(
  () => route.path,
  () => store.setKeyword('')
)
</script>

<template>
  <div class="layout">
    <SideNav />
    <div class="layout__main">
      <TopBar />
      <main class="layout__canvas mz-canvas">
        <router-view v-slot="{ Component }">
          <!-- 过渡让页面切换不突兀；mode="out-in" 避免新旧页面同时在位造成高度抖动 -->
          <transition name="fade-slide" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
}
.layout__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.layout__canvas {
  flex: 1;
  min-height: 0;
}

.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}
.fade-slide-enter-from {
  opacity: 0;
  transform: translateY(8px);
}
.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
</style>
