/**
 * Mock 数据共享池。
 *
 * 设计要点：
 * - 用「固定种子 + 线性同余」生成伪随机数，保证每次刷新数据一致
 *   （否则演示时数字乱跳，无法核对图表与列表口径）
 * - 商品、买家、地区等基础字典集中在此，供各业务模块复用
 */

/** 线性同余伪随机数发生器，返回 [0,1) */
export function createRng(seed) {
  let s = seed >>> 0
  return function next() {
    s = (s * 1103515245 + 12345) % 2147483648
    return s / 2147483648
  }
}

/** 从数组随机取一项 */
export function pick(rng, arr) {
  return arr[Math.floor(rng() * arr.length) % arr.length]
}

/** 随机整数 [min, max] */
export function randInt(rng, min, max) {
  return min + Math.floor(rng() * (max - min + 1))
}

/** 业务数据的「当前时间」基准点，让所有模块的时间轴一致 */
export const NOW = new Date(2026, 9, 5, 10, 20, 0)

/**
 * 商品池。
 * thumb 为缩略图配色键（th1~th6），由 <ProductThumb> 映射为渐变背景。
 */
export const PRODUCT_POOL = [
  { id: 'P10001', name: 'Apple iPhone 17 Pro 256G 原色钛金属', spec: '256G · 原色钛', price: 8999, listPrice: 9999, cost: 7600, cat: '手机通讯', brand: 'Apple', thumb: 'th1', tag: 'iPhone' },
  { id: 'P10002', name: 'Apple iPhone 17 128G 群青色', spec: 'A19 · 6.3 英寸超视网膜屏', price: 5999, listPrice: 6799, cost: 5100, cat: '手机通讯', brand: 'Apple', thumb: 'th6', tag: 'iPhone' },
  { id: 'P10003', name: '小米 15 Ultra 16+512G 徕卡影像旗舰', spec: '16+512G · 黑色', price: 6499, listPrice: 6999, cost: 5500, cat: '手机通讯', brand: '小米', thumb: 'th2', tag: 'MI' },
  { id: 'P10004', name: '华为 MateBook X Pro 2026 超凡屏', spec: 'i7 / 32G / 1T 触控', price: 12999, listPrice: 14999, cost: 11200, cat: '电脑办公', brand: '华为', thumb: 'th3', tag: 'Mate' },
  { id: 'P10005', name: 'Apple MacBook Air 15 M4 512G', spec: '午夜色 · 18 小时续航', price: 10999, listPrice: 11999, cost: 9400, cat: '电脑办公', brand: 'Apple', thumb: 'th1', tag: 'Mac' },
  { id: 'P10006', name: 'iPad Pro 13 英寸 M4 WiFi 512G', spec: '512G · 深空黑', price: 9299, listPrice: 9999, cost: 7900, cat: '电脑办公', brand: 'Apple', thumb: 'th6', tag: 'iPad' },
  { id: 'P10007', name: '索尼 WH-1000XM6 头戴降噪耳机', spec: '铂金银 · 30 小时续航', price: 2999, listPrice: 3299, cost: 2300, cat: '影音娱乐', brand: '索尼', thumb: 'th5', tag: 'SONY' },
  { id: 'P10008', name: 'Apple AirPods Pro 3 主动降噪', spec: '自适应音频 · 无线充电盒', price: 1899, listPrice: 2099, cost: 1450, cat: '影音娱乐', brand: 'Apple', thumb: 'th2', tag: 'AirPods' },
  { id: 'P10009', name: '戴森 V16 无线手持吸尘器', spec: '旗舰版 · 光学探测吸头', price: 4990, listPrice: 5590, cost: 3900, cat: '智能家居', brand: '戴森', thumb: 'th4', tag: 'DYSON' },
  { id: 'P10010', name: 'Apple Watch Ultra 3 49mm', spec: '钛金属表壳 · 双频 GPS', price: 6499, listPrice: 6899, cost: 5400, cat: '智能穿戴', brand: 'Apple', thumb: 'th1', tag: 'Watch' },
  { id: 'P10011', name: '三星 990 PRO 2TB NVMe 固态硬盘', spec: '2TB / PCIe 4.0', price: 1199, listPrice: 1499, cost: 900, cat: '数码配件', brand: '三星', thumb: 'th3', tag: 'SSD' },
  { id: 'P10012', name: '罗技 MX Master 4 无线鼠标', spec: '石墨黑 · 静音微动', price: 699, listPrice: 799, cost: 480, cat: '数码配件', brand: '罗技', thumb: 'th1', tag: 'Logi' }
]

/** 买家池 */
export const BUYERS = [
  { name: '林清和', level: 'V5' },
  { name: '苏若雪', level: 'V4' },
  { name: '周天翊', level: 'V6' },
  { name: '徐佳宁', level: 'V3' },
  { name: '郑维安', level: 'V5' },
  { name: '何知微', level: 'V4' },
  { name: '孟星辰', level: 'V2' },
  { name: '唐述白', level: 'V6' },
  { name: '谢澜舟', level: 'V3' },
  { name: '邹予安', level: 'V5' },
  { name: '冯听柔', level: 'V4' },
  { name: '鲍慎行', level: 'V2' }
]

/** 收货地区池 */
export const AREAS = [
  { province: '广东省 深圳市 南山区', detail: '科苑南路 2588 号 万科云城 A 座 12F' },
  { province: '浙江省 杭州市 西湖区', detail: '文三路 199 号 云谷大厦 8F' },
  { province: '北京市 海淀区', detail: '中关村大街 27 号 4 号楼 502' },
  { province: '上海市 浦东新区', detail: '世纪大道 1568 号 世纪汇广场 T2-1801' },
  { province: '江苏省 南京市 鼓楼区', detail: '中山北路 88 号 华彩天地 9F' },
  { province: '四川省 成都市 武侯区', detail: '天府大道 1199 号 银泰中心 21F' },
  { province: '湖北省 武汉市 洪山区', detail: '珞喻路 726 号 光谷国际广场 B 座 7F' },
  { province: '福建省 厦门市 思明区', detail: '观日路 12 号 软件园二期 3F' }
]

export const EXPRESS_LIST = ['顺丰速运', '京东物流', '中通快递', '圆通速递', '德邦快递']
export const WAREHOUSE_LIST = ['深圳总仓', '杭州仓', '北京仓']
export const PAY_LIST = ['支付宝', '微信支付', '银行卡', '余额支付']

/** 头像渐变色，按买家下标轮换 */
export const AVATAR_TONES = ['brand', 'coral', 'teal', 'gold', 'violet']

/** 生成订单号 / 运单号 */
export const orderNo = (i) => `GK${2026100500 + 312 - i * 7}`
export const waybillNo = (i) => `SF${1000000000 + i * 99991}`

/** 稳定的手机号（避免每次刷新变化） */
export const phoneNo = (i) => `1${3 + (i % 7)}${`${100000000 + i * 13579}`.slice(0, 9)}`
