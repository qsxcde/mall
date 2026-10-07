package com.geekmall.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单状态机单元测试。
 *
 * <p>流转表是「谁能改状态、能改成什么」的唯一来源，支付回调、超时任务、
 * 用户取消都依赖它，因此这里对合法 / 非法流转做穷举式校验。</p>
 */
class OrderStatusTest {

    @Nested
    @DisplayName("根据状态码解析")
    class Of {

        @ParameterizedTest(name = "状态码 {0} → {1}")
        @CsvSource({
                "0, PENDING_PAY",
                "1, PENDING_SHIP",
                "2, PENDING_RECEIVE",
                "3, PENDING_COMMENT",
                "4, FINISHED",
                "5, CANCELED"
        })
        void shouldResolveKnownCodes(int code, OrderStatus expected) {
            assertThat(OrderStatus.of(code)).isEqualTo(expected);
        }

        @Test
        @DisplayName("状态码为 null 时返回 null，而不是抛异常")
        void shouldReturnNullWhenCodeIsNull() {
            assertThat(OrderStatus.of(null)).isNull();
        }

        @ParameterizedTest(name = "未知状态码 {0} 返回 null")
        @ValueSource(ints = {-1, 6, 99})
        void shouldReturnNullForUnknownCodes(int code) {
            assertThat(OrderStatus.of(code)).isNull();
        }
    }

    @Nested
    @DisplayName("合法流转")
    class LegalTransitions {

        @ParameterizedTest(name = "{0} 允许流转到 {1}")
        @CsvSource({
                "PENDING_PAY, PENDING_SHIP",
                "PENDING_PAY, CANCELED",
                "PENDING_SHIP, PENDING_RECEIVE",
                "PENDING_SHIP, CANCELED",
                "PENDING_RECEIVE, PENDING_COMMENT",
                "PENDING_COMMENT, FINISHED"
        })
        void shouldAllow(OrderStatus from, OrderStatus to) {
            assertThat(from.canTransferTo(to)).isTrue();
        }
    }

    @Nested
    @DisplayName("非法流转")
    class IllegalTransitions {

        @ParameterizedTest(name = "{0} 不允许越过中间状态流转到 {1}")
        @CsvSource({
                // 未支付不能直接发货 / 收货 / 完成
                "PENDING_PAY, PENDING_RECEIVE",
                "PENDING_PAY, PENDING_COMMENT",
                "PENDING_PAY, FINISHED",
                // 待收货不能跳过确认收货直接评价完成
                "PENDING_RECEIVE, FINISHED",
                // 终态不允许再流转
                "FINISHED, CANCELED",
                "CANCELED, PENDING_PAY",
                "CANCELED, PENDING_SHIP"
        })
        void shouldReject(OrderStatus from, OrderStatus to) {
            assertThat(from.canTransferTo(to)).isFalse();
        }

        @ParameterizedTest(name = "{0} 不允许流转到自身（同态由幂等逻辑处理）")
        @EnumSource(OrderStatus.class)
        void shouldNotTransferToSelf(OrderStatus status) {
            assertThat(status.canTransferTo(status)).isFalse();
        }

        @Test
        @DisplayName("对终态而言，流转到任何状态都是非法的")
        void shouldRejectEveryTargetFromFinalStates() {
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(OrderStatus.FINISHED.canTransferTo(target)).isFalse();
                assertThat(OrderStatus.CANCELED.canTransferTo(target)).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("终态判定与文案")
    class FinalStateAndText {

        @Test
        @DisplayName("只有已完成与已取消属于终态")
        void shouldIdentifyFinalStates() {
            assertThat(OrderStatus.FINISHED.isFinal()).isTrue();
            assertThat(OrderStatus.CANCELED.isFinal()).isTrue();
            for (OrderStatus status : OrderStatus.values()) {
                if (status == OrderStatus.FINISHED || status == OrderStatus.CANCELED) {
                    continue;
                }
                assertThat(status.isFinal()).as(status.name()).isFalse();
            }
        }

        @ParameterizedTest(name = "{0} 的文案为 {1}")
        @CsvSource({
                "PENDING_PAY, 待付款",
                "PENDING_SHIP, 待发货",
                "PENDING_RECEIVE, 待收货",
                "PENDING_COMMENT, 待评价",
                "FINISHED, 已完成",
                "CANCELED, 已取消"
        })
        void shouldExposeText(OrderStatus status, String text) {
            assertThat(status.getText()).isEqualTo(text);
        }

        @ParameterizedTest(name = "{0} 的状态码为 {1}")
        @CsvSource({
                "PENDING_PAY, 0",
                "PENDING_SHIP, 1",
                "FINISHED, 4",
                "CANCELED, 5"
        })
        void shouldExposeCode(OrderStatus status, int code) {
            assertThat(status.getCode()).isEqualTo(code);
        }
    }
}
