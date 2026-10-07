package com.geekmall.common.enums;

import lombok.Getter;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机。
 *
 * <p>状态流转必须经由 {@link #canTransferTo(OrderStatus)} 校验，禁止越权流转。</p>
 * <pre>
 * 0 待付款 → 1 待发货（支付成功）
 * 0 待付款 → 5 已取消（用户取消 / 超时未支付）
 * 1 待发货 → 2 待收货（商家发货）
 * 2 待收货 → 3 待评价（确认收货）
 * 3 待评价 → 4 已完成（评价完成）
 * </pre>
 */
@Getter
public enum OrderStatus {

    PENDING_PAY(0, "待付款"),
    PENDING_SHIP(1, "待发货"),
    PENDING_RECEIVE(2, "待收货"),
    PENDING_COMMENT(3, "待评价"),
    FINISHED(4, "已完成"),
    CANCELED(5, "已取消");

    private final int code;
    private final String text;

    OrderStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    /** 合法流转表：key 可流转到 value 中的任意状态。 */
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        TRANSITIONS.put(PENDING_PAY, EnumSet.of(PENDING_SHIP, CANCELED));
        TRANSITIONS.put(PENDING_SHIP, EnumSet.of(PENDING_RECEIVE, CANCELED));
        TRANSITIONS.put(PENDING_RECEIVE, EnumSet.of(PENDING_COMMENT));
        TRANSITIONS.put(PENDING_COMMENT, EnumSet.of(FINISHED));
        TRANSITIONS.put(FINISHED, EnumSet.noneOf(OrderStatus.class));
        TRANSITIONS.put(CANCELED, EnumSet.noneOf(OrderStatus.class));
    }

    public static OrderStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    public boolean canTransferTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(OrderStatus.class)).contains(target);
    }

    public boolean isFinal() {
        return this == FINISHED || this == CANCELED;
    }
}
