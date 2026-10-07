<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AuthAside from '@/components/AuthAside.vue'

const router = useRouter()
const step = ref(0)
const counter = ref(0)
const form = reactive({ phone: '', code: '', password: '', confirm: '' })

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

const next = () => {
  if (!/^1\d{10}$/.test(form.phone)) return ElMessage.warning('请输入正确的手机号')
  if (!form.code) return ElMessage.warning('请输入短信验证码')
  step.value = 1
}

const submit = () => {
  if (!/(?=.*[a-zA-Z])(?=.*\d).{6,20}/.test(form.password)) {
    return ElMessage.warning('密码需为 6-20 位且同时包含字母和数字')
  }
  if (form.password !== form.confirm) return ElMessage.warning('两次输入的密码不一致')
  step.value = 2
  ElMessage.success('密码重置成功')
}
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
              <el-button :disabled="counter > 0" @click="sendCode">
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
          <el-button size="large" style="flex:1" @click="step = 0">上一步</el-button>
          <el-button type="primary" size="large" style="flex:2" @click="submit">重置密码</el-button>
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
