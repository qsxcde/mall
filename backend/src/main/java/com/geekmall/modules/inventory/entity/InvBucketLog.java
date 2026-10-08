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
 * 库存桶审计流水 inv_bucket_log：append-only，任何桶余量变化都留痕。
 *
 * <p>{@code (biz_type, biz_id, bucket_id)} 唯一索引用于幂等：
 * 同一业务动作重复执行时第二次插入被忽略，避免「重复扣回 / 凭空造库存」。</p>
 */
@Data
@TableName("inv_bucket_log")
public class InvBucketLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bucketId;

    private Long productId;

    /** 所属店铺（数据隔离维度） */
    private Long shopId;

    /** ALLOCATE / OUTBOUND / TRANSFER_IN / TRANSFER_OUT / MERGE_IN / MERGE_OUT / ROLLBACK / ADJUST */
    private String bizType;

    /** 变化量：正数增加、负数减少 */
    private Integer changeQty;

    private Integer beforeStock;

    private Integer afterStock;

    private String orderNo;

    /** 幂等键 */
    private String bizId;

    private String bucketKey;

    private String operator;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
