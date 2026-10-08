package com.geekmall.modules.inventory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分桶规则展示对象。
 */
@Data
@Schema(description = "分桶规则")
public class BucketRuleVO implements Serializable {

    private Long id;

    private String ruleName;

    private String dimension;

    private String granularity;

    private String deductPolicy;

    private Long productId;

    private Integer enabled;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
