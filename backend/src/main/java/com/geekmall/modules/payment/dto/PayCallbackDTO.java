package com.geekmall.modules.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 支付回调入参（第三方异步通知）。
 *
 * <p>真实渠道还需要验签：用渠道公钥校验 sign，并校验 out_trade_no / total_amount 一致。
 * 骨架阶段以 tradeNo + amount 双重校验 + 唯一索引幂等代替。</p>
 */
@Data
@Schema(description = "支付回调")
public class PayCallbackDTO implements Serializable {

    @NotBlank(message = "缺少支付流水号")
    private String tradeNo;

    @NotBlank(message = "缺少订单号")
    private String orderNo;

    @NotNull(message = "缺少支付金额")
    private BigDecimal amount;

    @Schema(description = "1 支付成功，2 支付失败")
    private Integer status = 1;

    @Schema(description = "渠道签名，接入真实渠道后启用校验")
    private String sign;
}
