<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AuthAside from '@/components/AuthAside.vue'

const router = useRouter()
const formRef = ref()
const counter = ref(0)

const form = reactive({ phone: '', code: '', password: '', confirm: '', agree: true })

const phoneRule = { pattern: /^1\d{10}$/, message: '请输入正确的手机号', trigger: 'blur' }

const rules = {
  phone: [{ required: true, message: '请输入手机号', trigger: 'blur' }, phoneRule],
  code: [{ required: true, message: '请输入短信验证码', trigger: 'blur' }],
  password: [
    { required: true, message: '请设置密码', trigger: 'blur' },
    { pattern: /(?=.*[a-zA-Z])(?=.*\d).{6,20}/, message: '需 6-20 位且同时包含字母和数字', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, cb) =>
        value === form.password ? cb() : cb(new Error('两次输入的密码不一致')),
      trigger: 'blur'
    }
  ],
  agree: [
    {
      validator: (_rule, value, cb) => (value ? cb() : cb(new Error('请先阅读并同意用户协议'))),
      trigger: 'change'
    }
  ]
}

const sendCode = () => {
  if (!/^1\d{10}$/.test(form.phone)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  counter.value = 60
  const t = setInterval(() => {
    counter.value--
    if (counter.value <= 0) clearInterval(t)
  }, 1000)
  ElMessage.success('验证码已发送（演示）')
}

const submit = async () => {
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  // 注册成功后回到登录页，并带上手机号便于直接登录
  ElMessage.success('注册成功，请登录')
  router.replace({ name: 'login', query: { account: form.phone } })
}
</script>

<template>
  <AuthAside />

  <main class="auth-main">
    <div class="form-wrap">
      <div class="form-head">
        <h1>创建账号</h1>
        <p>注册极客数码，立享新人专享福利</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent>
        <el-form-item prop="phone">
          <el-input v-model="form.phone" placeholder="请输入手机号" size="large" maxlength="11" />
        </el-form-item>

        <el-form-item prop="code">
          <el-input v-model="form.code" placeholder="请输入短信验证码" size="large" maxlength="6">
            <template #append>
              <el-button :disabled="counter > 0" @click="sendCode">
                {{ counter > 0 ? `${counter}s 后重试` : '获取验证码' }}
              </el-button>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="6-20 位字母数字组合" size="large" show-password />
        </el-form-item>

        <el-form-item prop="confirm">
          <el-input v-model="form.confirm" type="password" placeholder="请再次输入密码" size="large" show-password />
        </el-form-item>

        <el-form-item prop="agree">
          <el-checkbox v-model="form.agree">
            我已阅读并同意<el-link type="primary" :underline="false" @click="router.push({ name: 'policy' })">《用户协议》</el-link>
          </el-checkbox>
        </el-form-item>

        <el-button type="primary" size="large" style="width:100%" @click="submit">立即注册</el-button>
      </el-form>

      <div class="form-foot">已有账号？<a @click="router.push({ name: 'login' })">去登录</a></div>
    </div>
  </main>
</template>
