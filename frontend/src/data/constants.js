/**
 * 纯前端 UI 常量（不来自后端）。
 *
 * 与 shop.js 的区别：shop.js 是「后端应当提供的数据」，这些是「展示规则」，
 * 例如状态到样式的映射、表单选项、页脚导航，理应留在前端。
 */

/**
 * 客服热线。
 *
 * 本站没有在线客服系统（后端也无对应接口），所有「联系客服」的入口统一导向这串号码，
 * 不允许弹「已接入在线客服」这类无法兑现的假提示。
 */
export const SERVICE_HOTLINE = '400-888-8888'
export const SERVICE_HOURS = '9:00-21:00'

/** 订单状态：后端返回数字码，前端负责映射到样式与 Tab */
export const ORDER_STATUS = {
  0: { key: 'pay', text: '待付款', cls: 'wait' },
  1: { key: 'ship', text: '待发货', cls: 'ship' },
  2: { key: 'recv', text: '待收货', cls: 'recv' },
  3: { key: 'cmt', text: '待评价', cls: 'cmt' },
  4: { key: 'done', text: '已完成', cls: 'done' },
  5: { key: 'cancel', text: '已取消', cls: 'cancel' }
}

/** 状态 key → { text, cls } 的兼容映射，供既有模板直接使用 */
export const orderStatusMap = Object.fromEntries(
  Object.values(ORDER_STATUS).map((s) => [s.key, { text: s.text, cls: s.cls }])
)

/** 订单列表顶部状态 Tab */
export const ORDER_TABS = [
  { key: 'all', name: '全部订单' },
  { key: 'pay', name: '待付款' },
  { key: 'ship', name: '待发货' },
  { key: 'recv', name: '待收货' },
  { key: 'cmt', name: '待评价' }
]

/** 订单状态 Tab key ↔ 后端状态码 */
export const STATUS_CODE_BY_KEY = { pay: 0, ship: 1, recv: 2, cmt: 3, done: 4, cancel: 5 }

/** 售后申请服务类型 */
export const afterSaleTypes = [
  { key: 'refund', name: '仅退款', desc: '未收到货 / 无需退货' },
  { key: 'return', name: '退货退款', desc: '已收到货，需寄回' },
  { key: 'exchange', name: '换货', desc: '质量问题换同款' },
  { key: 'repair', name: '维修', desc: '保修期内免费维修' }
]

export const afterSaleReasons = [
  '7 天无理由退货',
  '质量问题',
  '描述不符',
  '收到商品损坏',
  '发错货',
  '不想要了',
  '其他原因'
]

/** 领券中心券类型 */
export const couponCats = [
  { key: 'all', name: '全部' },
  { key: 'full', name: '满减券' },
  { key: 'percent', name: '折扣券' },
  { key: 'shipping', name: '免运费券' }
]

/** 消息中心类型 */
export const messageTypes = [
  { key: 'all', name: '全部' },
  { key: 'order', name: '订单' },
  { key: 'logistics', name: '物流' },
  { key: 'coupon', name: '优惠' },
  { key: 'system', name: '系统' }
]

export const messageIcon = { order: '📦', logistics: '🚚', coupon: '🎫', system: '🔔' }

/** 评价维度 */
export const reviewDimensions = [
  { key: 'desc', label: '描述相符' },
  { key: 'logistics', label: '物流服务' },
  { key: 'service', label: '服务态度' }
]

/** 会员权益图标（按后端返回顺序取用） */
export const benefitIcons = ['🎫', '🎂', '🚚', '🛠️', '⚡', '💎', '🎁', '📞']

/** 页脚导航 */
export const footerCols = [
  {
    title: '购物指南',
    links: [
      { text: '搜索商品', to: { name: 'search' } },
      { text: '商品分类', to: { name: 'category' } },
      { text: '新品首发', to: { name: 'newproduct' } },
      { text: '限时秒杀', to: { name: 'seckill' } }
    ]
  },
  {
    title: '服务支持',
    links: [
      { text: '帮助中心', to: { name: 'help' } },
      { text: '退换货政策', to: { name: 'policy' } },
      { text: '我的订单', to: { name: 'orders' } },
      { text: '领券中心', to: { name: 'coupon' } }
    ]
  },
  {
    title: '会员中心',
    links: [
      { text: '会员权益', to: { name: 'member' } },
      { text: '个人中心', to: { name: 'user' } },
      { text: '购物车', to: { name: 'cart' } }
    ]
  },
  {
    title: '关于极客',
    links: [
      { text: '关于我们', to: { name: 'about' } },
      { text: '联系客服', to: { name: 'help' } },
      { text: '加入我们', to: { name: 'about' } }
    ]
  }
]

/** 首页营销位（纯展示，不属于业务数据） */
export const homeBanners = [
  { title: '数码焕新季', desc: '新品首发 12 期免息 · 全场好物低价购', c: 'c1' },
  { title: '以旧换新', desc: '旧机最高补贴 2000 元 · 上门回收', c: 'c2' },
  { title: '会员日特惠', desc: '每月 18 号 · 专属折扣叠加优惠券', c: 'c3' },
  { title: '极速配送', desc: '当日发 · 次日达 · 顺丰包邮', c: 'c4' }
]

export const homeEntryLinks = [
  { icon: '⚡', name: '限时秒杀', c: 'c1', to: { name: 'seckill' } },
  { icon: '🆕', name: '新品首发', c: 'c3', to: { name: 'newproduct' } },
  { icon: '🎫', name: '领券中心', c: 'c4', to: { name: 'coupon' } },
  { icon: '💳', name: '分期免息', c: 'c5', to: { name: 'category', query: { cat: 'phone' } } },
  { icon: '🛠️', name: '延保服务', c: 'c6', to: { name: 'help' } },
  { icon: '🔧', name: '上门维修', c: 'c2', to: { name: 'help' } },
  { icon: '📱', name: 'App 下载', c: 'c1', to: { name: 'about' } }
]
