package com.geekmall.modules.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 出库扣减入参：按优先级从各桶扣减，并同步扣减商品总库存。
 *
 * <p>{@code orderNo} 为幂等键：同一单号重复调用不会重复扣减。</p>
 */
@Data
@Schema(description = "分桶出库入参")
public class BucketDeductDTO implements Serializable {

    @NotNull(message = "商品 ID 不能为空")
    private Long productId;

    @NotNull(message = "出库数量不能为空")
    @Min(value = 1, message = "出库数量至少为 1")
    private Integer qty;

    @Schema(description = "出库优先级：FIFO（默认）/ EXPIRY_FIRST / MANUAL，为空时取规则默认值")
    private String policy;

    @Schema(description = "业务单号（幂等键），建议传入订单号")
    private String orderNo;

    private String operator;

    private String remark;
}
