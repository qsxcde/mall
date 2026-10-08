package com.geekmall.modules.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 跨桶合并入参：把多个来源桶的余量全部并入目标桶，商品总库存不变。
 */
@Data
@Schema(description = "跨桶合并入参")
public class BucketMergeDTO implements Serializable {

    @NotNull(message = "商品 ID 不能为空")
    private Long productId;

    @NotEmpty(message = "来源桶不能为空")
    @Schema(description = "被合并的来源桶 ID 列表")
    private List<Long> sourceBucketIds;

    @Schema(description = "目标桶 ID（与 targetDimensionValue 二选一）")
    private Long targetBucketId;

    @Schema(description = "目标维度值，为空时取第一个来源桶的维度值")
    private String targetDimensionValue;

    @Schema(description = "操作人")
    private String operator;

    private String remark;
}
