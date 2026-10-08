package com.geekmall.modules.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 库存桶审计流水查询入参。
 */
@Data
@Schema(description = "分桶审计日志查询")
public class BucketLogQueryDTO implements Serializable {

    @Schema(description = "商品 ID")
    private Long productId;

    @Schema(description = "桶 ID")
    private Long bucketId;

    @Schema(description = "业务类型：ALLOCATE/OUTBOUND/TRANSFER_IN/TRANSFER_OUT/MERGE_IN/MERGE_OUT/ROLLBACK/ADJUST")
    private String bizType;

    @Schema(description = "业务单号")
    private String orderNo;

    private long page = 1;

    /** 每页条数，与商家端其它列表页保持同名（size） */
    private long size = 20;
}
