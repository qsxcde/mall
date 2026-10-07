/**
 * 业务字典。
 *
 * 统一约定：每个枚举项为 { text, tone }
 *   text —— 展示文案
 *   tone —— 色调，由 <StatusTag> 映射到设计令牌
 *           brand | green | teal | amber | coral | violet | neutral
 *
 * 这样视图层只认 tone，不再关心具体颜色值，改配色只动一处。
 */

/** 订单状态 */
export const ORDER_STATUS = {
  wait_pay: { text: '待付款', tone: 'amber', hint: '等待买家付款，超时将自动关闭订单' },
  wait_ship: { text: '待发货', tone: 'brand', hint: '买家已付款，请在 24 小时内完成发货' },
  shipped: { text: '待收货', tone: 'teal', hint: '商家已发货，包裹运输途中' },
  wait_review: { text: '待评价', tone: 'green', hint: '买家已签收，等待发表评价' },
  done: { text: '已完成', tone: 'neutral', hint: '交易已完成，货款已结算至账户余额' },
  closed: { text: '已取消', tone: 'neutral', hint: '订单已关闭，占用库存已回滚' },
  after: { text: '售后中', tone: 'coral', hint: '买家发起售后申请，请在 48 小时内响应' }
}

/** 订单 Tab 定义（顺序即展示顺序） */
export const ORDER_TABS = [
  { key: 'all', label: '全部订单' },
  { key: 'wait_pay', label: '待付款' },
  { key: 'wait_ship', label: '待发货' },
  { key: 'shipped', label: '待收货' },
  { key: 'wait_review', label: '待评价' },
  { key: 'done', label: '已完成' },
  { key: 'closed', label: '已取消' },
  { key: 'after', label: '售后中' }
]

/** 商品状态 */
export const PRODUCT_STATUS = {
  on: { text: '在售', tone: 'green' },
  ware: { text: '仓库中', tone: 'amber' },
  audit: { text: '审核中', tone: 'brand' },
  sold: { text: '售罄', tone: 'coral' },
  off: { text: '已下架', tone: 'neutral' },
  trash: { text: '回收站', tone: 'neutral' }
}

export const PRODUCT_TABS = [
  { key: 'all', label: '全部商品' },
  { key: 'on', label: '在售' },
  { key: 'ware', label: '仓库中' },
  { key: 'audit', label: '审核中' },
  { key: 'sold', label: '售罄' },
  { key: 'off', label: '已下架' },
  { key: 'trash', label: '回收站' }
]

export const PRODUCT_CATS = ['手机通讯', '电脑办公', '智能穿戴', '影音娱乐', '数码配件', '智能家居']

/** 发货状态 */
export const SHIPPING_STATUS = {
  wait: { text: '待打单', tone: 'amber' },
  printed: { text: '已打单', tone: 'brand' },
  shipped: { text: '已发货', tone: 'teal' },
  exc: { text: '物流异常', tone: 'coral' }
}

export const SHIPPING_TABS = [
  { key: 'all', label: '全部' },
  { key: 'late', label: '超时预警' },
  { key: 'printed', label: '已打单' },
  { key: 'shipped', label: '今日已发' },
  { key: 'exc', label: '物流异常' }
]

/**
 * 售后状态。
 *
 * 注意 canceled（买家撤销）与 rejected（商家拒绝）是两种不同的终结方式，不能合并：
 * 前者是买家主动撤回，后者是商家驳回并可能引发平台介入，展示文案与话术都不同。
 */
export const AFTERSALE_STATUS = {
  pending: { text: '待处理', tone: 'amber', hint: '请在 48 小时内响应，超时将自动同意退款' },
  wait_return: { text: '待买家退货', tone: 'brand', hint: '已同意退货，等待买家寄回商品' },
  wait_receive: { text: '待商家收货', tone: 'teal', hint: '买家已寄回，请确认收货后完成退款' },
  done: { text: '已完成', tone: 'green', hint: '退款已原路退回买家账户' },
  rejected: { text: '已拒绝', tone: 'neutral', hint: '售后申请已被拒绝，买家可申请平台介入' },
  canceled: { text: '买家已撤销', tone: 'neutral', hint: '买家主动撤回了申请，无需商家处理' }
}

