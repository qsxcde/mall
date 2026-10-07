<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { messageIcon, messageTypes } from '@/data/constants'
import { useMessageStore } from '@/stores/message'

const router = useRouter()
const store = useMessageStore()

const active = ref('all')
const list = computed(() => store.byType(active.value))

// 消息来自服务端（含已读状态，按用户维度记录）
onMounted(() => {
  store.load().catch(() => {})
})

const open = async (m) => {
  await store.markRead(m.id)
  if (m.link) router.push(m.link)
}
const readAll = async () => {
  await store.markAllRead()
  ElMessage.success('已全部标为已读')
}
</script>

<template>
  <div class="container page-wrap">
    <el-breadcrumb class="crumb" separator=">">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item>消息中心</el-breadcrumb-item>
    </el-breadcrumb>

    <div class="msg-head">
      <div class="msg-title">
        消息中心 <el-tag v-if="store.unread" type="danger" size="small" round>{{ store.unread }} 条未读</el-tag>
      </div>
      <el-button v-if="store.unread" text type="primary" @click="readAll">全部标为已读</el-button>
    </div>

    <el-radio-group v-model="active" class="np-tabs">
      <el-radio-button v-for="t in messageTypes" :key="t.key" :value="t.key">{{ t.name }}</el-radio-button>
    </el-radio-group>

    <div v-if="list.length" class="msg-list">
      <div
        v-for="m in list"
        :key="m.id"
        class="msg-item"
        :class="{ unread: !m.read }"
        @click="open(m)"
      >
        <div class="msg-ic" :class="m.type">{{ messageIcon[m.type] }}</div>
        <div class="msg-body">
          <div class="msg-row">
            <span class="msg-ititle">{{ m.title }}</span>
            <span v-if="!m.read" class="dot" />
          </div>
          <div class="msg-desc">{{ m.desc }}</div>
          <div class="msg-time">{{ m.time }}</div>
        </div>
        <el-icon class="msg-arrow"><ArrowRight /></el-icon>
      </div>
    </div>

    <el-empty v-else description="暂无消息" />
  </div>
</template>

<style scoped>
.msg-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.msg-title { font-size: 18px; font-weight: bold; display: flex; align-items: center; gap: 10px; }
.msg-list { display: flex; flex-direction: column; gap: 10px; }
.msg-item {
  display: flex; align-items: center; gap: 16px; background: #fff; border: 1px solid var(--border);
  border-radius: 12px; padding: 16px 20px; cursor: pointer; transition: all .2s;
}
.msg-item:hover { border-color: var(--primary); box-shadow: 0 4px 14px rgba(26,109,255,.12); }
.msg-item.unread { background: #f7faff; }
.msg-ic {
  width: 44px; height: 44px; border-radius: 12px; flex-shrink: 0; font-size: 20px;
  display: flex; align-items: center; justify-content: center; background: #f0f6ff;
}
.msg-ic.coupon { background: #fff0e8; }
.msg-ic.system { background: #f2f2f7; }
.msg-body { flex: 1; min-width: 0; }
.msg-row { display: flex; align-items: center; gap: 8px; }
.msg-ititle { font-size: 15px; font-weight: 600; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); }
.msg-desc { font-size: 13px; color: #666; margin-top: 6px; line-height: 1.7; }
.msg-time { font-size: 12px; color: var(--text-light); margin-top: 6px; }
.msg-arrow { color: #c0c4cc; }
</style>
