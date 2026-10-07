package com.geekmall.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 支付方式。
 *
 * <p>数据库与接口参数统一存英文码（wechat / alipay / card / balance），
 * 展示文案由本枚举提供 —— 此前交易域与支付域各维护了一份相同的映射表，
 * 商家端若再抄一份就会出现第三个版本，故收敛到这里统一维护。</p>
 */
@Getter
public enum PayMethod {

    WECHAT("wechat", "微信支付"),
    ALIPAY("alipay", "支付宝"),
    CARD("card", "银行卡"),
    BALANCE("balance", "账户余额");

    private final String code;
    private final String label;

    PayMethod(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 缺省支付方式，与前端下单默认值保持一致。 */
    public static final String DEFAULT_CODE = "wechat";

    /** 码 → 文案；未知码原样返回，便于暴露未登记的新渠道而不是显示空白。 */
    public static String labelOf(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(m -> m.code.equals(code))
                .map(PayMethod::getLabel)
                .findFirst()
                .orElse(code);
    }
}
