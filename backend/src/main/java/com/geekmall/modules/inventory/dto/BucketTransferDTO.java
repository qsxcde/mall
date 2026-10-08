package com.geekmall.modules.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 跨桶调拨入参：从来源桶搬指定数量到目标桶，商品总库存不变。
 */
@Data
@Schema(description = "跨桶调拨入参")
public class BucketTransferDTO implements Serializable {

    @NotNull(message = "来源桶 ID 不能为空")
    private Long fromBucketId;

    @NotNull(message = "目标桶 ID 不能为空")
    private Long toBucketId;

    @NotNull(message = "调拨数量不能为空")
    @Min(value = 1, message = "调拨数量至少为 1")
    private Integer qty;

    private String operator;

    private String remark;
}
