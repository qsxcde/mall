package com.geekmall.modules.trade.converter;

import com.geekmall.common.enums.OrderStatus;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderItem;
import com.geekmall.modules.trade.entity.OrderStatusLog;
import com.geekmall.modules.trade.vo.OrderItemVO;
import com.geekmall.modules.trade.vo.OrderTimelineVO;
import com.geekmall.modules.trade.vo.OrderVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单转换器单元测试。
 *
 * <p>其中「按钮可用性」是后端统一下发的业务判断，前端不再重复实现状态机，
 * 因此对每种订单状态都做了穷举断言。</p>
 */
class OrderConverterTest {

    private static Order order(int status) {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNo("GM202610010001");
        order.setStatus(status);
        order.setGoodsAmount(new BigDecimal("5999.00"));
        order.setShippingFee(new BigDecimal("18.00"));
        order.setDiscount(new BigDecimal("50.00"));
        order.setPayAmount(new BigDecimal("5967.00"));
        order.setCreateTime(LocalDateTime.of(2026, 10, 1, 10, 0));
        return order;
    }

    private static OrderItem item(long productId, String title, String price, int qty) {
        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setTitle(title);
        item.setCover("https://img/" + productId + ".png");
        item.setSpec("256G");
        item.setPrice(new BigDecimal(price));
        item.setQty(qty);
        return item;
    }

    @Nested
    @DisplayName("订单明细")
    class ItemMapping {

        @Test
        @DisplayName("null 入参返回 null")
        void nullItem() {
            assertThat(OrderConverter.toItemVO(null)).isNull();
        }

        @Test
        @DisplayName("小计 = 单价 × 数量，保留 2 位")
        void shouldCalculateAmount() {
            OrderItemVO vo = OrderConverter.toItemVO(item(9L, "极客耳机", "199.90", 3));

            assertThat(vo.getProductId()).isEqualTo(9L);
            assertThat(vo.getTitle()).isEqualTo("极客耳机");
            assertThat(vo.getQty()).isEqualTo(3);
            assertThat(vo.getAmount()).isEqualByComparingTo("599.70");
        }

