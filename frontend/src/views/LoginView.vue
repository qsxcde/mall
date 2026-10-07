<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import AuthAside from '@/components/AuthAside.vue'

const route = useRoute()
const router = useRouter()
const user = useUserStore()

const loginTab = ref('pwd')
const pwdFormRef = ref()
const smsFormRef = ref()
const smsCounter = ref(0)

// 从注册页返回时携带手机号，自动填入
const pwdForm = reactive({
  account: typeof route.query.account === 'string' ? route.query.account : '',
  password: '',
  remember: true
})
const smsForm = reactive({ phone: '', code: '', remember: true })

const phoneRule = { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号', trigger: 'blur' }

const pwdRules = {
  account: [{ required: true, message: '请输入手机号或邮箱', trigger: 'blur' }],
  password: [{ required: true, message: '请输入登录密码', trigger: 'blur' }]
}
const smsRules = {
  phone: [{ required: true, message: '请输入手机号', trigger: 'blur' }, phoneRule],
  code: [{ required: true, message: '请输入短信验证码', trigger: 'blur' }]
}

const sendCode = async () => {
  if (!/^1[3-9]\d{9}$/.test(smsForm.phone)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  try {
    const result = await user.sendSmsCode(smsForm.phone, 'login')
    smsCounter.value = 60
    const timer = setInterval(() => {
      smsCounter.value--
      if (smsCounter.value <= 0) clearInterval(timer)
    }, 1000)
    // 开发环境后端会回显验证码，直接填入，省去查日志
    if (result?.devCode) {
      smsForm.code = result.devCode
      ElMessage.success(`验证码已发送（开发环境已自动填入 ${result.devCode}）`)
    } else {
      ElMessage.success('验证码已发送')
    }
  } catch (e) {
    /* 失败信息由请求拦截器统一提示 */
  }
}

const afterLogin = () => {
  const redirect = route.query.redirect
  router.replace(typeof redirect === 'string' ? redirect : { name: 'home' })
}

const validate = async (formEl) => {
  if (!formEl) return false
  try {
    await formEl.validate()
    return true
  } catch (e) {
    return false
  }
}

const submitLogin = async (formEl, form) => {
  if (!(await validate(formEl))) return
  try {
    await user.login({ account: form.account, password: form.password, remember: form.remember })
    ElMessage.success('登录成功')
    afterLogin()
  } catch (e) {
    /* 失败信息由请求拦截器统一提示 */
  }
}

const submitSmsLogin = async (formEl) => {
  if (!(await validate(formEl))) return
  try {
    await user.smsLogin({ phone: smsForm.phone, smsCode: smsForm.code })
    ElMessage.success('登录成功')
    afterLogin()
  } catch (e) {
    /* 失败信息由请求拦截器统一提示 */
  }
}
</script>

<template>
  <AuthAside />

  <main class="auth-main">
    <div class="form-wrap">
      <div class="form-head">
        <h1>欢迎回来</h1>
        <p>登录您的极客数码账号</p>
      </div>

      <el-tabs v-model="loginTab" stretch class="auth-tabs">
        <el-tab-pane label="密码登录" name="pwd" />
        <el-tab-pane label="短信登录" name="sms" />
      </el-tabs>

      <!-- 密码登录 -->
      <el-form v-show="loginTab === 'pwd'" ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" @submit.prevent>
        <el-form-item prop="account">
          <el-input v-model="pwdForm.account" placeholder="手机号 / 邮箱" size="large" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="pwdForm.password" type="password" placeholder="请输入登录密码" size="large" show-password />
        </el-form-item>
        <div style="display:flex;justify-content:space-between;align-items:center;margin:4px 0 20px">
          <el-checkbox v-model="pwdForm.remember">记住我</el-checkbox>
          <el-link type="primary" :underline="false" @click="router.push({ name: 'forgot' })">忘记密码？</el-link>
        </div>
        <el-button type="primary" size="large" style="width:100%" @click="submitLogin(pwdFormRef, pwdForm)">登录</el-button>
      </el-form>

      <!-- 短信登录 -->
      <el-form v-show="loginTab === 'sms'" ref="smsFormRef" :model="smsForm" :rules="smsRules" @submit.prevent>
        <el-form-item prop="phone">
          <el-input v-model="smsForm.phone" placeholder="请输入手机号" size="large" maxlength="11" />
        </el-form-item>
        <el-form-item prop="code">
          <el-input v-model="smsForm.code" placeholder="请输入短信验证码" size="large" maxlength="6">
            <template #append>
              <el-button :disabled="smsCounter > 0" @click="sendCode">
                {{ smsCounter > 0 ? `${smsCounter}s 后重试` : '获取验证码' }}
              </el-button>
            </template>
          </el-input>
        </el-form-item>
        <div style="display:flex;justify-content:space-between;align-items:center;margin:4px 0 20px">
          <el-checkbox v-model="smsForm.remember">记住我</el-checkbox>
          <el-link type="primary" :underline="false" @click="ElMessage.info('演示')">无法接收短信？</el-link>
        </div>
        <el-button type="primary" size="large" style="width:100%" @click="submitSmsLogin(smsFormRef)">登录</el-button>
      </el-form>

      <el-divider>其他方式</el-divider>
      <div class="social">
        <a title="微信登录">💬</a>
        <a title="QQ 登录">🐧</a>
        <a title="支付宝登录">🅰️</a>
      </div>
      <div class="form-foot">还没有账号？<a @click="router.push({ name: 'register' })">立即注册</a></div>
    </div>
  </main>
</template>
