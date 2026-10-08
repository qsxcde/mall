/**
 * 库存分桶域：把一份商品库存按业务维度拆成多个独立桶行，让出库可以分散到不同行并行推进，
 * 而不是所有请求争抢同一行 InnoDB 行锁。
 *
 * <p><b>为什么需要</b>：{@code pms_product.stock} 是商品级单行总量，单热点 SKU 下出库并行度恒为 1。
 * 分桶解决的是<b>并发度</b>问题 —— 不创造库存、也不负责防超卖（防超卖靠 CAS）。</p>
 *
 * <p>已实现：</p>
 * <ul>
 *     <li>{@code entity}：InvBucketRule（分桶规则）/ InvBucket（桶）/ InvBucketOperation（调拨合并单）
 *         / InvBucketLog（审计流水，append-only）</li>
 *     <li>{@code service.InventoryBucketService}：规则增改启停、{@code allocate} 声明式重布局、
 *         {@code autoSplit} 均分、跨桶 {@code transfer}/{@code merge}、按优先级 {@code deduct} 出库、
 *         按单号 {@code rollback} 还回原桶、balance/report/page/logs 查询</li>
 *     <li>{@code job.InventoryBucketReconcileJob}：定时扫描「桶合计 ≠ 商品总库存」并告警，
 *         ShedLock 保证多实例只跑一次</li>
 *     <li>{@code controller.InventoryBucketController} → /api/v1/merchant/inventory/bucket/**</li>
 * </ul>
 *
 * <p>关键设计（详见 {@code docs/spec/库存分桶设计与交互边界.md}）：</p>
 * <ol>
 *     <li><b>守恒不变量</b>：{@code SUM(inv_bucket.stock) == pms_product.stock}，
 *         差额落入「未分配」桶，杜绝库存凭空消失；</li>
 *     <li><b>单一维度</b>：同一商品同一时刻只用一种分桶维度（WAREHOUSE/BATCH/EXPIRY/REGION），
 *         避免多维度叠加导致对账歧义；</li>
 *     <li><b>留痕 + 幂等</b>：任何桶余量变化都写 {@code inv_bucket_log}，出库/回滚两侧共用
 *         {@code order_no} 作幂等键（唯一索引 {@code uk_biz_idempotent}）；</li>
 *     <li><b>未分桶商品零影响</b>：{@code deduct} 检测到商品没有任何桶时退化为「仅商品级扣减」，
 *         与旧链路行为一致，因此可按商品灰度上线；</li>
 *     <li><b>分桶不消除锁，只分摊锁</b>：{@code deduct} 仍会扣一次商品总库存（共享行仍串行），
 *         收益上限是 {@code min(桶数, 消费者线程数, 连接池)}。</li>
 * </ol>
 *
 * <p>与秒杀库存的关系：{@code mkt_seckill_item.stock} 是独立活动库存池，<b>不纳入本域</b>，
 * 它有自己的桶表 {@code mkt_seckill_bucket}（见 {@code modules/marketing}）。</p>
 *
 * <p>对应前端：frontendMerchant 的 InventoryBucketView（路由 {@code /inventory}）。</p>
 */
package com.geekmall.modules.inventory;
