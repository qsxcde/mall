<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const user = useUserStore()
const { isLoggedIn, info } = storeToRefs(user)

const greeting = computed(() => (isLoggedIn.value ? `Hi，${info.value.nickname}` : ''))

const goLogin = () => router.push({ name: 'login' })
const logout = () => {
  user.logout()
  ElMessage.success('已退出登录')
}
</script>

<template>
  <div class="topbar">
    <div class="container topbar-inner">
      <div>📍 配送至：江苏无锡</div>

      <div v-if="isLoggedIn">
        <router-link :to="{ name: 'user' }">{{ greeting }}</router-link>
        <span class="divider">|</span>
        <router-link :to="{ name: 'orders' }">我的订单</router-link>
        <router-link :to="{ name: 'user' }">个人中心</router-link>
        <router-link :to="{ name: 'member' }">会员中心</router-link>
        <router-link :to="{ name: 'help' }">客服</router-link>
        <a @click="logout">退出</a>
      </div>

      <div v-else>
        <a @click="goLogin">登录</a>
        <a @click="router.push({ name: 'register' })">注册</a>
        <span class="divider">|</span>
        <router-link :to="{ name: 'orders' }">我的订单</router-link>
        <router-link :to="{ name: 'user' }">个人中心</router-link>
        <router-link :to="{ name: 'member' }">会员中心</router-link>
        <router-link :to="{ name: 'help' }">客服</router-link>
      </div>
    </div>
  </div>
</template>
