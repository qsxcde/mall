package com.geekmall.modules.message.listener;

import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.event.OrderStatusChangedEvent;
import com.geekmall.modules.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 订单状态变更 → 站内消息。
 *
 * <p>两个注解是关键：</p>
 * <ul>
 *     <li>{@code @TransactionalEventListener(AFTER_COMMIT)}：只有订单事务真正提交后才发消息，
 *         不会出现「订单回滚了但用户收到通知」；</li>
 *     <li>{@code @Async}：消息落库/后续推送失败不会拖慢下单接口，也不会影响订单事务。</li>
 * </ul>
 *
 * <p>交易域完全不知道消息域的存在——将来要加短信、App 推送，只需再写一个监听器。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusMessageListener {

    private final MessageService messageService;

    @Async("messageExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        try {
            OrderStatus target = OrderStatus.of(event.toStatus());
            if (target == null) {
                return;
            }
            messageService.send(event.userId(), typeOf(target), titleOf(target),
                    "订单 " + event.orderNo() + "：" + (event.remark() == null ? titleOf(target) : event.remark()),
                    linkOf(target, event.orderNo()));
        } catch (Exception e) {
            // 通知失败不能影响主流程，记录后等后续补偿
            log.error("订单 {} 状态变更消息发送失败", event.orderNo(), e);
        }
    }

    private String titleOf(OrderStatus status) {
        return switch (status) {
            case PENDING_PAY -> "订单创建成功";
            case PENDING_SHIP -> "订单支付成功";
            case PENDING_RECEIVE -> "订单已发货";
            case PENDING_COMMENT -> "订单已签收，期待您的评价";
            case FINISHED -> "订单已完成";
            case CANCELED -> "订单已取消";
        };
    }

    private String typeOf(OrderStatus status) {
        return status == OrderStatus.PENDING_RECEIVE ? "logistics" : "order";
    }

    private String linkOf(OrderStatus status, String orderNo) {
        return switch (status) {
            case PENDING_RECEIVE -> "/orders/" + orderNo + "/logistics";
            case PENDING_COMMENT -> "/orders/" + orderNo + "/review";
            default -> "/orders/" + orderNo;
        };
    }
}
