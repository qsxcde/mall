package com.geekmall.modules.trade.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.geekmall.common.enums.OrderStatus;
import com.geekmall.common.event.OrderStatusChangedEvent;
import com.geekmall.common.exception.BizException;
import com.geekmall.modules.trade.entity.Order;
import com.geekmall.modules.trade.entity.OrderStatusLog;
import com.geekmall.modules.trade.mapper.OrderMapper;
import com.geekmall.modules.trade.mapper.OrderStatusLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单状态机单元测试（Mockito 隔离持久层）。
 *
 * <p>重点覆盖 CAS 条件更新的三条分支：成功、被并发流转到同一目标（幂等）、
 * 被并发流转到其他状态（必须报错让调用方刷新）。这是「不重复回退库存 /
 * 不重复核销优惠券」的最后一道防线。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderStateMachineTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderStatusLogMapper statusLogMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrderStateMachine stateMachine;

    private static Order order(int status) {
        Order order = new Order();
        order.setId(100L);
        order.setOrderNo("GM202610010001");
        order.setUserId(1L);
        order.setStatus(status);
        return order;
    }

    @SuppressWarnings("unchecked")
    private void stubUpdate(int rows) {
        when(orderMapper.update(any(Order.class), any(Wrapper.class))).thenReturn(rows);
    }

    @Nested
    @DisplayName("前置校验")
    class Validation {

        @Test
        @DisplayName("状态码非法时直接报错，不尝试写库")
        void shouldRejectUnknownStatus() {
            Order order = order(99);

            assertThatThrownBy(() -> stateMachine.transfer(order, OrderStatus.CANCELED, "user", "取消"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("订单状态异常");

            verify(orderMapper, never()).update(any(Order.class), any(Wrapper.class));
        }

        @Test
        @DisplayName("非法流转（已完成 → 已取消）被拒绝")
        void shouldRejectIllegalTransition() {
            Order order = order(OrderStatus.FINISHED.getCode());

            assertThatThrownBy(() -> stateMachine.transfer(order, OrderStatus.CANCELED, "user", "取消"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不允许变更为");

            verify(orderMapper, never()).update(any(Order.class), any(Wrapper.class));
        }

        @Test
        @DisplayName("已是目标状态时按幂等返回，不写库也不发事件")
        void shouldSkipWhenAlreadyAtTarget() {
            Order order = order(OrderStatus.PENDING_SHIP.getCode());

            Order result = stateMachine.transfer(order, OrderStatus.PENDING_SHIP, "payment", "支付成功");

            assertThat(result).isSameAs(order);
            verify(orderMapper, never()).update(any(Order.class), any(Wrapper.class));
            verify(statusLogMapper, never()).insert(any(OrderStatusLog.class));
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("流转成功")
    class SuccessfulTransfer {

        @Test
        @DisplayName("更新订单、记录状态日志、发布状态变更事件，并回填本地状态")
        void shouldTransferAndLog() {
            Order order = order(OrderStatus.PENDING_PAY.getCode());
            stubUpdate(1);

            Order result = stateMachine.transfer(order, OrderStatus.PENDING_SHIP, "payment", "支付成功");

            assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());

            ArgumentCaptor<Order> updateCaptor = ArgumentCaptor.forClass(Order.class);
            verify(orderMapper).update(updateCaptor.capture(), any(Wrapper.class));
            Order update = updateCaptor.getValue();
            assertThat(update.getId()).isEqualTo(100L);
            assertThat(update.getStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());
            // 流转到待发货需落支付时间
            assertThat(update.getPayTime()).isNotNull();

            ArgumentCaptor<OrderStatusLog> logCaptor = ArgumentCaptor.forClass(OrderStatusLog.class);
            verify(statusLogMapper).insert(logCaptor.capture());
            OrderStatusLog log = logCaptor.getValue();
            assertThat(log.getOrderNo()).isEqualTo("GM202610010001");
            assertThat(log.getFromStatus()).isZero();
            assertThat(log.getToStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());
            assertThat(log.getOperator()).isEqualTo("payment");
            assertThat(log.getRemark()).isEqualTo("支付成功");

            ArgumentCaptor<OrderStatusChangedEvent> eventCaptor =
                    ArgumentCaptor.forClass(OrderStatusChangedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().orderNo()).isEqualTo("GM202610010001");
            assertThat(eventCaptor.getValue().fromStatus()).isZero();
            assertThat(eventCaptor.getValue().toStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());
        }

        @Test
        @DisplayName("取消订单会写入取消原因与取消时间")
        void shouldRecordCancelReason() {
            Order order = order(OrderStatus.PENDING_PAY.getCode());
            stubUpdate(1);

            stateMachine.transfer(order, OrderStatus.CANCELED, OrderStateMachine.OPERATOR_SYSTEM, "超时未支付，系统自动取消");

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderMapper).update(captor.capture(), any(Wrapper.class));
            assertThat(captor.getValue().getCancelTime()).isNotNull();
            assertThat(captor.getValue().getCancelReason()).isEqualTo("超时未支付，系统自动取消");
        }
    }

    @Nested
    @DisplayName("CAS 竞争")
    class CasContention {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("更新影响行数为 0 且最新状态已是目标状态：按幂等返回，不重复写日志")
        void shouldReturnIdempotentWhenConcurrentlyMovedToSameTarget() {
            Order order = order(OrderStatus.PENDING_PAY.getCode());
            when(orderMapper.update(any(Order.class), any(Wrapper.class))).thenReturn(0);
            Order latest = order(OrderStatus.PENDING_SHIP.getCode());
            when(orderMapper.selectById(100L)).thenReturn(latest);

            Order result = stateMachine.transfer(order, OrderStatus.PENDING_SHIP, "payment", "支付成功");

            assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING_SHIP.getCode());
            verify(statusLogMapper, never()).insert(any(OrderStatusLog.class));
            verify(eventPublisher, never()).publishEvent(any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("更新影响行数为 0 且最新状态是其他值：报错要求刷新，避免静默丢操作")
        void shouldFailWhenConcurrentlyMovedElsewhere() {
            Order order = order(OrderStatus.PENDING_PAY.getCode());
            when(orderMapper.update(any(Order.class), any(Wrapper.class))).thenReturn(0);
            when(orderMapper.selectById(100L)).thenReturn(order(OrderStatus.CANCELED.getCode()));

            assertThatThrownBy(() -> stateMachine.transfer(order, OrderStatus.PENDING_SHIP, "payment", "支付成功"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("已被其他操作变更");

            verify(statusLogMapper, never()).insert(any(OrderStatusLog.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("订单在竞争中被删除（查不到）时同样报错，而不是抛 NPE")
        void shouldFailWhenOrderVanished() {
            Order order = order(OrderStatus.PENDING_PAY.getCode());
            when(orderMapper.update(any(Order.class), any(Wrapper.class))).thenReturn(0);
            when(orderMapper.selectById(100L)).thenReturn(null);

            assertThatThrownBy(() -> stateMachine.transfer(order, OrderStatus.PENDING_SHIP, "payment", "支付成功"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("已被其他操作变更");
        }

        @Test
        @DisplayName("商家后台操作使用独立的操作方标识，便于审计区分")
        void merchantOperatorIsDistinct() {
            assertThat(OrderStateMachine.OPERATOR_MERCHANT)
                    .isNotEqualTo(OrderStateMachine.OPERATOR_USER)
                    .isNotEqualTo(OrderStateMachine.OPERATOR_SYSTEM)
                    .isNotEqualTo(OrderStateMachine.OPERATOR_PAY)
                    .isEqualTo("merchant");
        }
    }
}
