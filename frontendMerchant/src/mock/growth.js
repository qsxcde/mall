/**
 * 增长与财务域 Mock：营销活动 / 评价 / 结算。
 *
 * 财务部分刻意保证「口径自洽」：
 *   本期实结 = 成交额 - 平台佣金 - 支付服务费 - 退款扣减
 * 佣金与服务费按固定费率从成交额推导，因此任何一行都能人工复核。
 */
import { createRng, pick, randInt, NOW, PRODUCT_POOL, BUYERS } from './shared'
import { DAILY_SERIES, mmdd, sum } from './series'

const DAY = 86400000
const HOUR = 3600000

/* ==========================================================================
   营销活动
   ========================================================================== */

/** [活动名, 类型, 状态, 开始距今天数(负数为已开始), 持续天数] */
const PROMO_PATTERN = [
  ['双十一抢先购 · 全场满减', 'discount', 'running', -6, 18],
  ['iPhone 17 系列新品直降 500', 'discount', 'running', -3, 12],
  ['直播间专属秒杀 · 每晚 8 点', 'seckill', 'running', -10, 25],
  ['新人首单立减 100', 'coupon', 'running', -60, 120],
  ['数码配件满 299 减 50', 'coupon', 'running', -20, 45],
  ['耳机音箱 2 件 9 折', 'bundle', 'running', -8, 20],
  ['Apple 生态套装立省 1200', 'bundle', 'pending', 4, 30],
  ['双十一预售定金膨胀', 'group', 'pending', 8, 22],
  ['智能穿戴拼团 3 人成团', 'group', 'pending', 12, 15],
  ['满 5000 赠蓝牙音箱', 'gift', 'paused', -15, 40],
  ['开学季数码焕新专场', 'discount', 'ended', -52, 20],
  ['618 年中大促返场', 'discount', 'ended', -95, 25],
  ['会员日专享 8 折', 'coupon', 'audit', -1, 30]
]

function buildPromos() {
  const rng = createRng(20261605)
  return PROMO_PATTERN.map(([name, type, status, startOffset, duration], i) => {
    const startAt = new Date(NOW.getTime() + startOffset * DAY)
    const endAt = new Date(startAt.getTime() + duration * DAY)
    const budget = [5000, 20000, 50000, 100000, 200000][i % 5]
    // 花费与成交额按状态拉开差距：进行中/已结束才有真实产出
    const spentRatio = status === 'running' ? 0.42 + rng() * 0.5 : status === 'ended' ? 1 : 0
    const cost = Math.round(budget * spentRatio)
    const roi = status === 'pending' || status === 'audit' ? 0 : +(2.4 + rng() * 5.6).toFixed(2)
    const gmv = Math.round(cost * roi)
    const sold = Math.round(gmv / (380 + rng() * 900))
    return {
      id: `PM${2026100500 + 66 - i * 4}`,
      name,
      type,
      status,
      startAt,
      endAt,
      budget,
      cost,
      roi,
      gmv,
      sold,
      joined: randInt(rng, 6, 42),
      // 进行中的活动取 1~3 个主推商品，供卡片展示
      products: Array.from({ length: randInt(rng, 1, 3) }, () => pick(rng, PRODUCT_POOL))
    }
  })
}

export const PROMOS = buildPromos()

/** 推广渠道效果，供看板横向对比 */
export const PROMO_CHANNELS = [
  { name: '直通车', cost: 86400, gmv: 486000, roi: 5.62, click: 128400, convert: 0.0412, tone: 'brand' },
  { name: '引力魔方', cost: 52800, gmv: 264000, roi: 5.0, click: 96200, convert: 0.0326, tone: 'violet' },
  { name: '万相台', cost: 38600, gmv: 178400, roi: 4.62, click: 68200, convert: 0.0284, tone: 'teal' },
  { name: '淘宝客', cost: 24400, gmv: 96400, roi: 3.95, click: 42100, convert: 0.0192, tone: 'amber' },
  { name: '站外投放', cost: 18600, gmv: 62800, roi: 3.38, click: 28400, convert: 0.0146, tone: 'coral' }
]

