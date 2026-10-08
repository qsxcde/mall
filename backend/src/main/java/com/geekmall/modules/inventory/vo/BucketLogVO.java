package com.geekmall.modules.inventory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存桶审计流水展示对象。
 */
@Data
@Schema(description = "分桶审计日志")
public class BucketLogVO implements Serializable {

    private Long id;

    private Long bucketId;

    private Long productId;

    private String bizType;

    private Integer changeQty;

    private Integer beforeStock;

    private Integer afterStock;

    private String orderNo;

    private String bucketKey;

    private String operator;

    private String remark;

    private LocalDateTime createTime;
}
