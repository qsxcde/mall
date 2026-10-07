/**
 * 商品域 Mock：商品列表 / 经营概览 / 数据看板。
 * 「钱」相关的指标一律从 @/mock/series 聚合而来，保证与财务结算对得上。
 */
import { createRng, NOW } from './shared'
import { DAILY_SERIES, chainRatio, lastDays, prevDays, sum } from './series'

const DAY = 86400000

/* ==========================================================================
   商品
   ========================================================================== */

/** [分类, 品牌, 售价, 划线价, 成本, 库存, 安全库存, 销量, 浏览量, 缩略图, 短标, 状态] */
const EXTRA_PRODUCTS = [
  ['手机通讯', 'Apple', 8999, 9999, 7600, 126, 50, 1284, 38420, 'th1', 'iPhone', 'on'],
  ['手机通讯', 'Apple', 5999, 6799, 5100, 88, 40, 962, 26130, 'th6', 'iPhone', 'on'],
  ['手机通讯', '小米', 6499, 6999, 5500, 12, 30, 741, 21890, 'th2', 'MI', 'on'],
  ['手机通讯', '华为', 6499, 7199, 5600, 0, 25, 628, 19420, 'th3', 'Mate', 'sold'],
  ['手机通讯', '三星', 9699, 10499, 8300, 34, 20, 318, 12040, 'th5', 'SAMSUNG', 'on'],
  ['电脑办公', '华为', 12999, 14999, 11200, 3, 20, 216, 15380, 'th3', 'Mate', 'on'],
  ['电脑办公', 'Apple', 10999, 11999, 9400, 47, 20, 402, 22140, 'th1', 'Mac', 'on'],
  ['电脑办公', '联想', 11999, 13999, 10200, 18, 15, 156, 9870, 'th6', 'ThinkPad', 'on'],
  ['电脑办公', '明基', 6999, 7999, 5900, 42, 15, 188, 12460, 'th6', 'BenQ', 'on'],
  ['数码配件', '三星', 1199, 1499, 900, 260, 60, 1462, 31570, 'th3', 'SSD', 'on'],
  ['数码配件', '罗技', 699, 799, 480, 310, 80, 2140, 42880, 'th1', 'Logi', 'on'],
  ['影音娱乐', 'Apple', 1899, 2099, 1450, 96, 40, 1832, 40260, 'th2', 'AirPods', 'on'],
  ['影音娱乐', '索尼', 2999, 3299, 2300, 7, 30, 864, 24310, 'th5', 'SONY', 'on'],
  ['影音娱乐', '森海塞尔', 2499, 2799, 1900, 54, 25, 372, 13020, 'th4', 'SENN', 'on'],
  ['智能家居', '戴森', 4990, 5590, 3900, 15, 20, 486, 16790, 'th4', 'DYSON', 'on'],
  ['智能家居', '石头', 4599, 5099, 3600, 63, 25, 528, 18940, 'th3', 'ROBO', 'on'],
  ['智能穿戴', 'Apple', 6499, 6899, 5400, 38, 20, 296, 11270, 'th1', 'Watch', 'on'],
  ['智能穿戴', '华为', 2988, 3288, 2300, 72, 30, 418, 14360, 'th3', 'Watch', 'audit'],
  ['智能穿戴', '小米', 449, 499, 300, 0, 60, 1620, 28740, 'th2', 'Band', 'sold'],
  ['电脑办公', 'Apple', 9299, 9999, 7900, 26, 15, 342, 15680, 'th6', 'iPad', 'ware'],
  ['数码配件', 'Apple', 999, 1099, 720, 145, 50, 726, 19420, 'th5', 'Pencil', 'on'],
  ['数码配件', '极客严选', 129, 169, 68, 480, 100, 3860, 52140, 'th4', 'Geek', 'off']
]

