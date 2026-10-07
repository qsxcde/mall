package com.geekmall.common.event;

import java.util.Map;

/**
 * 下单成功事件。
 *
 * <p>购物车清理等非核心副作用通过该事件在事务提交后异步执行（P0-2）：
 * 既把「删购物车」移出下单主事务、缩短库存行锁持有时间，
 * 又保证订单回滚时不会误删购物车。</p>
 *
 * @param userId      下单用户
 * @param cartItemQty 本单消耗的购物车项 → <b>下单那一刻的数量</b>。
 *                    <p>带上数量是必要的：购物车 {@code add()} 对「同商品同规格」是<b>累加合并</b>，
 *                    并复用同一行 id。若异步清理只按 id 删除，就会把「下单之后用户又加购合并进来」
 *                    的那一行一起删掉，导致用户紧接着再次下单时提示「购物车为空」。
 *                    因此清理要求「数量与下单时一致」才删除（乐观守卫），数量已被改动则保留。</p>
 */
public record OrderCreatedEvent(Long userId, Map<Long, Integer> cartItemQty) {
}
