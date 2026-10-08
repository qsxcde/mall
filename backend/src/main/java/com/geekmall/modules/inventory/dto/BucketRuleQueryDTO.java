package com.geekmall.modules.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 分桶规则查询入参。
 */
@Data
@Schema(description = "分桶规则查询")
public class BucketRuleQueryDTO implements Serializable {

    @Schema(description = "分桶维度")
    private String dimension;

    @Schema(description = "1 启用 0 停用，为空不筛选")
    private Integer enabled;

    @Schema(description = "商品 ID（含全店通用规则）")
    private Long productId;
}
