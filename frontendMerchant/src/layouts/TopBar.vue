<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { logout } from '@/api/auth'
import { useMerchantStore } from '@/stores/merchant'

/**
 * 顶栏。
 *
 * - 面包屑：由当前路由 meta.title 生成
 * - 全局搜索：写入 store.keyword，当前页面监听后过滤自己的列表
 * - 通知：内容由 store 角标实时拼装，点进去直接跳到对应待办页面
 *
 * 页面级操作（如「发布商品」「批量发货」）不放在顶栏，
 * 而是由各页面的 PageHeader 右侧操作区承载 —— 它们是页面作用域的动作，
 * 放在页头更符合后台系统的惯例，也避免了布局与视图之间的隐式耦合。
 */
const route = useRoute()
const router = useRouter()
const store = useMerchantStore()

const title = computed(() => route.meta.title || '')
const keyword = computed({
  get: () => store.keyword,
  set: (v) => store.setKeyword(v)
})

/** 搜索框占位随页面变化，让用户知道「这个框会搜什么」 */
const PLACEHOLDER = {
  products: '搜索商品名称 / 编码 / 品牌',
  orders: '搜索订单号 / 买家 / 商品',
  shipping: '搜索订单号 / 买家 / 运单号',
  aftersale: '搜索工单号 / 订单号 / 买家',
  reviews: '搜索评价内容 / 买家 / 商品',
  marketing: '搜索活动名称 / 活动编号'
}
const placeholder = computed(() => PLACEHOLDER[route.name] || '搜索…')

/* ---------- ⌘K / Ctrl+K 聚焦搜索 ---------- */
const searchRef = ref(null)

function onKeydown(e) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    searchRef.value?.focus()
  }
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

function goNotice(item) {
  if (item?.to) router.push(item.to)
}

/**
 * 退出登录：服务端删会话 + 清本地令牌，再清空 store 防止下一个账号看到上一个账号的数据。
 * 用 location.replace 而不是 router.push，顺带把历史记录里的后台页面抹掉。
 */
async function onLogout() {
  try {
    await ElMessageBox.confirm('确定退出当前商家账号吗？', '退出登录', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await logout()
  store.reset()
  ElMessage.success('已退出登录')
  router.replace({ name: 'login' })
}
</script>

<template>
  <header class="topbar">
    <div class="topbar__crumb">
      商家中心 <span>/</span> <b>{{ title }}</b>
    </div>

    <el-input
      ref="searchRef"
      v-model="keyword"
      class="topbar__search"
      :placeholder="placeholder"
      clearable
    >
      <template #prefix>
        <el-icon><Search /></el-icon>
      </template>
      <template #suffix>
        <kbd>⌘K</kbd>
      </template>
    </el-input>

    <!-- 通知 -->
    <el-popover placement="bottom-end" :width="330" trigger="click" popper-class="notice-pop">
      <template #reference>
        <el-badge :value="store.unreadCount" :hidden="!store.unreadCount" class="topbar__badge">
          <button class="icon-btn" aria-label="通知">
            <el-icon :size="18"><Bell /></el-icon>
          </button>
        </el-badge>
      </template>

      <div class="notice">
        <div class="notice__head">待办提醒</div>
        <template v-if="store.notifications.length">
          <div
            v-for="item in store.notifications"
            :key="item.key"
            class="notice__item"
            @click="goNotice(item)"
          >
            <div class="notice__icon" :class="`tone-${item.tone}`">
              <el-icon :size="15"><component :is="item.icon" /></el-icon>
            </div>
            <div class="notice__body">
              <p>{{ item.title }}</p>
              <span>{{ item.desc }}</span>
            </div>
          </div>
        </template>
        <el-empty v-else description="暂无待办，店铺运转正常" :image-size="70" />
      </div>
    </el-popover>

    <!-- 用户 -->
    <el-dropdown trigger="click">
      <div class="topbar__user">
        <div class="topbar__avatar">{{ store.user.avatar }}</div>
        <div class="topbar__info">
          <b>{{ store.user.name }}</b>
          <span>{{ store.user.role }}</span>
        </div>
        <el-icon class="topbar__caret"><ArrowDown /></el-icon>
      </div>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item>
            <el-icon><Shop /></el-icon> 店铺资料
          </el-dropdown-item>
          <el-dropdown-item>
            <el-icon><Setting /></el-icon> 账号设置
          </el-dropdown-item>
          <el-dropdown-item divided @click="onLogout">
            <el-icon><SwitchButton /></el-icon> 退出登录
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 16px;
  height: var(--topbar-h);
  padding: 0 28px;
  /* 半透明 + 背景模糊：滚动时内容从顶栏下穿过，保留「画布在流动」的感觉 */
  background: rgba(243, 240, 233, 0.82);
  backdrop-filter: saturate(1.6) blur(14px);
  -webkit-backdrop-filter: saturate(1.6) blur(14px);
  border-bottom: 1px solid var(--line);
}

.topbar__crumb {
  font-size: 12.5px;
  color: var(--text-3);
  display: flex;
  align-items: center;
  gap: 7px;
  white-space: nowrap;
}
.topbar__crumb b {
  color: var(--text);
  font-weight: 700;
}

.topbar__search {
  margin-left: auto;
  width: 268px;
}
.topbar__search :deep(.el-input__wrapper) {
  height: 38px;
  border-radius: 11px;
}
.topbar__search :deep(.el-input__prefix) {
  color: var(--text-3);
}
.topbar__search kbd {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-3);
  border: 1px solid var(--line);
  border-radius: 5px;
  padding: 1px 5px;
  background: #faf8f4;
}

.topbar__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.topbar__actions:empty {
  display: none;
}

.icon-btn {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  border: 1px solid var(--line);
  background: #fff;
  display: grid;
  place-items: center;
  color: var(--text-2);
  cursor: pointer;
  transition: all 0.18s;
}
.icon-btn:hover {
  color: var(--brand);
  border-color: #c9dcff;
  background: var(--brand-soft);
}
.topbar__badge :deep(.el-badge__content) {
  top: 6px;
  right: 8px;
}

/* ---------- 用户 ---------- */
.topbar__user {
  display: flex;
  align-items: center;
  gap: 9px;
  padding-left: 15px;
  border-left: 1px solid var(--line);
  cursor: pointer;
}
.topbar__avatar {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 800;
  color: #fff;
  background: linear-gradient(135deg, #2b3a67, var(--brand));
}
.topbar__info b {
  font-size: 13px;
  display: block;
  line-height: 1.2;
}
.topbar__info span {
  font-size: 11px;
  color: var(--text-3);
}
.topbar__caret {
  color: var(--text-3);
  font-size: 12px;
}

/* ---------- 通知面板 ---------- */
.notice__head {
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--text-3);
  font-weight: 700;
  padding: 6px 10px 8px;
}
.notice__item {
  display: flex;
  gap: 10px;
  padding: 10px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.18s;
}
.notice__item:hover {
  background: #faf8f4;
}
.notice__icon {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
}
.notice__icon.tone-coral {
  background: var(--coral-soft);
  color: var(--coral);
}
.notice__icon.tone-amber {
  background: var(--amber-soft);
  color: var(--amber);
}
.notice__icon.tone-violet {
  background: var(--violet-soft);
  color: var(--violet);
}
.notice__icon.tone-brand {
  background: var(--brand-soft);
  color: var(--brand);
}
.notice__body p {
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.5;
}
.notice__body span {
  font-size: 11px;
  color: var(--text-3);
}
</style>
