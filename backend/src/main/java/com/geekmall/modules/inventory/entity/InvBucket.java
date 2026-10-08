package com.geekmall.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 库存桶 inv_bucket：一个 (商品 × 维度值) 一行，是库存扣减/回加的最小并发单元。
 *
 * <p>并发控制：所有余量变更都走 {@code UPDATE ... WHERE id=? AND stock >= qty} 的 CAS，
 * 不依赖乐观锁插件，也不在数据库之外持有锁。</p>
 */
@Data
@TableName("inv_bucket")
public class InvBucket implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private Long shopId;

    /** 来源分桶规则 ID */
    private Long ruleId;

    private String dimension;

    /** 维度值：仓库名 / 批次号 / 效期(yyyy-MM-dd) / 地区 */
    private String dimensionValue;

    private String warehouse;

    private String batchNo;

    private LocalDate expireDate;

    private String region;

    /** 桶内余量 */
    private Integer stock;

    /** 桶内累计入桶量，用于「入桶总量 = 余量 + 已出库」对账 */
    private Integer total;

    /** 人工优先级，越小越先出 */
    private Integer priority;

    /** 1 正常 0 冻结（冻结桶不参与出库） */
    private Integer status;

    private LocalDateTime lastSyncTime;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
