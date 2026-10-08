/**
 * 库存分桶 API：分桶规则 / 库存分配 / 桶余量 / 调拨合并 / 出库回滚 / 报告审计。
 *
 * 与后端 `InventoryBucketController`（前缀 /merchant/inventory/bucket）一一对应。
 * 分页接口统一返回 { list, total, page, pageSize }，可直接喂给 useTableQuery。
 */
import request from './request'

/* ==========================================================================
   分桶规则
   ========================================================================== */

/** 规则列表（按维度 / 启用状态过滤） */
export function fetchRuleList(params = {}) {
  return request.get('/merchant/inventory/bucket/rule/list', { params })
}

/** 新增或编辑规则，返回规则 ID */
export function saveBucketRule(payload) {
  return request.post('/merchant/inventory/bucket/rule/save', payload)
}

/** 启用 / 停用规则 */
export function updateRuleStatus(ruleId, enabled) {
  return request.post('/merchant/inventory/bucket/rule/status', null, {
    params: { ruleId, enabled }
  })
}

/* ==========================================================================
   库存分配
   ========================================================================== */

/**
 * 按明细把商品现有库存分配到各桶（不改商品总库存，差额自动落入「未分配」桶）。
 * @param {{productId:number, ruleId?:number, dimension?:string, items:Array, operator?:string}} payload
 */
export function allocateBuckets(payload) {
  return request.post('/merchant/inventory/bucket/allocate', payload)
}

/**
 * 按规则把现有库存均分到多个维度值。
 * 后端 `@RequestParam List<String> values` 支持逗号分隔的单值绑定。
 */
export function autoSplitBuckets(productId, ruleId, values) {
  return request.post('/merchant/inventory/bucket/auto-split', null, {
    params: { productId, ruleId, values: values.join(',') }
  })
}

/* ==========================================================================
   余量查询
   ========================================================================== */

/** 某商品分桶余量全景（含守恒对账） */
export function fetchBalance(productId) {
  return request.get('/merchant/inventory/bucket/balance', { params: { productId } })
}

/** 库存桶分页 */
export function fetchBucketPage(params = {}) {
  return request.get('/merchant/inventory/bucket/page', { params })
}

/* ==========================================================================
   调拨 / 合并
   ========================================================================== */

/** 跨桶调拨，返回操作单号 */
export function transferBuckets(payload) {
  return request.post('/merchant/inventory/bucket/transfer', payload)
}

/** 跨桶合并，返回操作单号 */
export function mergeBuckets(payload) {
  return request.post('/merchant/inventory/bucket/merge', payload)
}

/* ==========================================================================
   出库 / 回滚
   ========================================================================== */

/** 按优先级从各桶出库 */
export function deductBuckets(payload) {
  return request.post('/merchant/inventory/bucket/deduct', payload)
}

/** 按单号把出库量还回原桶 */
export function rollbackBuckets(orderNo) {
  return request.post('/merchant/inventory/bucket/rollback', null, { params: { orderNo } })
}

/* ==========================================================================
   报告 / 审计
   ========================================================================== */

/** 分桶报告：按维度汇总 + 与商品总库存对账 */
export function fetchBucketReport(productId) {
  return request.get('/merchant/inventory/bucket/report', { params: { productId } })
}

/** 审计流水分页 */
export function fetchBucketLogs(params = {}) {
  return request.get('/merchant/inventory/bucket/logs', { params })
}

/** 调拨 / 合并单分页 */
export function fetchBucketOperations(params = {}) {
  return request.get('/merchant/inventory/bucket/operations', { params })
}
