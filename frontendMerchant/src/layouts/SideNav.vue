<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useMerchantStore } from '@/stores/merchant'

/**
 * 侧栏导航。
 *
 * 分组与图标在此显式声明（路由 meta 里有 group/label，但没有图标），
 * 顺序即业务顺序：经营 → 商品 → 交易 → 财务 → 增长。
 */
const NAV_GROUPS = [
  {
    label: '经营',
    items: [
      { path: '/overview', label: '经营概览', icon: 'Odometer' },
      { path: '/analytics', label: '数据看板', icon: 'TrendCharts' }
    ]
  },
  {
    label: '商品',
    items: [{ path: '/products', label: '商品管理', icon: 'Box', badge: 'productWarn' }]
  },
  {
    label: '交易',
    items: [
      { path: '/orders', label: '订单管理', icon: 'Tickets', badge: 'orderPending' },
      { path: '/shipping', label: '发货中心', icon: 'Van', badge: 'shippingLate' },
      { path: '/aftersale', label: '售后管理', icon: 'RefreshLeft', badge: 'aftersale' }
    ]
  },
  {
    label: '财务',
    items: [{ path: '/finance', label: '财务结算', icon: 'Wallet' }]
  },
  {
    label: '增长',
    items: [
      { path: '/marketing', label: '营销中心', icon: 'Promotion', badge: 'marketing' },
      { path: '/reviews', label: '评价管理', icon: 'ChatLineSquare', badge: 'reviewWait' }
    ]
  }
]

const route = useRoute()
const store = useMerchantStore()

/** 当前激活项：用路由 path 精确匹配，父级路由不会被误点亮 */
const activePath = computed(() => route.path)
</script>

<template>
  <aside class="rail">
    <!-- 品牌 -->
    <div class="rail__brand">
      <div class="rail__mark">极</div>
      <div class="rail__title">
        <b>极客商家中心</b>
        <span>Merchant Hub</span>
      </div>
    </div>

    <!-- 导航：用 el-menu 承载，router 模式让 index 直接当路径用 -->
    <el-scrollbar class="rail__scroll">
      <el-menu :default-active="activePath" router class="rail__menu" :collapse="false">
        <template v-for="group in NAV_GROUPS" :key="group.label">
          <div class="rail__label">{{ group.label }}</div>
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>
              <span class="rail__text">{{ item.label }}</span>
              <span v-if="store.badgeOf(item.badge) > 0" class="rail__pill">
                {{ store.badgeOf(item.badge) }}
              </span>
            </template>
          </el-menu-item>
        </template>
      </el-menu>
    </el-scrollbar>

    <!-- 店铺卡片 -->
    <div class="rail__foot">
      <div class="store-card">
        <div class="store-card__logo">{{ store.shop.logo }}</div>
        <div class="store-card__txt">
          <b>{{ store.shop.name }}</b>
          <span>
            <i class="dot" />
            {{ store.shop.verified ? '已认证' : '未认证' }} · {{ store.shop.level }}
          </span>
        </div>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.rail {
  width: var(--rail-w);
  flex-shrink: 0;
  height: 100vh;
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  background: var(--rail);
  /* 双径向光晕：左上品牌蓝呼吸、右下珊瑚暖光，打破纯色块 */
  background-image:
    radial-gradient(120% 90% at 0% 0%, rgba(26, 109, 255, 0.22), transparent 55%),
    radial-gradient(90% 70% at 100% 100%, rgba(255, 90, 46, 0.1), transparent 60%);
  color: #cfd6e4;
  z-index: 40;
}

/* ---------- 品牌 ---------- */
.rail__brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 22px 20px 20px;
}
.rail__mark {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  font-family: var(--font-display);
  font-weight: 700;
  font-size: 19px;
  color: #fff;
  background: linear-gradient(140deg, #4da3ff, var(--brand) 60%, var(--brand-deep));
  box-shadow: 0 8px 20px -8px rgba(26, 109, 255, 0.9), inset 0 1px 0 rgba(255, 255, 255, 0.4);
}
.rail__title b {
  display: block;
  color: #fff;
  font-size: 15px;
  font-weight: 800;
  letter-spacing: 0.02em;
  line-height: 1.2;
}
.rail__title span {
  font-size: 11px;
  color: #7b869c;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

/* ---------- 菜单 ---------- */
.rail__scroll {
  flex: 1;
  min-height: 0;
}
.rail__menu {
  border-right: none;
  background: transparent;
  padding: 0 12px 16px;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #a9b4c8;
  --el-menu-hover-bg-color: rgba(255, 255, 255, 0.06);
  --el-menu-active-color: #fff;
  --el-menu-item-height: 40px;
}
.rail__label {
  font-size: 10.5px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: #5c6880;
  font-weight: 700;
  padding: 15px 10px 8px;
}
.rail__menu :deep(.el-menu-item) {
  position: relative; /* 供激活态左侧竖条定位 */
  height: 40px;
  line-height: 40px;
  border-radius: 10px;
  font-size: 13.5px;
  font-weight: 600;
  padding-left: 12px !important;
  margin-bottom: 2px;
}
.rail__menu :deep(.el-menu-item:hover) {
  color: #fff;
}
.rail__menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background: linear-gradient(100deg, rgba(26, 109, 255, 0.95), rgba(26, 109, 255, 0.55));
  box-shadow: 0 10px 24px -12px rgba(26, 109, 255, 0.95);
}
/* 激活项左侧的短竖条，指向性更明确 */
.rail__menu :deep(.el-menu-item.is-active)::before {
  content: "";
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  border-radius: 0 3px 3px 0;
  background: #7fbaff;
}
.rail__menu :deep(.el-menu-item .el-icon) {
  font-size: 18px;
  margin-right: 11px;
  width: 18px;
}
.rail__text {
  flex: 1;
  min-width: 0;
}
.rail__pill {
  margin-left: auto;
  background: var(--coral);
  color: #fff;
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 600;
  border-radius: 20px;
  padding: 1.5px 7px;
  line-height: 1.5;
}
.rail__menu :deep(.el-menu-item.is-active) .rail__pill {
  background: rgba(255, 255, 255, 0.28);
}

/* ---------- 店铺卡 ---------- */
.rail__foot {
  padding: 14px;
  border-top: 1px solid var(--rail-line);
}
.store-card {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--rail-2);
  border: 1px solid var(--rail-line);
  border-radius: 12px;
  padding: 11px;
}
.store-card__logo {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 800;
  color: #4a3208;
  background: linear-gradient(135deg, #ffd88a, var(--gold));
}
.store-card__txt {
  min-width: 0;
}
.store-card__txt b {
  display: block;
  font-size: 12.5px;
  color: #fff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.store-card__txt span {
  font-size: 11px;
  color: #7b869c;
  display: flex;
  align-items: center;
  gap: 5px;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #35d07f;
  box-shadow: 0 0 0 3px rgba(53, 208, 127, 0.18);
}
</style>
