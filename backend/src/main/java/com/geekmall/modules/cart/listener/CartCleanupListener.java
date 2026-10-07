package com.geekmall.modules.cart.listener;

import com.geekmall.common.event.OrderCreatedEvent;
import com.geekmall.modules.cart.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 下单成功后清理购物车（P0-2）。
 *
 * <p>两个注解是关键：{@code AFTER_COMMIT} 保证订单真正落库后才删购物车，
 * 订单回滚时购物车不受影响；{@code @Async("messageExecutor")} 保证清理失败或变慢
 * 不会拖慢下单接口、也不会撑长库存行锁的持有时间。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartCleanupListener {

    private final CartService cartService;

    @Async("messageExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        try {
            cartService.removeBatch(event.userId(), event.cartItemQty());
        } catch (Exception e) {
            // 清理失败不影响订单，用户下次进入购物车仍可见（幂等性由订单保证）
            log.error("用户 {} 下单后清理购物车失败：{}", event.userId(), event.cartItemQty(), e);
        }
    }
}
