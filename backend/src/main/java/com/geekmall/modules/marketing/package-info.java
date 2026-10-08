/**
 * 营销域：优惠券、秒杀、积分商城、新品首发。
 *
 * <p>已实现：</p>
 * <ul>
 *     <li><b>优惠券</b>：领券中心模板列表（带「是否已领」标记）、领取（唯一索引 + 库存条件更新双重幂等）、
 *         我的优惠券、结算可用券与抵扣计算、核销、订单取消回退。
 *         {@code CouponController} → /api/v1/coupons/**</li>
 *     <li><b>秒杀</b>：场次、场次商品、抢购。含 Redis Lua 原子预扣 + 一人一单 + 预扣回补、
 *         <b>库存分桶</b>（{@code mkt_seckill_bucket}，桶数由 {@code mkt_seckill_item.bucket_count} 配置，
 *         把单行热点拆成 N 行）、以及基于 <b>Redis Stream</b> 的可选削峰。
 *         {@code SeckillController} → /api/v1/seckill/**</li>
 *     <li><b>积分商城</b>：积分商品（带「积分是否足够」）、兑换（扣积分 + 扣库存 + 写记录同事务）、兑换记录。
 *         {@code PointsController} → /api/v1/points/**</li>
 * </ul>
 *
 * <h3>秒杀为什么这样实现（方案 6.4 的 L2 档）</h3>
 * <p>核心矛盾是「瞬时高并发写入 vs 数据库扛不住」。第一性动作是把扣库存搬到 Redis，
 * 让绝大多数请求在内存层就被拦掉，因此：</p>
 * <ol>
 *     <li><b>Redis Lua 原子预扣</b>：一个脚本内完成「判库存 → 判一人一单 → 扣减」，
 *         拆开任何一步都会产生并发窗口导致超卖。</li>
 *     <li><b>DB 第二道防线</b>：{@code UPDATE ... WHERE stock > 0} 兜底，Redis 与 DB 不一致时也不会超卖。</li>
 *     <li><b>失败补偿</b>：预扣成功但落库/建单失败时回补 Redis（移除一人一单标记 + 归还库存），
 *         否则库存会被「黑洞」永久占用。</li>
 *     <li><b>削峰可选、默认关闭</b>：默认同步落库；打开 {@code mall.seckill.async.enabled} 后，
 *         落库改为经 Redis Stream 交给消费者线程，接口从「返回订单号」变为「返回请求号 + 轮询结果」。
 *         实测它只降低<b>受理</b>耗时、<b>不提升落库吞吐</b>（瓶颈是 DB 行锁），见
 *         {@code docs/concurrency/秒杀削峰压测对比报告.md}。</li>
 * </ol>
 *
 * <p>待实现：新品首发独立接口（当前可直接用 {@code /api/v1/products?isNew=true}）、
 * 秒杀场次时间自动流转（现依赖 session.state 字段）。
 * （抢购结果轮询接口已实现：{@code GET /api/v1/seckill/result/{requestId}}。）</p>
 *
 * <p>对应前端：CouponView、SeckillView、NewProductView、PointsMallView，以及结算页券选择。</p>
 */
package com.geekmall.modules.marketing;
