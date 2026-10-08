<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import tradeApi from '@/api/trade'
import { useCartStore } from '@/stores/cart'
import { useCouponStore } from '@/stores/coupon'
import { fmtMoney } from '@/utils/format'

const router = useRouter()
const cart = useCartStore()
const couponStore = useCouponStore()

// 试算结果整体来自后端：商品、金额、地址、配送方式、可用券、支付方式
const pre = ref(null)
const addresses = ref([])
const shippingOptions = ref([])
const paymentOptions = ref([])

const addressId = ref(null)
const shipping = ref('standard')
const payment = ref('wechat')
const couponId = ref(couponStore.selected || '')

const loading = ref(false)
const submitting = ref(false)

const goodsAmount = computed(() => pre.value?.goodsAmount ?? 0)
const shippingFee = computed(() => pre.value?.shippingFee ?? 0)
const discount = computed(() => pre.value?.discount ?? 0)
const payTotal = computed(() => pre.value?.payTotal ?? 0)
const list = computed(() => pre.value?.items ?? [])
// 只展示未使用且可用的券（不可用的给出原因，置灰展示）
const coupons = computed(() => pre.value?.coupons ?? [])

const load = async () => {
  loading.value = true
  try {
    const result = await tradeApi.preOrder({
      shippingType: shipping.value,
      couponId: couponId.value || undefined
    })
    pre.value = result
    addresses.value = result.addresses
    shippingOptions.value = result.shippingOptions
    paymentOptions.value = result.paymentOptions
    // 以后端返回的实际生效值为准（券失效时后端会降级并回传提示）
    shipping.value = result.selectedShippingType
    if (!result.selectedCouponId) {
      couponId.value = ''
      couponStore.select('')
    }
    if (result.couponNotice) {
      ElMessage.warning(result.couponNotice)
    }
    if (!addressId.value) {
      addressId.value = result.defaultAddressId ?? addresses.value[0]?.id ?? null
    }
  } catch (e) {
    pre.value = null
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  // 未勾选商品时先同步一次购物车，保证试算有数据
  if (!cart.items.length) {
    await cart.load().catch(() => {})
  }
  await load()
})

// 配送方式 / 优惠券变化 → 重新试算
watch(shipping, () => load())
watch(couponId, (value) => {
  couponStore.select(value)
  load()
})

const submit = async () => {
  if (!addressId.value) return ElMessage.warning('请选择收货地址')
  if (!list.value.length) return ElMessage.info('没有可结算的商品')
  submitting.value = true
  try {
    const orderNo = await tradeApi.submit({
      addressId: addressId.value,
      shippingType: shipping.value,
      couponId: couponId.value || undefined,
      payMethod: payment.value,
      // 幂等键：同一次提交重试不会重复下单
      requestId: `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
    })
    await cart.load().catch(() => {})
    ElMessage.success('订单已提交，前往支付')
    router.push({ name: 'payment', query: { orderNo } })
  } catch (e) {
    /* 失败信息由拦截器提示 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="container">
    <!-- 步骤条 -->
    <div class="steps-bar">
      <el-steps :active="1" align-center style="max-width:720px;width:100%">
        <el-step title="确认订单" />
        <el-step title="支付" />
        <el-step title="完成" />
      </el-steps>
    </div>

    <div class="co-wrap">
      <div class="co-main">
        <!-- 收货地址 -->
        <div class="co-card">
          <div class="ttl">收货地址 <span class="edit" @click="router.push({ name: 'user', query: { tab: 'address' } })">管理</span></div>
          <div class="addr-list">
            <div
              v-for="a in addresses"
              :key="a.id"
              class="addr-opt"
              :class="{ on: addressId === a.id }"
              @click="addressId = a.id"
            >
              <div class="nm">
                {{ a.name }}
                <span v-if="a.isDefault" class="tag">默认</span>
              </div>
              <div class="dt">{{ a.phone }}<br />{{ a.region }} {{ a.detail }}</div>
            </div>
          </div>
        </div>

        <!-- 商品清单 -->
        <div class="co-card">
          <div class="ttl">商品清单（{{ list.length }} 件）</div>
          <div v-for="i in list" :key="i.id" class="co-item">
            <div class="ci" :class="i.c">图</div>
            <div class="cm">
              <div class="cn">{{ i.title }}</div>
              <div class="cs">{{ i.spec }}</div>
            </div>
            <div class="cp">¥{{ fmtMoney(i.price) }}</div>
            <div class="cq">×{{ i.qty }}</div>
          </div>
        </div>

        <!-- 配送方式 -->
        <div class="co-card">
          <div class="ttl">配送方式</div>
          <el-radio-group v-model="shipping">
            <el-radio v-for="s in shippingOptions" :key="s.value" :value="s.value">
              {{ s.label }} <span class="fee">{{ s.fee }}</span>
            </el-radio>
          </el-radio-group>
        </div>

        <!-- 优惠券 -->
        <div class="co-card">
          <div class="ttl">优惠券</div>
          <el-select v-model="couponId" placeholder="不使用优惠券" style="width:100%;max-width:380px">
            <el-option label="不使用优惠券" value="" />
            <el-option
              v-for="c in coupons"
              :key="c.id"
              :disabled="!c.usable"
              :label="c.usable
                ? `${c.name} ${c.unit}${c.amount}（${c.cond}）`
                : `${c.name} — ${c.unusableReason || '不可用'}`"
              :value="c.id"
            />
          </el-select>
        </div>

        <!-- 支付方式 -->
        <div class="co-card">
          <div class="ttl">支付方式</div>
          <el-radio-group v-model="payment">
            <el-radio v-for="p in paymentOptions" :key="p.value" :value="p.value">{{ p.label }}</el-radio>
          </el-radio-group>
        </div>
      </div>

      <!-- 汇总 -->
      <aside class="co-side">
        <div class="srow"><span>商品金额</span><b>¥{{ fmtMoney(goodsAmount) }}</b></div>
        <div class="srow"><span>运费</span><b>¥{{ fmtMoney(shippingFee) }}</b></div>
        <div class="srow disc"><span>优惠抵扣</span><b>−¥{{ fmtMoney(discount) }}</b></div>
        <div class="divider" />
        <div class="total">
          <span class="lab">应付总额</span>
          <span class="amt"><small>¥</small>{{ fmtMoney(payTotal) }}</span>
        </div>
        <button class="btn-pay" :disabled="submitting || loading" @click="submit">
          {{ submitting ? '提交中…' : '提交订单' }}
        </button>
        <div class="note">· 提交后 15 分钟内未完成支付，订单将自动取消<br />· 支持 7 天无理由退换货</div>
      </aside>
    </div>
  </div>
</template>