/** 店铺优惠券 */
export const COUPONS = [
  { id: 'CP01', name: '新人首单券', threshold: 0, amount: 100, total: 20000, taken: 16420, used: 9840, status: 'running', endAt: new Date(NOW.getTime() + 60 * DAY) },
  { id: 'CP02', name: '满 299 减 50', threshold: 299, amount: 50, total: 30000, taken: 21840, used: 12860, status: 'running', endAt: new Date(NOW.getTime() + 45 * DAY) },
  { id: 'CP03', name: '满 999 减 120', threshold: 999, amount: 120, total: 10000, taken: 6820, used: 4104, status: 'running', endAt: new Date(NOW.getTime() + 30 * DAY) },
  { id: 'CP04', name: '满 3999 减 400', threshold: 3999, amount: 400, total: 5000, taken: 3180, used: 2140, status: 'running', endAt: new Date(NOW.getTime() + 20 * DAY) },
  { id: 'CP05', name: '会员日专属 8 折', threshold: 0, amount: 0, total: 8000, taken: 2140, used: 0, status: 'audit', endAt: new Date(NOW.getTime() + 30 * DAY) },
  { id: 'CP06', name: '双十一预售尾款券', threshold: 1999, amount: 200, total: 15000, taken: 4820, used: 0, status: 'pending', endAt: new Date(NOW.getTime() + 40 * DAY) }
]

/* ==========================================================================
   评价
   ========================================================================== */

const REVIEW_TEMPLATES = {
  5: [
    '手机手感非常好，屏幕色彩通透，拍照比上一代提升明显，物流也很快，第二天就收到了。',
    '正品无疑，包装完好，开机激活一切正常。店家还送了钢化膜，态度很好，推荐购买。',
    '性价比很高，性能完全够用，玩游戏很流畅不发热。包装很用心，值得回购。',
    '做工精致，细节到位，续航比想象中好。客服回复也很及时，整体很满意。'
  ],
  4: [
    '整体不错，就是包装盒有一点点挤压的痕迹，机器本身没问题，可以接受。',
    '功能都正常，就是这个颜色比图片上偏暗一些，其他都挺好的。',
    '性能和描述一致，就是发货稍微慢了一点，等了三天才收到。'
  ],
  3: [
    '东西还行，就是赠品没有一起发过来，联系客服补发了，有点影响体验。',
    '一般般吧，期望值有点高，实际用起来跟普通款差别不大。'
  ],
  2: [
    '收到的时候外包装已经破损了，机器边框有轻微磕碰，客服只愿意补偿 50 元。',
    '用了两天就发现有轻微死机情况，正在联系售后处理。'
  ],
  1: [
    '发错颜色了，联系客服半天才回复，体验很差。'
  ]
}

const REVIEW_TAGS = ['质量很好', '物流很快', '正品保障', '包装精美', '客服耐心', '性价比高', '手感不错', '屏幕通透']

/** [评分, 状态] —— 12 条待回复 + 8 条已回复 + 4 条已忽略，含差评与有图 */
const REVIEW_PATTERN = [
  [5, 'wait'], [5, 'wait'], [4, 'wait'], [5, 'wait'], [3, 'wait'], [2, 'wait'],
  [5, 'wait'], [4, 'wait'], [5, 'reply'], [5, 'reply'], [4, 'reply'], [5, 'reply'],
  [1, 'reply'], [5, 'reply'], [4, 'reply'], [5, 'reply'],
  [5, 'ignored'], [4, 'ignored'],
  [5, 'wait'], [5, 'wait'], [3, 'wait'], [2, 'wait'], [5, 'wait'], [4, 'wait']
]

