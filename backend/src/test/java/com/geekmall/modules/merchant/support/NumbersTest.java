package com.geekmall.modules.merchant.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聚合结果取值工具单元测试。
 *
 * <p>MyBatis 的 SUM/COUNT 在无匹配行时返回 null，且不同驱动返回 Long / BigDecimal / Double 不等。
 * 商家端概览的每一个数字都要经过这里，取错会直接体现在经营看板上。</p>
 */
class NumbersTest {

    @Nested
    @DisplayName("转 long")
    class ToLong {

        @Test
        @DisplayName("null 视为 0")
        void nullIsZero() {
            assertThat(Numbers.l(null)).isZero();
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"12, 12", "-3, -3"})
        void fromText(String text, long expected) {
            assertThat(Numbers.l(text)).isEqualTo(expected);
        }

        @Test
        @DisplayName("支持 Number 各子类型（不同驱动返回类型不一致）")
        void fromNumberSubtypes() {
            assertThat(Numbers.l(7)).isEqualTo(7L);
            assertThat(Numbers.l(7L)).isEqualTo(7L);
            assertThat(Numbers.l(new BigDecimal("7"))).isEqualTo(7L);
            assertThat(Numbers.l(7.9d)).isEqualTo(7L);
        }

        @Test
        @DisplayName("i() 复用 long 的语义并收窄为 int")
        void toInt() {
            assertThat(Numbers.i(null)).isZero();
            assertThat(Numbers.i(5L)).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("转 double / BigDecimal")
    class ToDecimal {

        @Test
        @DisplayName("null 视为 0")
        void nullIsZero() {
            assertThat(Numbers.d(null)).isZero();
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"2.5, 2.5", "-1.25, -1.25"})
        void fromText(String text, double expected) {
            assertThat(Numbers.d(text)).isEqualTo(expected);
        }

        @Test
        @DisplayName("bd() 统一保留 2 位并使用 HALF_UP")
        void bdRoundsHalfUp() {
            assertThat(Numbers.bd("12.345")).isEqualByComparingTo("12.35");
            assertThat(Numbers.bd("12.344")).isEqualByComparingTo("12.34");
            assertThat(Numbers.bd(new BigDecimal("1.005"))).isEqualByComparingTo("1.01");
        }

        @Test
        @DisplayName("bd(null) 返回 0.00 而不是 null，便于直接参与运算")
        void bdNullIsZeroWithScale() {
            assertThat(Numbers.bd(null)).isEqualByComparingTo("0.00");
            assertThat(Numbers.bd(null).scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("安全运算")
    class SafeMath {

        @Test
        @DisplayName("环比：上期为 0 时返回 0，避免出现 Infinity / NaN")
        void chainRatioGuardsZeroBase() {
            assertThat(Numbers.chainRatio(100, 0)).isZero();
            assertThat(Numbers.chainRatio(0, 0)).isZero();
            assertThat(Numbers.chainRatio(120, 100)).isEqualTo(0.2d);
            assertThat(Numbers.chainRatio(80, 100)).isEqualTo(-0.2d);
        }

        @Test
        @DisplayName("除法：分母为 0 时返回 0")
        void divideGuardsZeroDenominator() {
            assertThat(Numbers.divide(5, 0)).isZero();
            assertThat(Numbers.divide(1, 4)).isEqualTo(0.25d);
        }

        @Test
        @DisplayName("四舍五入到指定精度")
        void roundToScale() {
            assertThat(Numbers.round(3.14159, 2)).isEqualTo(3.14d);
            assertThat(Numbers.round(3.145, 2)).isEqualTo(3.15d);
            assertThat(Numbers.round(2.5, 0)).isEqualTo(3.0d);
        }

        @Test
        @DisplayName("占比计算与环比组合使用的典型路径：某省份成交占比")
        void regionShareScenario() {
            double province = 300;
            double total = 1000;
            assertThat(Numbers.round(Numbers.divide(province, total), 4)).isEqualTo(0.3d);
            // 总量为 0 时不应抛异常
            assertThat(Numbers.divide(province, 0)).isZero();
        }
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("所有转换入口对 null 都不抛异常")
    void neverThrowsOnNull(Object value) {
        assertThat(Numbers.l(value)).isZero();
        assertThat(Numbers.i(value)).isZero();
        assertThat(Numbers.d(value)).isZero();
        assertThat(Numbers.bd(value)).isEqualByComparingTo("0.00");
    }
}
