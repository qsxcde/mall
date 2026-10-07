<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '@/api/auth'
import { useMerchantStore } from '@/stores/merchant'

/**
 * 商家登录页。
 *
 * 登录成功后立刻拉一次店铺与账号资料，再跳到目标页 ——
 * 这样布局挂载时 store 里已经有数据，不会先闪一帧空店名。
 */
const route = useRoute()
const router = useRouter()
const store = useMerchantStore()

const formRef = ref(null)
const loading = ref(false)

const form = reactive({
  account: '',
  password: ''
})

const rules = {
  account: [{ required: true, message: '请输入商家账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入登录密码', trigger: 'blur' }]
}

/** 演示环境：一键填入，省去每次联调手敲 */
function fillDemo() {
  form.account = 'merchant'
  form.password = '123456'
}

async function submit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await login({ account: form.account, password: form.password })
    store.reset()
    await store.loadProfile(true)
    ElMessage.success(`欢迎回来，${store.user.name || '掌柜'}`)
    // 登录前被拦截的地址优先，否则进经营概览
    const redirect = route.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/overview')
  } catch (e) {
    /* 失败提示由 request 拦截器统一给出 */
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <!-- 左侧品牌区：把「后台管生意」的基调在登录前就立住 -->
    <section class="login__brand">
      <div class="login__mark">旗</div>
      <h1 class="login__title">极客商家中心</h1>
      <p class="login__sub">经营、履约、售后、财务，一处看清。</p>
      <ul class="login__points">
        <li><b>经营概览</b> 今日成交与待办一屏掌握</li>
        <li><b>发货中心</b> 临期订单优先，避免超时扣分</li>
        <li><b>财务结算</b> 佣金、服务费、退款逐笔可复核</li>
      </ul>
    </section>

    <!-- 右侧表单区 -->
    <section class="login__panel">
      <div class="login__card">
        <header class="login__head">
          <h2>商家登录</h2>
          <p>请使用商家账号登录，买家账号无法进入商家中心。</p>
        </header>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @keyup.enter="submit"
        >
          <el-form-item label="商家账号" prop="account">
            <el-input v-model="form.account" placeholder="请输入账号" clearable>
              <template #prefix>
                <el-icon><User /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="登录密码" prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password>
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-button
            type="primary"
            size="large"
            class="login__submit"
            :loading="loading"
            @click="submit"
          >
            登录
          </el-button>
        </el-form>

        <div class="login__demo">
          <span>演示账号</span>
          <code>merchant / 123456</code>
          <el-button link type="primary" @click="fillDemo">一键填入</el-button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr);
  min-height: 100vh;
}

/* ---------- 品牌区 ---------- */
.login__brand {
  position: relative;
  padding: 64px 60px;
  color: #fff;
  background-color: var(--rail);
  background-image:
    radial-gradient(1000px 520px at 12% 8%, rgba(26, 109, 255, 0.34), transparent 62%),
    linear-gradient(160deg, var(--rail) 0%, var(--rail-2) 100%);
  display: flex;
  flex-direction: column;
  justify-content: center;
  overflow: hidden;
}
.login__brand::after {
  /* 细网格纹理：呼应「数据台」的视觉语言 */
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.045) 1px, transparent 1px);
  background-size: 46px 46px;
  mask-image: radial-gradient(520px 380px at 26% 34%, #000 0%, transparent 78%);
}
.login__brand > * {
  position: relative;
  z-index: 1;
}
.login__mark {
  width: 54px;
  height: 54px;
  border-radius: 15px;
  display: grid;
  place-items: center;
  font-size: 22px;
  font-weight: 800;
  background: linear-gradient(135deg, var(--brand), #5b9bff);
  box-shadow: 0 14px 32px -12px rgba(26, 109, 255, 0.9);
}
.login__title {
  margin: 26px 0 10px;
  font-family: var(--font-display);
  font-size: 38px;
  letter-spacing: 0.01em;
}
.login__sub {
  margin: 0;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.62);
}
.login__points {
  margin: 40px 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 14px;
  max-width: 400px;
}
.login__points li {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.72);
  padding-left: 16px;
  position: relative;
}
.login__points li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 7px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand);
}
.login__points b {
  color: #fff;
  margin-right: 8px;
}

/* ---------- 表单区 ---------- */
.login__panel {
  display: grid;
  place-items: center;
  padding: 40px 28px;
  background: var(--canvas);
}
.login__card {
  width: 100%;
  max-width: 388px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--r-l);
  padding: 34px 32px 26px;
  box-shadow: var(--shadow-m);
}
.login__head h2 {
  margin: 0 0 8px;
  font-size: 22px;
}
.login__head p {
  margin: 0 0 24px;
  font-size: 12.5px;
  color: var(--text-3);
  line-height: 1.6;
}
.login__submit {
  width: 100%;
  margin-top: 4px;
}
.login__demo {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px dashed var(--line);
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--text-3);
}
.login__demo code {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-2);
  background: #faf8f4;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 2px 7px;
}
.login__demo .el-button {
  margin-left: auto;
}

/* ---------- 窄屏：隐藏品牌区，只留表单 ---------- */
@media (max-width: 900px) {
  .login {
    grid-template-columns: 1fr;
  }
  .login__brand {
    display: none;
  }
}
</style>