export const AFTERSALE_TABS = [
  { key: 'all', label: '全部工单' },
  { key: 'pending', label: '待处理' },
  { key: 'wait_return', label: '待买家退货' },
  { key: 'wait_receive', label: '待商家收货' },
  { key: 'done', label: '已完成' },
  { key: 'rejected', label: '已拒绝' },
  { key: 'canceled', label: '买家已撤销' }
]

/** 售后类型 */
export const AFTERSALE_TYPE = {
  refund: { text: '仅退款', tone: 'coral' },
  return: { text: '退货退款', tone: 'amber' },
  exchange: { text: '换货', tone: 'brand' },
  repair: { text: '维修', tone: 'violet' }
}

/** 结算单状态 */
export const SETTLE_STATUS = {
  settled: { text: '已结算', tone: 'green' },
  settling: { text: '结算中', tone: 'brand' },
  pending: { text: '待结算', tone: 'amber' }
}

/** 营销活动类型 */
export const PROMO_TYPES = {
  discount: { text: '限时折扣', tone: 'coral' },
  coupon: { text: '店铺优惠券', tone: 'brand' },
  seckill: { text: '限时秒杀', tone: 'amber' },
  group: { text: '拼团', tone: 'violet' },
  bundle: { text: '套装优惠', tone: 'teal' },
  gift: { text: '满赠', tone: 'green' }
}

/** 营销活动状态 */
export const PROMO_STATUS = {
  running: { text: '进行中', tone: 'green' },
  pending: { text: '待开始', tone: 'brand' },
  paused: { text: '已暂停', tone: 'amber' },
  ended: { text: '已结束', tone: 'neutral' },
  audit: { text: '审核中', tone: 'violet' }
}

export const PROMO_TABS = [
  { key: 'all', label: '全部活动' },
  { key: 'running', label: '进行中' },
  { key: 'pending', label: '待开始' },
  { key: 'audit', label: '审核中' },
  { key: 'paused', label: '已暂停' },
  { key: 'ended', label: '已结束' }
]

/** 评价状态 */
export const REVIEW_STATUS = {
  wait: { text: '待回复', tone: 'amber' },
  replied: { text: '已回复', tone: 'green' },
  ignored: { text: '已忽略', tone: 'neutral' }
}

export const REVIEW_TABS = [
  { key: 'all', label: '全部评价' },
  { key: 'wait', label: '待回复' },
  { key: 'replied', label: '已回复' },
  { key: 'bad', label: '差评' },
  { key: 'good', label: '好评' },
  { key: 'pic', label: '有图' }
]

/** 承运商 */
export const EXPRESS_LIST = ['顺丰速运', '京东物流', '中通快递', '圆通速递', '德邦快递']

/** 仓库 */
export const WAREHOUSE_LIST = ['深圳总仓', '杭州仓', '北京仓']

/**
 * 支付方式。
 *
 * 与后端一致：接口参数与数据里存的是英文码，展示才用中文文案。
 * 筛选下拉必须传码，否则后端按 pay_method 精确匹配时会永远查不到数据。
 */
export const PAY_WAY = {
  wechat: '微信支付',
  alipay: '支付宝',
  card: '银行卡',
  balance: '账户余额'
}

/** 支付方式下拉选项（value 为码，label 为文案） */
export const PAY_OPTIONS = Object.entries(PAY_WAY).map(([value, label]) => ({ value, label }))

/** 买家头像渐变色，按索引取模轮换（与 UserAvatar 的 tone-* 样式对应） */
export const AVATAR_TONES = ['brand', 'coral', 'teal', 'gold', 'violet']

/**
 * 取字典项的兜底：未知 key 返回灰色占位，避免视图里到处判空。
 */
export function pick(dict, key) {
  return dict[key] || { text: key || '—', tone: 'neutral' }
}
