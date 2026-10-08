package com.geekmall.modules.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 库存桶查询入参。
 */
@Data
@Schema(description = "库存桶查询")
public class BucketQueryDTO implements Serializable {

    @Schema(description = "商品 ID")
    private Long productId;

    @Schema(description = "分桶维度：WAREHOUSE / BATCH / EXPIRY / REGION")
    private String dimension;

    @Schema(description = "维度值关键字（模糊匹配）")
    private String keyword;

    @Schema(description = "只看有余量的桶")
    private Boolean onlyPositive = Boolean.TRUE;

    @Schema(description = "1 正常 0 冻结，为空不筛选")
    private Integer status;

    private long page = 1;

    /** 每页条数，与商家端其它列表页保持同名（size） */
    private long size = 20;
}
