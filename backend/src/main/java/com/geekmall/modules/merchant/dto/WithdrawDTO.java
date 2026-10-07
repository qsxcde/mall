package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 提现申请入参。
 */
@Data
@Schema(description = "提现申请")
public class WithdrawDTO implements Serializable {

    @NotNull(message = "提现金额不能为空")
    @DecimalMin(value = "0.01", message = "提现金额必须大于 0")
    @Schema(description = "提现金额（元）")
    private BigDecimal amount;

    /**
     * 提现请求号（幂等键）。
     *
     * <p>由客户端在「一次提现意图」内保持不变：用户重复点击、网络重试都必须复用同一个值，
     * 服务端据此识别为同一笔提现，不会重复扣款。缺少该字段的客户端会在参数校验阶段被拒绝，
     * 因为无法区分「用户真想提两笔」和「同一笔被提交两次」。</p>
     */
    @NotBlank(message = "缺少提现请求号")
    @Size(max = 64, message = "提现请求号过长")
    @Schema(description = "提现请求号（幂等键，同一笔提现须复用）")
    private String requestId;
}
