package com.geekmall.modules.inventory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 库存桶展示对象。
 */
@Data
@Schema(description = "库存桶")
public class BucketVO implements Serializable {

    private Long id;

    private Long productId;

    private Long ruleId;

    private String dimension;

    private String dimensionValue;

    private String warehouse;

    private String batchNo;

    private LocalDate expireDate;

    private String region;

    @Schema(description = "桶内余量")
    private Integer stock;

    @Schema(description = "桶内累计入桶量")
    private Integer total;

    private Integer priority;

    private Integer status;

    private LocalDateTime lastSyncTime;

    private LocalDateTime createTime;
}