function buildReviews() {
  const rng = createRng(20261705)
  return REVIEW_PATTERN.map(([rating, mode], i) => {
    const status = mode === 'reply' ? 'replied' : mode === 'ignored' ? 'ignored' : 'wait'
    const isBad = rating <= 2
    const isGood = rating >= 4
    const createdAt = new Date(NOW.getTime() - (2 + i * 5.4) * HOUR)
    const replies = [
      '感谢您的支持，我们会继续保持品质与服务，期待您的再次光临！',
      '非常感谢您的认可！后续有任何使用问题都可以随时联系在线客服。',
      '抱歉给您带来不好的体验，已为您安排专属客服跟进，请留意站内信。'
    ]
    return {
      id: `RV${2026100500 + 46 - i * 2}`,
      orderId: `GK${2026100500 + 312 - i * 11}`,
      rating,
      isBad,
      isGood,
      content: REVIEW_TEMPLATES[rating][i % REVIEW_TEMPLATES[rating].length],
      images: rng() > 0.55 ? randInt(rng, 1, 4) : 0,
      tags: isGood ? [REVIEW_TAGS[i % 8], REVIEW_TAGS[(i + 3) % 8]] : [],
      product: pick(rng, PRODUCT_POOL),
      buyer: BUYERS[i % BUYERS.length],
      buyerIndex: i % BUYERS.length,
      sku: pick(rng, ['256G · 原色钛', '16+512G · 黑色', '标准版', '旗舰版']),
      status,
      reply: status === 'replied' ? replies[i % replies.length] : '',
      repliedAt: status === 'replied' ? new Date(createdAt.getTime() + 5 * HOUR) : null,
      helpful: randInt(rng, 0, 86),
      createdAt
    }
  })
}

export const REVIEWS = buildReviews()

/** 店铺 DSR 评分（行业均值为对比基准） */
export const DSR = [
  { key: 'desc', label: '描述相符', score: 4.92, industry: 4.78, delta: 0.146 },
  { key: 'service', label: '服务态度', score: 4.88, industry: 4.81, delta: 0.087 },
  { key: 'logistics', label: '物流服务', score: 4.95, industry: 4.84, delta: 0.113 }
]

/** 商品评价标签统计 */
export const REVIEW_TAG_STATS = REVIEW_TAGS.map((tag, i) => ({
  tag,
  count: [486, 421, 386, 284, 236, 198, 164, 122][i]
}))

/* ==========================================================================
   财务结算
   ========================================================================== */

/** 平台固定费率，改这里即可让所有账期金额联动重算 */
export const FEE_RATE = { commission: 0.05, service: 0.006 }

/**
 * 最近 6 个账期：把日序列的最后 30 天按 5 天一结切开。
 *
 * 成交额直接取自 @/mock/series，因此
 *   「财务结算」的 6 期成交额合计 === 「经营概览」的本月营收
 * 两页数字天然一致，不会出现「概览 379 万、财务 440 万」的打架。
 */
function buildRecentPeriods() {
  const last30 = DAILY_SERIES.slice(-30)
  // 最新的账期排在最前，状态依次推进：待结算 → 结算中 → 已结算
  const meta = [
    { refund: 12400, status: 'pending' },
    { refund: 18600, status: 'settling' },
    { refund: 24200, status: 'settled' },
    { refund: 16800, status: 'settled' },
    { refund: 21400, status: 'settled' },
    { refund: 18600, status: 'settled' }
  ]
  const blocks = []
  for (let i = 0; i < 6; i += 1) {
    const chunk = last30.slice(i * 5, i * 5 + 5)
    blocks.push({
      range: `${mmdd(chunk[0].date)} ~ ${mmdd(chunk[chunk.length - 1].date)}`,
      gmv: sum(chunk, 'amount'),
      refund: meta[i].refund,
      status: meta[i].status
    })
  }
  return blocks.reverse()
}

