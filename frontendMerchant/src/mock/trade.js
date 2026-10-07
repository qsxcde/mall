/**
 * 交易域 Mock：订单 / 发货 / 售后。
 * 三个模块共享同一批商品与买家，保证跨页面对得上号。
 */
import {
  createRng, pick, randInt, NOW,
  PRODUCT_POOL, BUYERS, AREAS, EXPRESS_LIST, WAREHOUSE_LIST, PAY_LIST,
  orderNo, waybillNo, phoneNo
} from './shared'

const HOUR = 3600000

/* ==========================================================================
   订单
   ========================================================================== */

/** 32 笔订单的状态分布，刻意覆盖全部 7 种状态便于演示筛选 */
const ORDER_PATTERN = [
  'wait_ship', 'shipped', 'wait_review', 'done', 'wait_pay', 'shipped', 'done', 'wait_ship',
  'after', 'wait_review', 'done', 'wait_ship', 'shipped', 'done', 'closed', 'wait_pay',
  'wait_ship', 'wait_review', 'done', 'shipped', 'wait_ship', 'done', 'wait_review', 'closed',
  'shipped', 'wait_ship', 'done', 'wait_pay', 'after', 'wait_review', 'done', 'shipped'
]

/** 已付款之后的状态集合，用于推导各时间节点 */
const PAID_SET = ['wait_ship', 'shipped', 'wait_review', 'done', 'after']
const SHIPPED_SET = ['shipped', 'wait_review', 'done', 'after']
const RECEIVED_SET = ['done', 'after']

function buildOrders() {
  const rng = createRng(20261005)
  return ORDER_PATTERN.map((status, i) => {
    const product = pick(rng, PRODUCT_POOL)
    const qty = product.price > 3000 ? 1 : randInt(rng, 1, 3)
    const goodsAmount = product.price * qty
    const shipFee = goodsAmount > 99 ? 0 : 18
    const discount = rng() > 0.55 ? Math.round(goodsAmount * (0.02 + rng() * 0.06)) : 0
    const area = pick(rng, AREAS)

    // 越靠前的订单越新，保证「按时间倒序」时列表顶部是刚发生的交易
    const hoursAgo = i < 6 ? i * 1.6 : 9.6 + (i - 5) * 29
    const createdAt = new Date(NOW.getTime() - hoursAgo * HOUR)

    // 按状态推导各时间节点：未付款的订单不应有付款/发货/签收时间
    const paidAt = PAID_SET.includes(status) ? new Date(createdAt.getTime() + 6 * 60000) : null
    const shipAt = SHIPPED_SET.includes(status) ? new Date(createdAt.getTime() + 4 * HOUR) : null
    const recvAt = RECEIVED_SET.includes(status) ? new Date(createdAt.getTime() + 52 * HOUR) : null

    return {
      id: orderNo(i),
      status,
      product,
      qty,
      goodsAmount,
      shipFee,
      discount,
      payAmount: goodsAmount + shipFee - discount,
      buyer: pick(rng, BUYERS),
      buyerIndex: i % BUYERS.length,
      payWay: pick(rng, PAY_LIST),
      express: pick(rng, EXPRESS_LIST),
      waybill: waybillNo(i),
      warehouse: pick(rng, WAREHOUSE_LIST),
      phone: phoneNo(i),
      address: `${area.province} ${area.detail}`,
      note: rng() > 0.78 ? '买家要求工作日送达，放前台即可' : '',
      createdAt,
      paidAt,
      shipAt,
      recvAt
    }
  })
}

export const ORDERS = buildOrders()

/* ==========================================================================
   发货单
   ========================================================================== */

/** [状态, 付款距基准点的小时数] */
const SHIP_PATTERN = [
  ['wait', 26], ['wait', 22], ['wait', 20.5], ['wait', 18],
  ['wait', 12], ['wait', 8], ['wait', 5], ['wait', 3], ['wait', 1.5],
  ['printed', 30], ['printed', 24], ['printed', 7], ['printed', 4],
  ['shipped', 20], ['shipped', 16], ['shipped', 11], ['shipped', 6], ['shipped', 2],
  ['exc', 34], ['exc', 28]
]

const EXC_REASONS = [
  '收件人电话无人接听，派送失败',
  '地址不详，快递网点暂存待确认',
  '包裹运输途中破损，已返回站点'
]

function buildShipments() {
  const rng = createRng(20261105)
  return SHIP_PATTERN.map(([status, hoursAgo], i) => {
    const product = pick(rng, PRODUCT_POOL)
    const area = pick(rng, AREAS)
    const paidAt = new Date(NOW.getTime() - hoursAgo * HOUR)
    const qty = product.price > 3000 ? 1 : randInt(rng, 1, 2)

    const shipment = {
      id: orderNo(i + 40),
      status,
      product,
      qty,
      amount: product.price * qty,
      buyer: pick(rng, BUYERS),
      buyerIndex: i % BUYERS.length,
      phone: phoneNo(i + 40),
      area,
      warehouse: WAREHOUSE_LIST[i % WAREHOUSE_LIST.length],
      express: EXPRESS_LIST[i % EXPRESS_LIST.length],
      waybill: '',
      paidAt,
      // 24 小时发货承诺
      deadline: new Date(paidAt.getTime() + 24 * HOUR),
      printedAt: null,
      shippedAt: null,
      excReason: ''
    }

    if (status !== 'wait') {
      shipment.waybill = waybillNo(i + 40)
      shipment.printedAt = new Date(paidAt.getTime() + 2 * HOUR)
    }
    if (status === 'shipped' || status === 'exc') {
      shipment.shippedAt = new Date(paidAt.getTime() + 4 * HOUR)
    }
    if (status === 'exc') {
      shipment.excReason = EXC_REASONS[i % EXC_REASONS.length]
    }
    return shipment
  })
}

