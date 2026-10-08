package com.geekmall.modules.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 分桶规则新增 / 编辑入参。
 */
@Data
@Schema(description = "分桶规则保存")
public class BucketRuleSaveDTO implements Serializable {

    @Schema(description = "规则 ID，为空表示新增")
    private Long id;

    @NotBlank(message = "规则名称不能为空")
    @Schema(description = "规则名称（同店铺内唯一）")
    private String ruleName;

    @NotBlank(message = "分桶维度不能为空")
    @Schema(description = "分桶维度：WAREHOUSE / BATCH / EXPIRY / REGION")
    private String dimension;

    @Schema(description = "分桶粒度：SKU（默认）/ SKU_BATCH")
    private String granularity = "SKU";

    @Schema(description = "出库优先级：FIFO（默认）/ EXPIRY_FIRST / MANUAL")
    private String deductPolicy = "FIFO";

    @Schema(description = "限定商品 ID，为空表示全店通用")
    private Long productId;

    @Schema(description = "1 启用 0 停用")
    private Integer enabled = 1;

    private String remark;
}
