package com.geekmall.modules.payment.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 支付单出参，对应前端 PaymentView。
 */
@Data
@Schema(description = "支付单")
public class PaymentVO implements Serializable {

    private String tradeNo;

    private String orderNo;

    private BigDecimal amount;

    private String payMethod;

    @Schema(description = "支付方式文案，如「微信支付」")
    private String payMethodLabel;

    @Schema(description = "0 待支付 1 成功 2 失败 3 已关闭")
    private Integer status;

    private String statusText;

    @Schema(description = "支付渠道：local 本地模拟 / alipay 支付宝沙箱")
    private String channel;

    @Schema(description = "退款状态：0 未退款 1 退款中 2 已退款 3 退款失败")
    private Integer refundStatus;

    @Schema(description = "二维码内容，扫码支付场景使用；余额/银行卡支付为 null")
    private String qrCode;

    private LocalDateTime callbackTime;

    @Schema(description = "剩余支付秒数")
    private Long expireSecondsLeft;
}
