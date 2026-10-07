package com.geekmall.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付方式枚举单元测试。
 *
 * <p>关键契约：接口参数与数据库存英文码，展示才用中文文案；
 * 未知码必须原样返回，以便暴露未登记的新渠道而不是显示空白。</p>
 */
class PayMethodTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "wechat, 微信支付",
            "alipay, 支付宝",
            "card, 银行卡",
            "balance, 账户余额"
    })
    void shouldMapCodeToLabel(String code, String label) {
        assertThat(PayMethod.labelOf(code)).isEqualTo(label);
    }

    @Test
    @DisplayName("未知码原样返回，不返回空白也不抛异常")
    void shouldReturnCodeItselfWhenUnknown() {
        assertThat(PayMethod.labelOf("unionpay")).isEqualTo("unionpay");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("空值返回 null")
    void shouldReturnNullForBlank(String code) {
        assertThat(PayMethod.labelOf(code)).isNull();
    }

    @Test
    @DisplayName("缺省支付方式与前端下单默认值一致")
    void shouldExposeDefaultCode() {
        assertThat(PayMethod.DEFAULT_CODE).isEqualTo("wechat");
        assertThat(PayMethod.labelOf(PayMethod.DEFAULT_CODE)).isEqualTo("微信支付");
    }

    @Test
    @DisplayName("码到文案的映射不区分大小写以外的歧义：每个 code 唯一")
    void codesShouldBeUnique() {
        assertThat(PayMethod.values()).extracting(PayMethod::getCode).doesNotHaveDuplicates();
    }
}
