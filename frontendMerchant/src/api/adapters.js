/**
 * 出参适配层。
 *
 * 后端返回的是「数据本体」，视图要的是「可直接渲染的形状」。两者之间只有少数几处固定差异，
 * 集中在这里吸收，避免在每个视图里写兜底：
 *
 * 1. 分页：后端统一 { list, total, page, pageSize, tabs, stats, … }，
 *    视图只取 list/total，其余由 useTableQuery 收进 extras，无需处理。
 * 2. buyerIndex：后端不存这个概念，但头像要求「同一买家恒定同色」，
 *    因此按姓名哈希推导一个稳定索引。
 * 3. 发货单地址：后端是 province + area（area 为完整地址字符串），
 *    视图要的是 { province, detail } 两段。
 */

/** 由字符串推导稳定的取色索引（同名必然同色，跨页面一致）。 */
export function toneIndex(seed) {
  const s = String(seed ?? '')
  let hash = 0
  for (let i = 0; i < s.length; i += 1) {
    hash = (hash * 31 + s.charCodeAt(i)) % 100000
  }
  return hash
}

/** 给行数据补上 buyerIndex。 */
function withBuyerIndex(row) {
  if (!row) return row
  return { ...row, buyerIndex: toneIndex(row.buyer?.name) }
}

/** 分页响应：逐项适配 list，其余字段（total/tabs/stats/trend…）原样透传。 */
export function adaptPage(res, adaptRow) {
  if (!res) return res
  return { ...res, list: (res.list || []).map(adaptRow) }
}

/* ------------------------------ 订单 ------------------------------ */

export function adaptOrder(order) {
  return withBuyerIndex(order)
}

/* ------------------------------ 发货单 ------------------------------ */

export function adaptShipment(shipment) {
  if (!shipment) return shipment
  const province = shipment.province || ''
  const full = shipment.area || ''
  // 后端 area 是「省 + 详址」的完整串，拆掉省前缀即为详址
  const detail = province && full.startsWith(province) ? full.slice(province.length).trim() : full
  return { ...withBuyerIndex(shipment), area: { province, detail } }
}

/* ------------------------------ 售后 ------------------------------ */

export function adaptAftersale(aftersale) {
  return withBuyerIndex(aftersale)
}

/* ------------------------------ 评价 ------------------------------ */

export function adaptReview(review) {
  return withBuyerIndex(review)
}
