package com.geekmall.modules.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建支付单入参（收银台）。
 */
@Data
@Schema(description = "创建支付")
public class CreatePaymentDTO implements Serializable {

    @NotBlank(message = "缺少订单号")
    private String orderNo;

    @Schema(description = "支付方式：wechat / alipay / card / balance")
    private String payMethod = "wechat";
}
