package com.geekmall.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 桶间调拨 / 合并操作单 inv_bucket_operation：append-only 业务凭证。
 */
@Data
@TableName("inv_bucket_operation")
public class InvBucketOperation implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作单号 */
    private String opNo;

    /** TRANSFER / MERGE */
    private String opType;

    private Long productId;

    private Long shopId;

    private Long fromBucketId;

    private Long toBucketId;

    private Integer qty;

    /** 1 成功 */
    private Integer status;

    private String operator;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
