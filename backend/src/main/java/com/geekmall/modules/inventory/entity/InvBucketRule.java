package com.geekmall.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存分桶规则 inv_bucket_rule：定义「按什么维度拆桶、出库按什么优先级挑桶」。
 */
@Data
@TableName("inv_bucket_rule")
public class InvBucketRule implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则名称，同店铺内唯一 */
    private String ruleName;

    /** 分桶维度：WAREHOUSE / BATCH / EXPIRY / REGION */
    private String dimension;

    /** 分桶粒度：SKU / SKU_BATCH */
    private String granularity;

    /** 出库挑桶优先级：FIFO / EXPIRY_FIRST / MANUAL */
    private String deductPolicy;

    /** 所属店铺，0 表示平台级规则 */
    private Long shopId;

    /** 限定商品 ID，NULL 表示全店通用 */
    private Long productId;

    /** 1 启用 0 停用 */
    private Integer enabled;

    private String remark;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
