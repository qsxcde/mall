package com.geekmall.modules.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 把商品现有库存分配到各桶的入参。
 *
 * <p>本操作**不改变商品总库存**，只把 {@code pms_product.stock} 拆到桶上；
 * 各桶数量之和若无须等于总量，差额会落入「未分配」桶，保证守恒。</p>
 */
@Data
@Schema(description = "库存分配入参")
public class BucketAllocateDTO implements Serializable {

    @NotNull(message = "商品 ID 不能为空")
    private Long productId;

    @Schema(description = "应用的分桶规则 ID，可为空")
    private Long ruleId;

    @Schema(description = "分桶维度；指定 ruleId 时以规则为准，二者至少提供一个")
    private String dimension;

    @NotEmpty(message = "分桶明细不能为空")
    @Valid
    private List<Item> items;

    @Schema(description = "操作人")
    private String operator;

    private String remark;

    /**
     * 单个桶的分配明细。
     */
    @Data
    @Schema(description = "单桶分配明细")
    public static class Item implements Serializable {

        @NotBlank(message = "维度值不能为空")
        @Schema(description = "维度值：仓库名 / 批次号 / 效期(yyyy-MM-dd) / 地区")
        private String dimensionValue;

        @NotNull(message = "分配数量不能为空")
        @Min(value = 0, message = "分配数量不能为负")
        private Integer qty;

        @Schema(description = "仓库（维度为 WAREHOUSE 时必填，其余可选）")
        private String warehouse;

        @Schema(description = "批次号")
        private String batchNo;

        @Schema(description = "效期，维度为 EXPIRY 时可填")
        private LocalDate expireDate;

        @Schema(description = "地区")
        private String region;

        @Schema(description = "人工优先级，越小越先出")
        private Integer priority;
    }
}
