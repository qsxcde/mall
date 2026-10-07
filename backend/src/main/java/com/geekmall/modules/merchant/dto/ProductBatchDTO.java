package com.geekmall.modules.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 商品批量操作入参：改状态 / 批量改价共用。
 */
@Data
@Schema(description = "商品批量操作")
public class ProductBatchDTO implements Serializable {

    @NotEmpty(message = "请选择商品")
    @Schema(description = "商品 ID 列表")
    private List<Long> ids;

    @Schema(description = "目标状态（改状态时使用）：on/ware/audit/sold/off/trash")
    private String status;

    @Schema(description = "改价模式：pct 按百分比 / amount 按固定金额")
    private String mode;

    @Schema(description = "改价数值：pct 模式为百分比（如 -10 表示降价 10%），amount 模式为金额增减")
    private BigDecimal value;
}
