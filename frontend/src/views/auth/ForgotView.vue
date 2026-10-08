<script setup>
import { onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import AuthAside from '@/components/AuthAside.vue'

const router = useRouter()
const user = useUserStore()
const step = ref(0)
const counter = ref(0)
const sending = ref(false)
const submitting = ref(false)
let timer = null

const form = reactive({ phone: '', code: '', password: '', confirm: '' })

const phoneValid = () => /^1[3-9]\d{9}$/.test(form.phone)

const startCountdown = (seconds) => {
  counter.value = Number(seconds) > 0 ? Number(seconds) : 60
  clearInterval(timer)
  timer = setInterval(() => {
    counter.value--
    if (counter.value <= 0) clearInterval(timer)
  }, 1000)
}

/** 发送重置验证码：走后端 /api/v1/auth/sms-code（scene=reset） */
const sendCode = async () => {
  if (!phoneValid()) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  sending.value = true
  try {
    const result = await user.sendSmsCode(form.phone, 'reset')
    startCountdown(result?.cooldownSeconds)
    if (result?.devCode) {
      form.code = result.devCode
      ElMessage.success(`验证码已发送（开发环境已自动填入 ${result.devCode}）`)
    } else {
      ElMessage.success('验证码已发送')
    }
  } catch (e) {
    /* 失败信息由请求拦截器统一提示 */
  } finally {
    sending.value = false
  }
}

const next = () => {
  if (!phoneValid()) return ElMessage.warning('请输入正确的手机号')
  if (!/^\d{6}$/.test(form.code)) return ElMessage.warning('请输入 6 位短信验证码')
  step.value = 1
}

const back = () => {
  step.value = 0
}

/** 重置密码：走后端 /api/v1/auth/password/reset，验证码在此处由后端校验 */
const submit = async () => {
  if (!/(?=.*[a-zA-Z])(?=.*\d).{6,20}/.test(form.password)) {
    return ElMessage.warning('密码需为 6-20 位且同时包含字母和数字')
  }
  if (form.password !== form.confirm) return ElMessage.warning('两次输入的密码不一致')
  submitting.value = true
  try {
    await user.resetPassword({
      phone: form.phone,
      smsCode: form.code,
      newPassword: form.password
    })
    step.value = 2
    ElMessage.success('密码重置成功')
  } catch (e) {
    /* 拦截器已提示；验证码错误/过期时可返回上一步重新获取 */
  } finally {
    submitting.value = false
  }
}

onUnmounted(() => clearInterval(timer))
</script>

<template>
  <AuthAside />

  <main class="auth-main">
    <div class="form-wrap">
      <div class="form-head">
        <h1>{{ step === 2 ? '重置完成' : '找回密码' }}</h1>
        <p>{{ step === 2 ? '请使用新密码登录你的账号' : '通过手机号验证身份后重置密码' }}</p>
      </div>

      <el-steps :active="step" align-center style="margin-bottom:26px">
        <el-step title="验证身份" />
        <el-step title="设置新密码" />
        <el-step title="完成" />
      </el-steps>

      <!-- 第一步：验证身份 -->
      <el-form v-if="step === 0" label-position="top">
        <el-form-item label="手机号">
          <el-input v-model="form.phone" size="large" maxlength="11" placeholder="请输入注册手机号" />
        </el-form-item>
        <el-form-item label="短信验证码">
          <el-input v-model="form.code" size="large" maxlength="6" placeholder="请输入验证码">
            <template #append>
              <el-button :disabled="counter > 0" :loading="sending" @click="sendCode">
                {{ counter > 0 ? `${counter}s 后重试` : '获取验证码' }}
              </el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-button type="primary" size="large" style="width:100%" @click="next">下一步</el-button>
      </el-form>

      <!-- 第二步：设置新密码 -->
      <el-form v-else-if="step === 1" label-position="top">
        <el-form-item label="新密码">
          <el-input v-model="form.password" type="password" size="large" show-password placeholder="6-20 位字母数字组合" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="form.confirm" type="password" size="large" show-password placeholder="请再次输入新密码" />
        </el-form-item>
        <div style="display:flex;gap:10px">
          <el-button size="large" style="flex:1" @click="back">上一步</el-button>
          <el-button type="primary" size="large" style="flex:2" :loading="submitting" @click="submit">重置密码</el-button>
        </div>
      </el-form>

      <!-- 第三步：完成 -->
      <el-result v-else icon="success" title="密码重置成功" sub-title="请使用新密码登录你的账号">
        <template #extra>
          <el-button type="primary" @click="router.push({ name: 'login' })">去登录</el-button>
        </template>
      </el-result>

      <div class="form-foot">想起密码了？<a @click="router.push({ name: 'login' })">返回登录</a></div>
    </div>
  </main>
</template>