const PRODUCT_NAMES = [
  'Apple iPhone 17 Pro 256G 原色钛金属', 'Apple iPhone 17 128G 群青色',
  '小米 15 Ultra 16+512G 徕卡影像', '华为 Mate 60 Pro 12+512G',
  '三星 Galaxy S25 Ultra 512G', '华为 MateBook X Pro 2026 超凡屏',
  'Apple MacBook Air 15 M4 512G', '联想 ThinkPad X1 Carbon Gen13',
  '明基 PD3225U 4K 设计师显示器', '三星 990 PRO 2TB NVMe 固态硬盘',
  '罗技 MX Master 4 无线鼠标', 'Apple AirPods Pro 3 主动降噪',
  '索尼 WH-1000XM6 头戴降噪耳机', '森海塞尔 MOMENTUM 4 无线耳机',
  '戴森 V16 无线手持吸尘器', '石头 G30 Space 扫拖机器人',
  'Apple Watch Ultra 3 49mm', '华为 WATCH GT6 Pro 蓝宝石版',
  '小米手环 10 Pro 陶瓷版', 'iPad Pro 13 英寸 M4 WiFi 512G',
  'Apple Pencil Pro 磁吸触控笔', '极客严选 65W 氮化镓充电器'
]

const PRODUCT_SPECS = [
  'A19 Pro 芯片 · 5G 双卡', 'A19 · 6.3 英寸超视网膜屏', '骁龙 8 Elite · 卫星通信',
  '昆仑玻璃 · 双向北斗消息', '2 亿像素 · S Pen', 'i7 / 32G / 1T 触控',
  '午夜色 · 18 小时续航', 'i7 / 32G / 1T 商务本', '32 英寸 / 4K / 雷电 4',
  'PCIe 4.0 · 7450MB/s', '石墨黑 · 静音微动', '自适应音频 · 无线充电盒',
  '铂金银 · 30 小时续航', '曜石黑 · 60 小时续航', '旗舰版 · 光学探测吸头',
  '自清洁基站 · 超薄机身', '钛金属表壳 · 双频 GPS', '钛合金表体 · 14 天续航',
  '1.74 英寸 AMOLED', '深空黑 · 超精视网膜 XDR', '压力感应 · 触感反馈',
  '三口输出 · 折叠插脚'
]

/** 同一商品的多规格拆分 */
const SKU_TEMPLATE = {
  手机通讯: ['原色钛 · 256G', '黑色 · 512G', '白色 · 1T'],
  电脑办公: ['i7 / 32G / 1T', 'i9 / 64G / 2T'],
  智能穿戴: ['标准版', '商务版'],
  影音娱乐: ['标准版', '旗舰版'],
  数码配件: ['标准版', '尊享版'],
  智能家居: ['标准版', '旗舰版']
}

function buildProducts() {
  return EXTRA_PRODUCTS.map((row, i) => {
    const [cat, brand, price, listPrice, cost, stock, safeStock, sales, views, thumb, tag, status] = row
    const specs = SKU_TEMPLATE[cat] || ['标准版']
    // 把总库存按 6:3 拆分，最后一条兜底吃掉余数，保证各规格之和 = 总库存
    let left = stock
    const skus = specs.map((spec, k) => {
      const ratio = k === 0 ? 0.6 : 0.3
      const take = k === specs.length - 1 ? left : Math.round(stock * ratio)
      const qty = Math.max(0, Math.min(left, take))
      left -= qty
      return {
        spec,
        price: price + k * (price > 3000 ? 900 : 100),
        stock: qty,
        code: `GK${10001 + i}-${k + 1}`
      }
    })

    return {
      id: `P${10001 + i}`,
      code: `GK${10001 + i}`,
      name: PRODUCT_NAMES[i],
      spec: PRODUCT_SPECS[i],
      cat,
      brand,
      price,
      listPrice,
      cost,
      stock,
      safeStock,
      sales,
      views,
      thumb,
      tag,
      status,
      skus,
      updatedAt: new Date(NOW.getTime() - (i % 12) * 5 * DAY)
    }
  })
}

export const PRODUCTS = buildProducts()

/* ==========================================================================
   经营概览
   ========================================================================== */

/** 月度目标：设在略高于实际营收的水平，进度环才有信息量 */
const MONTH_TARGET = 5000000

