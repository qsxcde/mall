package com.geekmall.common.event;

/**
 * 订单状态变更事件。
 *
 * <p>交易域只负责「状态变了」这件事，至于要不要发站内消息、要不要推送、
 * 要不要记埋点，交给各自的监听器。</p>
 *
 * <p>用 {@code @TransactionalEventListener(AFTER_COMMIT)} 监听可以保证：
 * 只有事务真正提交后消息才发出，不会出现「订单回滚了但用户收到通知」。</p>
 *
 * @param userId    订单所属用户
 * @param orderNo   订单号
 * @param fromStatus 变更前状态码
 * @param toStatus   变更后状态码
 * @param remark     流转备注
 */
public record OrderStatusChangedEvent(Long userId,
                                      String orderNo,
                                      int fromStatus,
                                      int toStatus,
                                      String remark) {
}