/** 更早的 6 期历史账期：把列表填满，不参与任何总计 */
const HISTORY_PERIODS = [
  ['08-27 ~ 08-31', 548600, 19800],
  ['08-22 ~ 08-26', 586400, 14200],
  ['08-17 ~ 08-21', 512800, 22600],
  ['08-12 ~ 08-16', 496200, 13400],
  ['08-07 ~ 08-11', 468400, 15800],
  ['08-02 ~ 08-06', 442800, 11200]
].map(([range, gmv, refund]) => ({ range, gmv, refund, status: 'settled' }))

const SETTLE_PATTERN = [...buildRecentPeriods(), ...HISTORY_PERIODS]

function buildSettlements() {
  return SETTLE_PATTERN.map(({ range, gmv, refund, status }, i) => {
    // 佣金与服务费按固定费率从成交额推导，保证任意一行都能手工复核
    const commission = Math.round(gmv * FEE_RATE.commission)
    const service = Math.round(gmv * FEE_RATE.service)
    const settle = gmv - commission - service - refund
    return {
      id: `JS${2026100101 - i * 101}`,
      range,
      gmv,
      commission,
      service,
      refund,
      settle,
      rate: settle / gmv,
      status,
      // 结算单明细的抽样订单数，与账期成交额正相关
      orderCount: Math.round(gmv / 940)
    }
  })
}

export const SETTLEMENTS = buildSettlements()

/** 由结算单推导资金总览，避免各处硬编码导致总额对不上 */
export function buildFundSummary() {
  const current = SETTLEMENTS[0]
  const settling = SETTLEMENTS.filter((s) => s.status === 'settling').reduce((a, s) => a + s.settle, 0)
  const settledAll = SETTLEMENTS.filter((s) => s.status === 'settled').reduce((a, s) => a + s.settle, 0)
  return {
    balance: 386420,
    pending: current.settle,
    settling,
    frozen: 57400,
    settledTotal: settledAll + 4586000,
    current
  }
}

/** 资金流水 */
function buildCashFlow() {
  const rng = createRng(20261805)
  const types = [
    { type: 'settle', label: '结算入账', tone: 'green', direction: 1 },
    { type: 'withdraw', label: '提现到账', tone: 'brand', direction: -1 },
    { type: 'commission', label: '平台佣金', tone: 'coral', direction: -1 },
    { type: 'service', label: '支付服务费', tone: 'amber', direction: -1 },
    { type: 'refund', label: '售后退款扣减', tone: 'coral', direction: -1 },
    { type: 'settle', label: '结算入账', tone: 'green', direction: 1 }
  ]
  return Array.from({ length: 18 }, (_, i) => {
    const t = types[i % types.length]
    const amount = t.type === 'settle' ? randInt(rng, 28000, 88000) : randInt(rng, 380, 26000)
    return {
      id: `CF${2026100500 + 120 - i * 3}`,
      ...t,
      amount: amount * t.direction,
      balance: 386420 + randInt(rng, 0, 42000),
      at: new Date(NOW.getTime() - (i * 7.5 + 1) * HOUR),
      remark: t.type === 'settle' ? `账期 ${SETTLE_PATTERN[i % SETTLE_PATTERN.length].range} 结算` : '—'
    }
  })
}

export const CASH_FLOW = buildCashFlow()

/** 结算单下的抽样订单流水（详情抽屉用） */
export function buildSettleOrders(settlement) {
  const rng = createRng(20261905)
  const count = 6
  return Array.from({ length: count }, (_, i) => {
    const product = PRODUCT_POOL[(i * 3) % PRODUCT_POOL.length]
    const qty = product.price > 3000 ? 1 : randInt(rng, 1, 2)
    const goods = product.price * qty
    return {
      id: `GK${2026100500 + 200 - i * 13}`,
      product,
      qty,
      goods,
      commission: Math.round(goods * FEE_RATE.commission),
      service: Math.round(goods * FEE_RATE.service),
      settle: Math.round(goods * (1 - FEE_RATE.commission - FEE_RATE.service)),
      paidAt: new Date(NOW.getTime() - (i * 9 + 2) * HOUR),
      _seed: settlement.id
    }
  })
}
