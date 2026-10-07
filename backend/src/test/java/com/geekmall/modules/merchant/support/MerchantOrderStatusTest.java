package com.geekmall.modules.merchant.support;

import com.geekmall.common.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 买家侧数字状态码 ↔ 商家侧字符串状态 映射单元测试。
 */
class MerchantOrderStatusTest {

    @ParameterizedTest(name = "订单状态码 {0} → {1}")
    @CsvSource({
            "0, wait_pay",
            "1, wait_ship",
            "2, shipped",
            "3, wait_review",
            "4, done",
            "5, closed"
    })
    void shouldMapCodeToMerchantStatus(int code, String expected) {
        assertThat(MerchantOrderStatus.of(code)).isEqualTo(expected);
    }

    @Test
    @DisplayName("未知或缺失的状态码兜底为 wait_pay，而不是空串导致前端 Tab 匹配失败")
    void shouldFallbackToWaitPay() {
        assertThat(MerchantOrderStatus.of(99)).isEqualTo(MerchantOrderStatus.WAIT_PAY);
        assertThat(MerchantOrderStatus.of(null)).isEqualTo(MerchantOrderStatus.WAIT_PAY);
    }

    @ParameterizedTest(name = "商家状态 {0} → 状态码 {1}")
    @CsvSource({
            "wait_pay, 0",
            "wait_ship, 1",
            "shipped, 2",
            "wait_review, 3",
            "done, 4",
            "closed, 5"
    })
    void shouldMapMerchantStatusToCode(String text, int expected) {
        assertThat(MerchantOrderStatus.toCode(text)).isEqualTo(expected);
    }

    @Test
    @DisplayName("售后中是派生状态，不对应订单自身状态码，必须返回 null")
    void shouldReturnNullForDerivedStatus() {
        assertThat(MerchantOrderStatus.toCode(MerchantOrderStatus.AFTER)).isNull();
        assertThat(MerchantOrderStatus.toCode("unknown")).isNull();
        assertThat(MerchantOrderStatus.toCode(null)).isNull();
    }

    @Test
    @DisplayName("状态映射必须与买家侧枚举一一对应，避免新增状态后遗漏")
    void shouldCoverAllOrderStatuses() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(MerchantOrderStatus.of(status.getCode()))
                    .as("订单状态 %s 缺少商家侧映射", status.name())
                    .isNotBlank();
            assertThat(MerchantOrderStatus.toCode(MerchantOrderStatus.of(status.getCode())))
                    .isEqualTo(status.getCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wait_pay", "wait_ship", "shipped", "wait_review", "done", "closed"})
    @DisplayName("toCode 与 of 互为逆运算")
    void shouldRoundTrip(String text) {
        assertThat(MerchantOrderStatus.of(MerchantOrderStatus.toCode(text))).isEqualTo(text);
    }
}
