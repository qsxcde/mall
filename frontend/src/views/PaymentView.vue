<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { paymentApi, tradeApi } from '@/api/trade'
import { fmtMoney } from '@/utils/format'

const route = useRoute()
const router = useRouter()

// 订单号由结算页带过来，金额以后端订单为准
const orderNo = ref(typeof route.query.orderNo === 'string' ? route.query.orderNo : '')
const amount = ref(0)
const tradeNo = ref('')
const qrCode = ref('')

const methods = [
  { key: 'alipay', name: '支付宝', desc: '推荐 · 快捷安全', icon: '💙', cls: 'm-alipay' },
  { key: 'wechat', name: '微信支付', desc: '亿万用户的选择', icon: '💚', cls: 'm-wechat' },
  { key: 'card', name: '银行卡', desc: '储蓄卡 / 信用卡', icon: '💳', cls: 'm-card' },
  { key: 'balance', name: '账户余额', desc: '余额支付', icon: '💰', cls: 'm-balance' }
]
const active = ref('wechat')
const paid = ref(false)
const paying = ref(false)
const creating = ref(false)

const activeName = computed(() => methods.find((m) => m.key === active.value)?.name)

// 支付倒计时：初值取自订单剩余时间
const left = ref(15 * 60)
const pad = (n) => String(n).padStart(2, '0')
const countdownText = computed(() => `${pad(Math.floor(left.value / 60))}:${pad(left.value % 60)}`)
let timer = null
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

/** 拉订单：拿应付金额与剩余支付时间 */
const loadOrder = async () => {
  if (!orderNo.value) return
  try {
    const detail = await tradeApi.orderDetail(orderNo.value)
    amount.value = detail.order?.payAmount || 0
    if (detail.expireSecondsLeft > 0) {
      left.value = detail.expireSecondsLeft
    }
  } catch (e) {
    /* 失败信息由拦截器提示 */
  }
}

/** 创建支付单：切换支付方式会重新建单，拿到新的流水号与二维码 */
const createPayment = async () => {
  if (!orderNo.value) return
  creating.value = true
  try {
    const payment = await paymentApi.create(orderNo.value, active.value)
    tradeNo.value = payment.tradeNo
    qrCode.value = payment.qrCode || ''
    if (payment.amount != null) {
      amount.value = Number(payment.amount)
    }
  } catch (e) {
    /* 失败信息由拦截器提示 */
  } finally {
    creating.value = false
  }
}

onMounted(async () => {
  await loadOrder()
  await createPayment()
  timer = setInterval(() => {
    if (left.value > 0) left.value--
  }, 1000)
})

watch(active, () => {
  if (!paid.value) createPayment()
})

/** 演示用「我已支付」：真实渠道下由异步回调驱动 */
const pay = async () => {
  if (!tradeNo.value) return ElMessage.info('支付单尚未就绪，请稍候')
  paying.value = true
  try {
    await paymentApi.mockPay(tradeNo.value)
    paid.value = true
    ElMessage.success('支付成功')
  } catch (e) {
    /* 失败信息由拦截器提示 */
  } finally {
    paying.value = false
  }
}
</script>

<template>
  <div class="container">
    <div class="steps-bar">
      <el-steps :active="2" align-center style="max-width:720px;width:100%">
        <el-step title="确认订单" />
        <el-step title="支付" />
        <el-step title="完成" />
      </el-steps>
    </div>

    <div class="pay-wrap">
      <template v-if="!paid">
        <div class="pay-top">
          <div>
            <div class="order">订单号：<b>{{ orderNo }}</b></div>
            <div class="pay-count">请在 <b>{{ countdownText }}</b> 内完成支付，超时订单将自动取消</div>
          </div>
          <div class="amt"><small>¥</small>{{ fmtMoney(amount) }}</div>
        </div>

        <div class="pay-methods">
          <div
            v-for="m in methods"
            :key="m.key"
            class="pay-m"
            :class="{ on: active === m.key }"
            @click="active = m.key"
          >
            <div class="pic" :class="m.cls">{{ m.icon }}</div>
            <div>
              <div class="pn">{{ m.name }}</div>
              <div class="pd">{{ m.desc }}</div>
            </div>
          </div>
        </div>

        <div v-if="active !== 'balance'" class="pay-qr">
          <div class="qr" />
          <div class="qrtip">
            请使用 <b>{{ activeName }}</b> 扫码支付<br />打开对应 App → 扫一扫 → 完成付款<br />支付成功后页面将自动跳转
          </div>
        </div>

        <div class="pay-actions">
          <button class="btn-back-main" @click="router.push({ name: 'checkout' })">返回修改</button>
          <button class="btn-pay-main" @click="pay">确认支付 ¥{{ fmtMoney(amount) }}</button>
        </div>
      </template>

      <el-result v-else icon="success" title="支付成功" :sub-title="`订单 ${orderNo} 已支付，我们将尽快为你发货`">
        <template #extra>
          <el-button type="primary" @click="router.push({ name: 'orders' })">查看订单</el-button>
          <el-button @click="router.push({ name: 'home' })">返回首页</el-button>
        </template>
      </el-result>
    </div>
  </div>
</template>