export function buildOverview() {
  const today = DAILY_SERIES[DAILY_SERIES.length - 1]
  const yesterday = DAILY_SERIES[DAILY_SERIES.length - 2]

  // 近 7 天 vs 前 7 天、近 30 天 vs 前 30 天，都由同一个序列切出来
  const thisWeek = lastDays(7)
  const prevWeek = prevDays(7)
  const thisMonth = lastDays(30)
  const prevMonth = prevDays(30)

  const monthRevenue = sum(thisMonth, 'amount')
  const monthCustomers = sum(thisMonth, 'customers')

  const kpi = [
    {
      key: 'gmv',
      label: '成交额',
      value: sum(thisWeek, 'amount'),
      prefix: '¥',
      delta: chainRatio(sum(thisWeek, 'amount'), sum(prevWeek, 'amount')),
      trend: thisWeek.map((d) => d.amount),
      tone: 'brand'
    },
    {
      key: 'orders',
      label: '订单数',
      value: sum(thisWeek, 'orders'),
      suffix: '笔',
      delta: chainRatio(sum(thisWeek, 'orders'), sum(prevWeek, 'orders')),
      trend: thisWeek.map((d) => d.orders),
      tone: 'green'
    },
    {
      key: 'aov',
      label: '客单价',
      value: sum(thisWeek, 'amount') / sum(thisWeek, 'orders'),
      prefix: '¥',
      digits: 2,
      delta: chainRatio(
        sum(thisWeek, 'amount') / sum(thisWeek, 'orders'),
        sum(prevWeek, 'amount') / sum(prevWeek, 'orders')
      ),
      trend: thisWeek.map((d) => d.amount / d.orders),
      tone: 'teal'
    },
    {
      key: 'visitors',
      label: '访客数',
      value: sum(thisWeek, 'visitors'),
      suffix: '人',
      delta: chainRatio(sum(thisWeek, 'visitors'), sum(prevWeek, 'visitors')),
      trend: thisWeek.map((d) => d.visitors),
      tone: 'violet'
    }
  ]

  return {
    hero: {
      monthRevenue,
      monthGrowth: chainRatio(monthRevenue, sum(prevMonth, 'amount')),
      monthCustomers,
      customerGrowth: chainRatio(monthCustomers, sum(prevMonth, 'customers')),
      pendingSettle: Math.round(monthRevenue * 0.23),
      targetAmount: MONTH_TARGET,
      targetRate: Math.min(1, monthRevenue / MONTH_TARGET)
    },
    kpi,
    today: {
      amount: today.amount,
      orders: today.orders,
      amountDelta: chainRatio(today.amount, yesterday.amount)
    },
    /** 趋势图：近 7 天 / 近 30 天，各带「上一周期」对比线 */
    trend: {
      week: {
        label: '近 7 天',
        labels: thisWeek.map((d) => d.label),
        current: thisWeek.map((d) => d.amount),
        previous: prevWeek.map((d) => d.amount)
      },
      month: {
        label: '近 30 天',
        labels: thisMonth.map((d) => d.label),
        current: thisMonth.map((d) => d.amount),
        previous: prevMonth.map((d) => d.amount)
      }
    },
    traffic: [
      { name: '自然搜索', value: 0.342, orders: 1284 },
      { name: '首页推荐', value: 0.226, orders: 848 },
      { name: '直通车', value: 0.163, orders: 612 },
      { name: '购物车', value: 0.128, orders: 480 },
      { name: '站外投放', value: 0.087, orders: 326 },
      { name: '其他', value: 0.054, orders: 203 }
    ],
    todos: [
      { key: 'ship', label: '待发货订单', count: 6, tone: 'brand', desc: '超 24 小时将影响体验分', to: '/shipping' },
      { key: 'after', label: '售后待处理', count: 6, tone: 'coral', desc: '需在 48 小时内响应', to: '/aftersale' },
      { key: 'review', label: '评价待回复', count: 12, tone: 'amber', desc: '影响店铺 DSR 评分', to: '/reviews' },
      { key: 'stock', label: '库存预警商品', count: 4, tone: 'violet', desc: '低于安全库存线', to: '/products' }
    ],
    topProducts: PRODUCTS.slice()
      .sort((a, b) => b.sales - a.sales)
      .slice(0, 5)
      .map((p) => ({
        id: p.id,
        name: p.name,
        thumb: p.thumb,
        tag: p.tag,
        sales: p.sales,
        amount: p.sales * p.price
      })),
    stockAlerts: PRODUCTS.filter((p) => p.status !== 'trash' && p.stock <= p.safeStock)
      .slice(0, 4)
      .map((p) => ({
        id: p.id,
        name: p.name,
        thumb: p.thumb,
        tag: p.tag,
        stock: p.stock,
        safeStock: p.safeStock
      }))
  }
}