        @Test
        @DisplayName("数量或单价缺失时小计为 0，不抛 NPE")
        void shouldTolerateMissingFields() {
            OrderItem noQty = item(9L, "极客耳机", "199.90", 0);
            noQty.setQty(null);
            assertThat(OrderConverter.toItemVO(noQty).getAmount()).isEqualByComparingTo("0.00");

            OrderItem noPrice = item(9L, "极客耳机", "0", 2);
            noPrice.setPrice(null);
            assertThat(OrderConverter.toItemVO(noPrice).getAmount()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("订单主对象")
    class OrderMapping {

        @Test
        @DisplayName("null 入参返回 null")
        void nullOrder() {
            assertThat(OrderConverter.toVO(null, List.of())).isNull();
        }

        @Test
        @DisplayName("金额与状态文案透传，件数汇总正确")
        void shouldMapAmountsAndTotalQty() {
            OrderVO vo = OrderConverter.toVO(order(OrderStatus.PENDING_PAY.getCode()),
                    List.of(item(1L, "手机", "5999.00", 1), item(2L, "耳机", "199.00", 2)));

            assertThat(vo.getNo()).isEqualTo("GM202610010001");
            assertThat(vo.getStatus()).isZero();
            assertThat(vo.getStatusText()).isEqualTo("待付款");
            assertThat(vo.getGoodsAmount()).isEqualByComparingTo("5999.00");
            assertThat(vo.getShippingFee()).isEqualByComparingTo("18.00");
            assertThat(vo.getDiscount()).isEqualByComparingTo("50.00");
            assertThat(vo.getPayAmount()).isEqualByComparingTo("5967.00");
            assertThat(vo.getTotalQty()).isEqualTo(3);
        }

        @Test
        @DisplayName("列表页扁平字段取自首件商品")
        void shouldExposeFirstItemFlatFields() {
            OrderVO vo = OrderConverter.toVO(order(0),
                    List.of(item(1L, "手机", "5999.00", 1), item(2L, "耳机", "199.00", 2)));

            assertThat(vo.getProductId()).isEqualTo(1L);
            assertThat(vo.getProductTitle()).isEqualTo("手机");
            assertThat(vo.getPrice()).isEqualByComparingTo("5999.00");
            assertThat(vo.getQty()).isEqualTo(1);
        }

        @Test
        @DisplayName("无明细时列表为空、件数为 0、扁平字段保持空")
        void shouldHandleEmptyItems() {
            OrderVO vo = OrderConverter.toVO(order(0), null);

            assertThat(vo.getItems()).isEmpty();
            assertThat(vo.getTotalQty()).isZero();
            assertThat(vo.getProductId()).isNull();
            assertThat(vo.getProductTitle()).isNull();
        }

        @Test
        @DisplayName("状态码非法时文案为空串，而不是 null 导致前端渲染 undefined")
        void shouldBlankTextForUnknownStatus() {
            OrderVO vo = OrderConverter.toVO(order(99), List.of());
            assertThat(vo.getStatusText()).isEmpty();
        }

        @ParameterizedTest(name = "{0} 的按钮可用性")
        @EnumSource(OrderStatus.class)
        @DisplayName("每种状态的按钮可用性符合状态机")
        void shouldApplyButtonFlags(OrderStatus status) {
            OrderVO vo = OrderConverter.toVO(order(status.getCode()), List.of());

            boolean cancellable = status == OrderStatus.PENDING_PAY || status == OrderStatus.PENDING_SHIP;
            assertThat(vo.getCanCancel()).isEqualTo(cancellable);
            assertThat(vo.getCanPay()).isEqualTo(status == OrderStatus.PENDING_PAY);
            assertThat(vo.getCanConfirm()).isEqualTo(status == OrderStatus.PENDING_RECEIVE);
            assertThat(vo.getCanReview()).isEqualTo(status == OrderStatus.PENDING_COMMENT);
            assertThat(vo.getCanAfterSale())
                    .isEqualTo(status == OrderStatus.PENDING_COMMENT || status == OrderStatus.FINISHED);
        }
    }

    @Nested
    @DisplayName("进度时间轴")
    class Timeline {

        @Test
        @DisplayName("节点文案包含状态文案与备注")
        void shouldComposeText() {
            OrderStatusLog log = new OrderStatusLog();
            log.setToStatus(OrderStatus.PENDING_RECEIVE.getCode());
            log.setRemark("商家已发货");
            log.setCreateTime(LocalDateTime.of(2026, 10, 2, 9, 30));

            OrderTimelineVO vo = OrderConverter.toTimelineVO(log);

            assertThat(vo.getText()).isEqualTo("待收货（商家已发货）");
            assertThat(vo.getTime()).isEqualTo(LocalDateTime.of(2026, 10, 2, 9, 30));
            assertThat(vo.getDone()).isTrue();
        }

        @Test
        @DisplayName("无备注时只显示状态文案")
        void shouldOmitBlankRemark() {
            OrderStatusLog log = new OrderStatusLog();
            log.setToStatus(OrderStatus.PENDING_PAY.getCode());
            log.setRemark("   ");

            assertThat(OrderConverter.toTimelineVO(log).getText()).isEqualTo("待付款");
        }

        @Test
        @DisplayName("状态码非法时文案为空串")
        void shouldBlankForUnknownStatus() {
            OrderStatusLog log = new OrderStatusLog();
            log.setToStatus(99);

            assertThat(OrderConverter.toTimelineVO(log).getText()).isEmpty();
        }
    }

    @Nested
    @DisplayName("地址快照")
    class AddressSnapshot {

        @Test
        @DisplayName("序列化后可原样反序列化")
        void shouldRoundTrip() {
            Map<String, String> address = Map.of(
                    "name", "张三",
                    "phone", "13800000000",
                    "fullAddress", "广东省深圳市南山区科技园 1 号");

            String json = OrderConverter.writeAddress(address);
            Map<String, String> back = OrderConverter.readAddress(json);

            assertThat(back).containsEntry("name", "张三")
                    .containsEntry("phone", "13800000000")
                    .containsEntry("fullAddress", "广东省深圳市南山区科技园 1 号");
        }

        @Test
        @DisplayName("null / 空白 / 非法 JSON 一律返回空 Map，避免脏快照导致详情页 500")
        void shouldDegradeGracefully() {
            assertThat(OrderConverter.readAddress(null)).isEmpty();
            assertThat(OrderConverter.readAddress("")).isEmpty();
            assertThat(OrderConverter.readAddress("   ")).isEmpty();
            assertThat(OrderConverter.readAddress("not-a-json")).isEmpty();
            assertThat(OrderConverter.readAddress("[1,2,3]")).isEmpty();
        }
    }
}
