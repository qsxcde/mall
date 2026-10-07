package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 订单批量操作入参：发货 / 关闭共用。
 */
@Data
@Schema(description = "订单批量操作")
public class OrderBatchDTO implements Serializable {

    @NotEmpty(message = "请选择订单")
    @Schema(description = "订单号列表")
    private List<String> ids;

    @Schema(description = "快递公司（发货时使用）")
    private String express;

    @Schema(description = "运单号（发货时使用）")
    private String waybill;

    @Schema(description = "关闭原因")
    private String reason;
}
