import { ORDER_STATUS } from '@/data/constants'

/**
 * 后端 VO → 前端既有数据形状的适配层。
 *
 * 为什么要这一层：
 * 1. 后端字段是「业务视角」（productId / payAmount / status 数字码），
 *    视图需要的是「展示视角」（product / price / status key + 样式）。
 * 2. 后端返回真实图片 URL，而演示站用 c1~c6 渐变色占位，
 *    在这里统一折算，视图无需关心。
 * 3. 字段名与类型（字符串数字 → Number）集中归一，避免散落在各页面里做转换。
 */

const PLACEHOLDER_CLASSES = ['c1', 'c2', 'c3', 'c4', 'c5', 'c6']

/** 用商品 ID 稳定地挑一个占位配色，保证同一商品每次渲染颜色一致 */
export const placeholderClass = (id) => {
  const n = Math.abs(Number(id) || 0)
  return PLACEHOLDER_CLASSES[n % PLACEHOLDER_CLASSES.length]
}

const num = (value, fallback = 0) => {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

/** 商品卡片 / 详情 */
export function toProduct(vo) {
  if (!vo) return null
  const id = vo.id
  return {
    id,
    title: vo.title || '',
    price: num(vo.price),
    oldPrice: vo.oldPrice == null ? null : num(vo.oldPrice),
    spec: vo.spec || '',
    sales: num(vo.sales),
    rating: num(vo.rating, 5),
    brand: vo.brand || '',
    cat: vo.cat || '',
    tags: Array.isArray(vo.tags) ? vo.tags : [],
    c: placeholderClass(id),
    hot: !!vo.hot,
    isNew: !!vo.isNew,
    // 详情字段
    images: vo.images || [],
    description: vo.description || '',
    stock: num(vo.stock),
    categoryKey: vo.categoryKey || '',
    parentKey: vo.parentKey || ''
  }
}

export const toProducts = (list) => (Array.isArray(list) ? list.map(toProduct) : [])

/** 分类（树形展平为一层使用场景保留 children） */
export function toCategory(vo) {
  if (!vo) return null
  return {
    id: vo.id,
    key: vo.categoryKey,
    name: vo.name,
    desc: vo.description || '',
    icon: vo.icon || '📦',
    children: Array.isArray(vo.children) ? vo.children.map(toCategory) : []
  }
}

export const toCategories = (list) => (Array.isArray(list) ? list.map(toCategory) : [])

/** 分页结果：{ list, total, page, pageSize } */
export function toPage(pageResult, mapper) {
  const list = Array.isArray(pageResult?.list) ? pageResult.list : []
  return {
    list: mapper ? list.map(mapper) : list,
    total: num(pageResult?.total),
    page: num(pageResult?.page, 1),
    pageSize: num(pageResult?.pageSize, 8)
  }
}

/** 订单明细 */
export function toOrderItem(vo) {
  return {
    productId: vo.productId,
    title: vo.title || '',
    spec: vo.spec || '',
    price: num(vo.price),
    qty: num(vo.qty, 1),
    amount: num(vo.amount),
    c: placeholderClass(vo.productId)
  }
}

/** 订单（列表 / 详情共用），并补齐前端展示所需的 status key 与样式 */
export function toOrder(vo) {
  if (!vo) return null
  const meta = ORDER_STATUS[vo.status] || { key: 'unknown', text: '', cls: '' }
  const items = Array.isArray(vo.items) ? vo.items.map(toOrderItem) : []
  return {
    no: vo.no,
    statusCode: vo.status,
    status: meta.key,
    statusText: vo.statusText || meta.text,
    statusCls: meta.cls,
    goodsAmount: num(vo.goodsAmount),
    shippingFee: num(vo.shippingFee),
    discount: num(vo.discount),
    payAmount: num(vo.payAmount),
    totalQty: num(vo.totalQty, 1),
    createTime: vo.createTime || '',
    items,
    // 列表页扁平字段：兼容既有模板（order.product / order.spec / order.price）
    pid: vo.productId,
    spec: vo.spec || '',
    price: num(vo.price),
    qty: num(vo.qty, 1),
    product: vo.productId
      ? toProduct({ id: vo.productId, title: vo.productTitle, spec: vo.spec })
      : null,
    // 按钮可用性由后端状态机给出，前端不再自己实现规则
    canCancel: !!vo.canCancel,
    canPay: !!vo.canPay,
    canConfirm: !!vo.canConfirm,
    canReview: !!vo.canReview,
    canAfterSale: !!vo.canAfterSale
  }
}

/** 订单详情 */
export function toOrderDetail(vo) {
  if (!vo) return null
  return {
    order: toOrder(vo.order),
    receiver: {
      name: vo.receiverName || '',
      phone: vo.receiverPhone || '',
      region: '',
      detail: vo.receiverAddress || ''
    },
    payMethod: vo.payMethod || '',
    payMethodText: PAY_METHOD_TEXT[vo.payMethod] || vo.payMethod || '',
    payTime: vo.payTime || '',
    tradeNo: vo.tradeNo || '',
    remark: vo.remark || '',
    cancelReason: vo.cancelReason || '',
    cancelTime: vo.cancelTime || '',
    expireSecondsLeft: num(vo.expireSecondsLeft),
    timeline: toTimeline(vo.timeline),
    logistics: toLogistics(vo.logistics)
  }
}

const PAY_METHOD_TEXT = {
  wechat: '微信支付',
  alipay: '支付宝',
  card: '银行卡',
  balance: '账户余额'
}

/** 时间轴 / 物流轨迹 */
export function toTimeline(list) {
  if (!Array.isArray(list)) return []
  return list.map((s) => ({
    text: s.text || '',
    time: s.time || '',
    done: !!s.done
  }))
}

export function toLogistics(vo) {
  if (!vo) return null
  return {
    company: vo.company || '',
    no: vo.no || '',
    phone: vo.phone || '',
    steps: toTimeline(vo.steps)
  }
}

/** 优惠券：领券中心模板 */
export function toCouponTemplate(vo) {
  const unit = vo.unit || '¥'
  return {
    id: vo.id,
    type: vo.type,
    amount: num(vo.amount),
    unit,
    cond: num(vo.threshold) > 0 ? `满 ${num(vo.threshold)} 可用` : '无门槛',
    name: vo.name || '',
    scope: vo.scope || '',
    date: vo.validTo ? `有效期至 ${String(vo.validTo).slice(0, 10)}` : '长期有效',
    percent: num(vo.percent),
    total: num(vo.total),
    stock: num(vo.stock),
    limited: !!vo.limited,
    soldout: !!vo.soldout,
    claimed: !!vo.claimed
  }
}

/** 优惠券：我的券 */
export function toUserCoupon(vo) {
  const unit = vo.unit || '¥'
  return {
    id: vo.id,
    type: vo.type,
    amount: num(vo.amount),
    unit,
    cond: num(vo.threshold) > 0 ? `满 ${num(vo.threshold)} 可用` : '无门槛',
    name: vo.name || '',
    desc: vo.validTo ? `有效期至 ${String(vo.validTo).slice(0, 10)}` : '长期有效',
    status: num(vo.status),
    statusText: vo.statusText || '',
    usable: vo.usable !== false,
    unusableReason: vo.unusableReason || ''
  }
}

/** 秒杀场次 / 商品 */
export function toSeckillSession(vo) {
  return {
    id: vo.id,
    time: vo.time,
    label: vo.label || vo.time,
    state: vo.state || 'wait',
    done: vo.state === 'done'
  }
}

export function toSeckillItem(vo) {
  return {
    id: vo.id,
    sessionId: vo.sessionId,
    pid: vo.productId,
    product: toProduct({ id: vo.productId, title: vo.title, spec: vo.spec }),
    price: num(vo.seckillPrice),
    old: num(vo.oldPrice),
    oldPrice: num(vo.oldPrice),
    stock: num(vo.stock),
    total: num(vo.total),
    sold: num(vo.sold),
    percent: num(vo.percent),
    tip: vo.tip || '',
    notStart: !!vo.notStart,
    soldout: !!vo.soldout
  }
}

/**
 * 秒杀抢购结果（削峰模式下轮询用）。
 *
 * 后端 SeckillGrabResult：{ requestId, userId, status, orderNo, message, finished }
 * status 为 QUEUED / SUCCESS / FAILED。前端只关心「是否终态」与「终态是哪种」，
 * 这里一并归一，避免视图里散落字符串比较。
 */
export function toSeckillGrabResult(vo) {
  // 缺省按 QUEUED 处理：宁可持续轮询到超时兜底，也不要把未知状态误判成失败
  const status = String(vo?.status || 'QUEUED').toUpperCase()
  return {
    requestId: vo?.requestId || '',
    status,
    orderNo: vo?.orderNo || '',
    message: vo?.message || '',
    // 终态判定以 status 为准，不依赖后端可能缺省的 finished 字段
    finished: status === 'SUCCESS' || status === 'FAILED'
  }
}

/** 积分商品 */
export function toPointsGoods(vo) {
  return {
    id: vo.id,
    name: vo.name || '',
    points: num(vo.points),
    icon: vo.icon || '🎁',
    c: placeholderClass(vo.id),
    desc: vo.description || '',
    category: vo.category || '',
    stock: num(vo.stock),
    affordable: vo.affordable !== false,
    soldout: !!vo.soldout
  }
}

/** 站内消息 */
export function toMessage(vo) {
  return {
    id: vo.id,
    type: vo.type,
    title: vo.title || '',
    desc: vo.desc || '',
    time: vo.time || '',
    read: !!vo.read,
    link: vo.link || ''
  }
}

/** 评价 */
export function toReview(vo) {
  const nickname = vo.nickname || '匿名用户'
  const content = vo.content || ''
  const time = vo.time || vo.createTime || ''
  return {
    id: vo.id,
    pid: vo.productId,
    product: toProduct({ id: vo.productId, title: vo.productTitle }),
    orderNo: vo.orderNo || '',
    rate: num(vo.avgScore, 5),
    scoreDesc: num(vo.scoreDesc, 5),
    scoreLogistics: num(vo.scoreLogistics, 5),
    scoreService: num(vo.scoreService, 5),
    content,
    images: vo.images || [],
    nickname,
    anonymous: !!vo.anonymous,
    reply: vo.reply || '',
    time,
    // 兼容商品详情页评价块的展示字段
    av: nickname.slice(0, 1),
    name: nickname,
    text: content,
    meta: time
  }
}

/**
 * 售后。
 *
 * status 统一成语义 key（doing / done / canceled），与订单列表保持一致的用法：
 * 模板侧只关心「是哪个状态」，数字码放在 statusCode 里备查。
 */
export function toAfterSale(vo) {
  const code = num(vo.status)
  const key = code === 0 ? 'doing' : code === 1 ? 'done' : 'canceled'
  const typeName = vo.typeName || ''
  const reason = vo.reason || ''
  return {
    id: vo.id,
    orderNo: vo.orderNo,
    name: vo.productTitle || '商品',
    status: key,
    statusCode: code,
    statusText: vo.statusText || '',
    c: placeholderClass(vo.orderNo),
    type: vo.type,
    typeName,
    reason,
    // 列表摘要：类型 + 原因
    desc: [typeName, reason].filter(Boolean).join(' · '),
    content: vo.content || '',
    images: vo.images || [],
    phone: vo.phone || '',
    amount: num(vo.amount),
    applyTime: vo.createTime || '',
    steps: toTimeline(vo.steps)
  }
}

/** 购物车项：id 保持「商品 ID」语义，另存 cartItemId 用于服务端操作 */
export function toCartItem(vo) {
  return {
    id: vo.productId,
    cartItemId: vo.id,
    productId: vo.productId,
    title: vo.title || '',
    price: num(vo.price),
    spec: vo.spec || '',
    c: placeholderClass(vo.productId),
    tag: '',
    qty: num(vo.qty, 1),
    checked: !!vo.checked,
    amount: num(vo.amount)
  }
}

/** 收货地址：region 合并省市区，兼容既有模板 */
export function toAddress(vo) {
  const region = [vo.province, vo.city, vo.district].filter(Boolean).join(' ')
  return {
    id: vo.id,
    name: vo.name || '',
    phone: vo.phone || '',
    province: vo.province || '',
    city: vo.city || '',
    district: vo.district || '',
    region,
    detail: vo.detail || '',
    fullAddress: vo.fullAddress || `${region} ${vo.detail || ''}`.trim(),
    isDefault: vo.isDefault === 1 || vo.isDefault === true
  }
}

/** 用户资料：补齐前端默认字段，避免模板里出现 undefined */
export function toUserProfile(vo) {
  if (!vo) return null
  return {
    id: vo.id,
    account: vo.username || '',
    nickname: vo.nickname || '极客用户',
    avatar: vo.avatar || '',
    realName: '',
    gender: vo.gender === 1 ? '男' : vo.gender === 2 ? '女' : '保密',
    genderCode: num(vo.gender),
    birthday: vo.birthday || '',
    phone: vo.phone || '',
    email: vo.email || '',
    bio: vo.bio || '',
    level: vo.levelId ? `Lv.${vo.levelId} 会员` : '普通会员',
    levelId: vo.levelId,
    points: num(vo.points),
    growth: num(vo.growth)
  }
}
