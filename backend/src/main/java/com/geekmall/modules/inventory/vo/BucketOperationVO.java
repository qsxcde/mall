package com.geekmall.modules.inventory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 调拨 / 合并操作单展示对象。
 */
@Data
@Schema(description = "调拨/合并单")
public class BucketOperationVO implements Serializable {

    private Long id;

    private String opNo;

    private String opType;

    private Long productId;

    private Long fromBucketId;

    private Long toBucketId;

    private Integer qty;

    private String operator;

    private String remark;

    private LocalDateTime createTime;
}
