package com.geekmall.modules.trade.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.trade.entity.OrderStatusLog;
import com.geekmall.modules.trade.vo.OrderItemVO;
import com.geekmall.modules.trade.vo.OrderTimelineVO;
import com.geekmall.modules.trade.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 订单对象转换。
 */
@Slf4j
public final class OrderConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrderConverter() {
    }

    public static OrderItemVO toItemVO(OrderItem item) {
        if (item == null) {
            return null;
        }
        OrderItemVO vo = new OrderItemVO();
        vo.setProductId(item.getProductId());
        vo.setTitle(item.getTitle());
        vo.setCover(item.getCover());
        vo.setSpec(item.getSpec());
        vo.setPrice(item.getPrice());
        vo.setQty(item.getQty());
        int qty = item.getQty() == null ? 0 : item.getQty();
        vo.setAmount(item.getPrice() == null ? BigDecimal.ZERO
                : item.getPrice().multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP));
        return vo;
    }

    public static OrderVO toVO(Order order, List<OrderItem> items) {
        if (order == null) {
            return null;
        }
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setNo(order.getOrderNo());
        vo.setStatus(order.getStatus());
        OrderStatus status = OrderStatus.of(order.getStatus());
        vo.setStatusText(status == null ? "" : status.getText());
        vo.setGoodsAmount(order.getGoodsAmount());
        vo.setShippingFee(order.getShippingFee());
        vo.setDiscount(order.getDiscount());
        vo.setPayAmount(order.getPayAmount());
        vo.setCreateTime(order.getCreateTime());

        List<OrderItem> safeItems = items == null ? List.of() : items;
        vo.setItems(safeItems.stream().map(OrderConverter::toItemVO).toList());
        vo.setTotalQty(safeItems.stream().mapToInt(i -> i.getQty() == null ? 0 : i.getQty()).sum());

        // 首件商品扁平字段，便于列表页直接渲染
        if (!safeItems.isEmpty()) {
            OrderItem first = safeItems.get(0);
            vo.setProductId(first.getProductId());
            vo.setProductTitle(first.getTitle());
            vo.setProductCover(first.getCover());
            vo.setSpec(first.getSpec());
            vo.setPrice(first.getPrice());
            vo.setQty(first.getQty());
        }

        applyButtonFlags(vo, status);
        return vo;
    }

    public static OrderTimelineVO toTimelineVO(OrderStatusLog log) {
        OrderStatus to = OrderStatus.of(log.getToStatus());
        String text = to == null ? "" : to.getText();
        if (log.getRemark() != null && !log.getRemark().isBlank()) {
            text = text + "（" + log.getRemark() + "）";
        }
        return new OrderTimelineVO(text, log.getCreateTime(), true);
    }

    /** 地址快照序列化。 */
    public static String writeAddress(Map<String, String> address) {
        try {
            return MAPPER.writeValueAsString(address);
        } catch (Exception e) {
            log.warn("地址快照序列化失败", e);
            return null;
        }
    }

    /** 地址快照反序列化，失败返回空 Map。 */
    public static Map<String, String> readAddress(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("地址快照反序列化失败：{}", json);
            return Map.of();
        }
    }

    private static void applyButtonFlags(OrderVO vo, OrderStatus status) {
        boolean canCancel = status == OrderStatus.PENDING_PAY || status == OrderStatus.PENDING_SHIP;
        boolean canPay = status == OrderStatus.PENDING_PAY;
        boolean canConfirm = status == OrderStatus.PENDING_RECEIVE;
        boolean canReview = status == OrderStatus.PENDING_COMMENT;
        boolean canAfterSale = status == OrderStatus.PENDING_COMMENT || status == OrderStatus.FINISHED;
        vo.setCanCancel(canCancel);
        vo.setCanPay(canPay);
        vo.setCanConfirm(canConfirm);
        vo.setCanReview(canReview);
        vo.setCanAfterSale(canAfterSale);
    }
}