export const SHIPMENTS = buildShipments()

/** 今日已打单量（含历史累计，用于打单台统计） */
export const PRINTED_TODAY_BASE = 118

/* ==========================================================================
   售后工单
   ========================================================================== */

/** [状态, 类型, 申请距基准点小时, 处理时限小时] */
const AFTER_PATTERN = [
  ['pending', 'refund', 2, 48],
  ['pending', 'return', 5, 48],
  ['pending', 'exchange', 9, 48],
  ['pending', 'refund', 14, 48],
  ['pending', 'return', 20, 48],
  ['pending', 'repair', 26, 48],
  ['wait_return', 'return', 30, 168],
  ['wait_return', 'return', 42, 168],
  ['wait_return', 'exchange', 55, 168],
  ['wait_receive', 'return', 70, 168],
  ['wait_receive', 'return', 88, 168],
  ['done', 'refund', 102, 0],
  ['done', 'return', 126, 0],
  ['done', 'exchange', 150, 0],
  ['done', 'repair', 174, 0],
  ['rejected', 'refund', 196, 0]
]

const AFTER_REASONS = {
  refund: [
    '商品与描述不符，边框有磕碰划痕',
    '收到商品后 7 天内降价，申请价保',
    '不喜欢/不想要了，商品未拆封',
    '下错单了，希望取消并退款'
  ],
  return: [
    '商品存在质量问题，充电口接触不良',
    '发错货，收到的颜色与订单不符',
    '屏幕有亮点，要求退货退款',
    '开机后频繁死机，无法正常使用'
  ],
  exchange: [
    '尺寸不合适，希望更换其他型号',
    '颜色与预期不符，申请换色',
    '包装破损，希望更换全新商品'
  ],
  repair: [
    '耳机左耳无声，需要检测维修',
    '笔记本键盘失灵，申请保内维修',
    '充电器不充电，需要返修'
  ]
}

/** 按状态推导售后进度节点，视图层直接渲染 el-timeline */
function buildTimeline(status, applyAt) {
  const at = (h) => new Date(applyAt.getTime() + h * HOUR)
  const base = [{ title: '买家提交申请', time: applyAt, done: true }]

  if (status === 'pending') {
    return [...base, { title: '商家处理', time: null, done: false, tip: '待响应' }]
  }
  if (status === 'rejected') {
    return [
      ...base,
      { title: '商家拒绝申请', time: at(6), done: true },
      { title: '买家可申请平台介入', time: null, done: false, tip: '待买家决定' }
    ]
  }
  const withAgree = [...base, { title: '商家同意售后', time: at(6), done: true }]

  if (status === 'wait_return') {
    return [...withAgree, { title: '买家寄回商品', time: null, done: false, tip: '待买家寄回' }]
  }
  const withReturn = [...withAgree, { title: '买家已寄回商品', time: at(30), done: true }]

  if (status === 'wait_receive') {
    return [...withReturn, { title: '商家确认收货', time: null, done: false, tip: '待商家收货' }]
  }
  return [
    ...withReturn,
    { title: '商家确认收货', time: at(48), done: true },
    { title: '退款已完成', time: at(50), done: true }
  ]
}

function buildAftersales() {
  const rng = createRng(20261205)
  return AFTER_PATTERN.map(([status, type, hoursAgo, limit], i) => {
    const product = pick(rng, PRODUCT_POOL)
    const qty = product.price > 3000 ? 1 : randInt(rng, 1, 2)
    const orderAmount = product.price * qty
    const applyAt = new Date(NOW.getTime() - hoursAgo * HOUR)
    // 仅退款可部分退，其余按订单金额全退
    const refundAmount = type === 'refund' && rng() > 0.6
      ? Math.round(orderAmount * (0.3 + rng() * 0.5))
      : product.price * qty

    const reasons = AFTER_REASONS[type]
    return {
      id: `AS${2026100500 + 88 - i * 3}`,
      orderId: orderNo((i + 3) % 32),
      type,
      status,
      reason: reasons[i % reasons.length],
      buyer: pick(rng, BUYERS),
      buyerIndex: (i + 4) % BUYERS.length,
      product,
      qty,
      orderAmount,
      refundAmount,
      applyAt,
      deadline: limit ? new Date(applyAt.getTime() + limit * HOUR) : null,
      // 退货场景才有寄回单号
      returnExpress: status === 'wait_receive' || status === 'done' ? pick(rng, EXPRESS_LIST) : '',
      returnWaybill: status === 'wait_receive' || status === 'done' ? `SF${2000000000 + i * 7777}` : '',
      rejectReason: status === 'rejected' ? '超过 7 天无理由退货期限，且商品已明显使用' : '',
      images: randInt(rng, 0, 4),
      phone: phoneNo(i + 60),
      timeline: buildTimeline(status, applyAt)
    }
  })
}

export const AFTERSALES = buildAftersales()
