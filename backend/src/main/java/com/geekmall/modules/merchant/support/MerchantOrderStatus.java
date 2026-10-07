package com.geekmall.modules.merchant.support;

import com.geekmall.common.enums.OrderStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单状态映射：买家侧数字状态码 ↔ 商家侧字符串状态。
 *
 * <pre>
 * 0 待付款  → wait_pay
 * 1 待发货  → wait_ship
 * 2 待收货  → shipped
 * 3 待评价  → wait_review
 * 4 已完成  → done
 * 5 已取消  → closed
 * 「售后中」→ after（由售后工单派生，非订单本身状态）
 * </pre>
 */
public final class MerchantOrderStatus {

    public static final String WAIT_PAY = "wait_pay";
    public static final String WAIT_SHIP = "wait_ship";
    public static final String SHIPPED = "shipped";
    public static final String WAIT_REVIEW = "wait_review";
    public static final String DONE = "done";
    public static final String CLOSED = "closed";
    public static final String AFTER = "after";

    private static final Map<Integer, String> CODE_TO_TEXT = new LinkedHashMap<>();

    static {
        CODE_TO_TEXT.put(OrderStatus.PENDING_PAY.getCode(), WAIT_PAY);
        CODE_TO_TEXT.put(OrderStatus.PENDING_SHIP.getCode(), WAIT_SHIP);
        CODE_TO_TEXT.put(OrderStatus.PENDING_RECEIVE.getCode(), SHIPPED);
        CODE_TO_TEXT.put(OrderStatus.PENDING_COMMENT.getCode(), WAIT_REVIEW);
        CODE_TO_TEXT.put(OrderStatus.FINISHED.getCode(), DONE);
        CODE_TO_TEXT.put(OrderStatus.CANCELED.getCode(), CLOSED);
    }

    private MerchantOrderStatus() {
    }

    /** 数字状态码 → 商家侧状态字符串。 */
    public static String of(Integer code) {
        return CODE_TO_TEXT.getOrDefault(code, WAIT_PAY);
    }

    /** 商家侧状态字符串 → 数字状态码，未知返回 null。 */
    public static Integer toCode(String text) {
        return CODE_TO_TEXT.entrySet().stream()
                .filter(e -> e.getValue().equals(text))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}