/* ==========================================================================
   数据看板
   ========================================================================== */

export function buildAnalytics() {
  const rng = createRng(20261505)
  const last30 = lastDays(30)
  const prev30 = prevDays(30)

  const gmv = sum(last30, 'amount')
  const orders = sum(last30, 'orders')
  const visitors = sum(last30, 'visitors')
  const prevGmv = sum(prev30, 'amount')
  const prevOrders = sum(prev30, 'orders')
  const prevVisitors = sum(prev30, 'visitors')

  return {
    summary: [
      { key: 'gmv', label: '成交额', value: gmv, prefix: '¥', delta: chainRatio(gmv, prevGmv), tone: 'brand' },
      { key: 'orders', label: '支付订单', value: orders, suffix: '笔', delta: chainRatio(orders, prevOrders), tone: 'green' },
      {
        key: 'convert',
        label: '支付转化率',
        value: orders / visitors,
        percent: true,
        delta: chainRatio(orders / visitors, prevOrders / prevVisitors),
        tone: 'teal'
      },
      {
        key: 'aov',
        label: '客单价',
        value: gmv / orders,
        prefix: '¥',
        digits: 2,
        delta: chainRatio(gmv / orders, prevGmv / prevOrders),
        tone: 'violet'
      },
      { key: 'uv', label: '访客数', value: visitors, suffix: '人', delta: chainRatio(visitors, prevVisitors), tone: 'amber' }
    ],
    trend: {
      labels: last30.map((d) => d.label),
      amount: last30.map((d) => d.amount),
      orders: last30.map((d) => d.orders)
    },
    channels: [
      { name: '自然搜索', value: 0.342, uv: 18420, delta: 0.086 },
      { name: '首页推荐', value: 0.226, uv: 12180, delta: 0.142 },
      { name: '直通车', value: 0.163, uv: 8780, delta: -0.035 },
      { name: '购物车', value: 0.128, uv: 6900, delta: 0.052 },
      { name: '站外投放', value: 0.087, uv: 4690, delta: 0.218 },
      { name: '其他', value: 0.054, uv: 2910, delta: -0.014 }
    ],
    categories: [
      { name: '手机通讯', value: 0.386, amount: 2836000 },
      { name: '电脑办公', value: 0.274, amount: 2013000 },
      { name: '影音娱乐', value: 0.146, amount: 1072000 },
      { name: '智能家居', value: 0.096, amount: 705000 },
      { name: '智能穿戴', value: 0.062, amount: 455000 },
      { name: '数码配件', value: 0.036, amount: 264000 }
    ],
    regions: [
      { name: '广东省', value: 0.186, amount: 1365000 },
      { name: '浙江省', value: 0.142, amount: 1042000 },
      { name: '江苏省', value: 0.118, amount: 866000 },
      { name: '北京市', value: 0.104, amount: 763000 },
      { name: '上海市', value: 0.096, amount: 705000 },
      { name: '四川省', value: 0.072, amount: 528000 },
      { name: '湖北省', value: 0.061, amount: 448000 },
      { name: '福建省', value: 0.054, amount: 396000 }
    ],
    /** 转化漏斗：逐级收窄 */
    funnel: [
      { name: '商品曝光', value: 1284000, rate: 1 },
      { name: '商品点击', value: 268400, rate: 0.209 },
      { name: '加入购物车', value: 86200, rate: 0.067 },
      { name: '提交订单', value: 32800, rate: 0.0256 },
      { name: '支付成功', value: 26400, rate: 0.0206 }
    ],
    /** 24 小时下单时段分布：午间 12 点与晚间 21 点双峰 */
    hourly: Array.from({ length: 24 }, (_, h) => {
      const noon = Math.exp(-((h - 12) ** 2) / 8)
      const night = Math.exp(-((h - 21) ** 2) / 12)
      return { hour: h, value: Math.round((0.18 + noon * 0.62 + night * 0.95) * (0.86 + rng() * 0.28) * 100) }
    }),
    customerMix: [
      { name: '新客', value: 0.628, count: 16420 },
      { name: '老客复购', value: 0.372, count: 9720 }
    ]
  }
}
