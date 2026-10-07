<script setup>
import { onMounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import AppTopbar from '@/components/AppTopbar.vue'
import AppHeader from '@/components/AppHeader.vue'
import AppFooter from '@/components/AppFooter.vue'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'
import { useMessageStore } from '@/stores/message'

const user = useUserStore()
const cart = useCartStore()
const message = useMessageStore()
const { isLoggedIn } = storeToRefs(user)

/**
 * 登录态是「谁的数据」的唯一依据：
 * 进入登录态就拉取个人数据，退出就清空，避免上一个账号的数据残留。
 */
const syncAccountData = async (loggedIn) => {
  if (!loggedIn) {
    cart.reset()
    message.unreadCount = 0
    return
  }
  await user.init()
  await Promise.all([cart.load().catch(() => {}), message.loadUnread().catch(() => {})])
}

onMounted(() => syncAccountData(isLoggedIn.value))
watch(isLoggedIn, syncAccountData)
</script>

<template>
  <div class="app-shell">
    <AppTopbar />
    <AppHeader />
    <main>
      <router-view v-slot="{ Component }">
        <transition name="fade-slide" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
    <AppFooter />
  </div>
</template>

<style scoped>
.app-shell { display: flex; flex-direction: column; min-height: 100vh; }
main { flex: 1; }
</style>
