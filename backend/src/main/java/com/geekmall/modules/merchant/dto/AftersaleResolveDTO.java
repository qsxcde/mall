package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 售后处理入参。
 */
@Data
@Schema(description = "售后处理")
public class AftersaleResolveDTO implements Serializable {

    @NotEmpty(message = "请选择工单")
    @Schema(description = "工单 ID 列表")
    private List<String> ids;

    @NotBlank(message = "处理动作不能为空")
    @Schema(description = "动作：approve 同意 / reject 拒绝 / receive 确认收货 / reaudit 重新审核")
    private String action;

    @Schema(description = "同意时的退款金额（可部分退款）")
    private BigDecimal refundAmount;

    @Schema(description = "拒绝理由")
    private String rejectReason;

    @Schema(description = "退货地址（同意退货时返回给买家）")
    private String address;
}
